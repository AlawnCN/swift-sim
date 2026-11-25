package com.example.swiftsim.files;

import com.example.swiftsim.config.ConfigService;
import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.domain.MessageRepository;
import com.example.swiftsim.gateway.GatewayService;
import com.example.swiftsim.gateway.SubmitOptions;
import com.example.swiftsim.gateway.SubmitResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Payment-application folder transport. Paths are named from the application's point of view:
 * {@code outgoing/mt|mx} is simulator input, {@code incoming/mt|mx} is simulator output.
 * A successfully consumed original is moved to {@code temp/outgoing/mt|mx} (the only success archive).
 * {@code temp/incoming} belongs to the application and is never opened.
 * Inflight claims and failures stay under the simulator state directory ({@code .swiftsim} by default).
 * <p>
 * The gateway, ledger, and scenario engine are unchanged. This class only claims files, calls
 * {@link GatewayService#submit}, and publishes the copies that are already addressed to the
 * application under test. HTTP listeners stay up in every mode.
 * <p>
 * Delivery is not exactly-once. A claimed file is submitted at most once across a restart when the
 * database and the inflight file are both still there. A crash in the moment after the gateway
 * commits and before this class updates the claim is recovered by {@code fileClaimId}, not by
 * reading the outgoing directory again. Publishing a file to {@code incoming} is idempotent per
 * message id; if the application removes that file before the publication row is marked
 * {@code PUBLISHED}, a restart can write it again.
 */
@Slf4j
@Service
public class FileTransportService {
    public static final String CHANNEL = "FOLDER";
    public static final String HTTP = "HTTP";
    public static final String FILE = "FILE";
    public static final String BOTH = "BOTH";
    private static final Set<String> MODES = Set.of(HTTP, FILE, BOTH);
    private static final List<String> OPEN = List.of("CLAIMED", "SUBMITTED");
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;

    private final FileClaimRepository claims;
    private final FilePublicationRepository publications;
    private final MessageRepository messages;
    private final ConfigService participants;
    private final GatewayService gateway;
    private final Path dataDir;
    private final Settings startup;
    private final java.util.concurrent.atomic.AtomicReference<Settings> cfg = new java.util.concurrent.atomic.AtomicReference<>();
    private final StabilityTracker stability = new StabilityTracker();
    private final Object publishLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong skippedUnsafe = new AtomicLong();

    private volatile Instant lastPollAt;
    private volatile String lastError;
    private volatile String lastLoggedBlock = "";
    private volatile List<String> diagnostics = List.of();
    private volatile List<Map<String, Object>> waiting = List.of();

    public FileTransportService(FileClaimRepository claims, FilePublicationRepository publications, MessageRepository messages,
                                ConfigService participants, @Lazy GatewayService gateway,
                                @Value("${sim.data-dir:./data}") String dataDir,
                                @Value("${sim.transport.mode:HTTP}") String mode,
                                @Value("${sim.transport.originate:}") String originate,
                                @Value("${sim.transport.file.root:}") String root,
                                @Value("${sim.transport.file.state-dir:}") String stateDir,
                                @Value("${sim.transport.file.poll-ms:2000}") long pollMs,
                                @Value("${sim.transport.file.stable-ms:1000}") long stableMs,
                                @Value("${sim.transport.file.max-bytes:2000000}") long maxBytes) {
        this.claims = claims;
        this.publications = publications;
        this.messages = messages;
        this.participants = participants;
        this.gateway = gateway;
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        Settings s = new Settings();
        s.mode = mode;
        s.originate = originate == null ? "" : originate;
        s.root = root == null ? "" : root;
        s.stateDir = stateDir == null ? "" : stateDir;
        s.pollMs = pollMs;
        s.stableMs = stableMs;
        s.maxBytes = maxBytes;
        normalize(s);
        this.startup = s;
    }

    @PostConstruct
    void loadSavedSettings() {
        Settings s = startup.copy();
        Path file = settingsFile();
        if (Files.isRegularFile(file, NOFOLLOW) && !Files.isSymbolicLink(file)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
                apply(s, p);
                normalize(s);
                log.info("file transport settings loaded from {} (overrides startup flags for keys present in that file)", file);
            } catch (IOException e) {
                log.warn("cannot read {}: {}", file, e.getMessage());
            }
        }
        cfg.set(s);
        if (s.fileEnabled()) {
            log.info("file transport mode={} originate={} root={}", s.mode, s.effectiveOriginate(), s.root);
        } else {
            log.info("file transport mode=HTTP; payment-application folders will not be opened");
        }
    }

    public void stop() {
        running.set(false);
    }

    public boolean fileEnabled() {
        return cfg.get().fileEnabled();
    }

    /**
     * Where a message addressed to the payment application is published.
     * Replies copy the inbound message. Folder drops use FILE. In BOTH, HTTP submissions stay on HTTP
     * and simulator kickoff uses {@code originate}. Mode FILE publishes every such copy to the folder
     * unless the caller explicitly asked for HTTP.
     */
    public String resolveOutbound(String explicit, String channel, Long inReplyTo) {
        Settings s = cfg.get();
        String ex = norm(explicit);
        if (!s.fileEnabled()) {
            if (FILE.equals(ex)) log.warn("transport mode HTTP ignores requested FILE delivery");
            return HTTP;
        }
        if (HTTP.equals(ex) || FILE.equals(ex)) return ex;
        if (inReplyTo != null) {
            String parent = messages.findById(inReplyTo).map(MessageRecord::getOutboundTransport).orElse(null);
            if (HTTP.equals(parent) || FILE.equals(parent)) return parent;
        }
        if (CHANNEL.equalsIgnoreCase(channel)) return FILE;
        if (FILE.equals(s.mode)) return FILE;
        if (inReplyTo == null && "SCENARIO".equalsIgnoreCase(channel)) return s.effectiveOriginate();
        return HTTP;
    }

    /**
     * What an admin compose send will do with the payment-application folder. Does not publish.
     * An explicit FILE choice is ignored while mode is HTTP. A NAK'd user message is not published;
     * a later reply copies the stored transport only when a scenario actually sends one.
     */
    public Map<String, Object> adminSendPlan(String explicit, boolean nak, String senderBic, String receiverBic) {
        String resolved = resolveOutbound(explicit, "ADMIN", null);
        Settings s = cfg.get();
        boolean receiverIsSut = receiverBic != null && participants.byBic(receiverBic).map(p -> p.isSut()).orElse(false);
        boolean senderIsSut = senderBic != null && participants.byBic(senderBic).map(p -> p.isSut()).orElse(false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transport", resolved);
        m.put("receiverIsSut", receiverIsSut);
        String reason;
        String error = null;
        if (nak) reason = "NAK";
        else if (FILE.equals(norm(explicit)) && !s.fileEnabled()) reason = "MODE_HTTP_IGNORES_FILE";
        else if (!FILE.equals(resolved)) reason = "HTTP";
        else if (!receiverIsSut) reason = "RECEIVER_NOT_SUT";
        else reason = "TO_SUT";
        boolean publish = "TO_SUT".equals(reason);
        if (publish) {
            KenexLayout.Result layout = KenexLayout.resolve(s.root, s.stateDir);
            if (layout.error != null) {
                reason = "LAYOUT";
                publish = false;
                error = layout.error;
            } else {
                Path root = layout.layout.root;
                if (Files.isSymbolicLink(root) || (Files.exists(root) && !Files.isDirectory(root, NOFOLLOW))) {
                    reason = "LAYOUT";
                    publish = false;
                    error = "file root is not a real directory: " + root;
                } else {
                    m.put("incomingMt", layout.layout.incomingMt.toString());
                    m.put("incomingMx", layout.layout.incomingMx.toString());
                }
            }
        }
        m.put("reason", reason);
        m.put("publishSubmittedMessage", publish);
        if ("NAK".equals(reason) && FILE.equals(resolved) && senderIsSut) m.put("publishNakNotice", true);
        if (error != null) m.put("error", error);
        return m;
    }

    /** Publish one already-stored message into {@code incoming/mt} or {@code incoming/mx}, once. */
    public void publishToPaymentApp(MessageRecord rec, String payload) {
        if (!running.get() || rec == null || rec.getId() == null || payload == null || payload.isEmpty()) return;
        if (!FILE.equals(rec.getOutboundTransport()) || !cfg.get().fileEnabled()) return;
        if (participants.byBic(rec.getReceiverBic()).map(p -> !p.isSut()).orElse(true)) return;
        synchronized (publishLock) {
            try {
                KenexLayout layout = requireLayout();
                if (layout == null) return;
                FilePublication existing = publications.findByMessageId(rec.getId()).orElse(null);
                if (existing != null && "PUBLISHED".equals(existing.getStatus())) return;
                FilePublication pub = existing;
                if (pub == null) {
                    pub = new FilePublication();
                    pub.setMessageId(rec.getId());
                    pub.setCreatedAt(Instant.now());
                    pub.setFormat(rec.getFormat());
                    pub.setMsgType(trunc(rec.getMsgType(), 32));
                    pub.setSha256(sha256(payload.getBytes(StandardCharsets.UTF_8)));
                    pub.setStatus("PENDING");
                    publications.saveAndFlush(pub);
                }
                writePublication(layout, pub, payload);
            } catch (Exception e) {
                lastError = "publish message " + rec.getId() + ": " + e.getMessage();
                log.warn("kenex publish failed for message {}: {}", rec.getId(), e.toString());
            }
        }
    }

    /** One poll. Does not open any payment-application directory while file transport is off. */
    public long pollOnce() {
        Settings s = cfg.get();
        if (!running.get()) return s.pollMs;
        if (!s.fileEnabled()) {
            lastPollAt = Instant.now();
            waiting = List.of();
            noteBlock("file transport disabled; payment-application folders are not accessed");
            return s.pollMs;
        }
        KenexLayout.Result resolved = KenexLayout.resolve(s.root, s.stateDir);
        if (resolved.error != null) {
            noteBlock(resolved.error);
            lastPollAt = Instant.now();
            return s.pollMs;
        }
        List<String> problems = prepare(resolved.layout);
        diagnostics = List.copyOf(problems);
        if (!problems.isEmpty()) noteBlock(String.join("; ", problems));
        try {
            if (running.get()) adoptOrphans(resolved.layout);
            if (running.get()) recover(resolved.layout);
            List<Map<String, Object>> waitingNow = new ArrayList<>();
            if (running.get()) scan(resolved.layout, s, "MT", waitingNow);
            if (running.get()) scan(resolved.layout, s, "MX", waitingNow);
            waiting = List.copyOf(waitingNow);
            if (running.get()) retryPublications(resolved.layout);
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("file transport poll failed: {}", e.toString());
        }
        lastPollAt = Instant.now();
        return s.pollMs;
    }

    public Map<String, Object> status() {
        Settings s = cfg.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", s.mode);
        m.put("fileEnabled", s.fileEnabled());
        m.put("originate", s.effectiveOriginate());
        m.put("originateConfigured", s.originate);
        m.put("perspective", "PAYMENT_APPLICATION");
        m.put("root", s.root);
        m.put("stateDir", s.stateDir);
        m.put("pollMs", s.pollMs);
        m.put("stableMs", s.stableMs);
        m.put("maxBytes", s.maxBytes);
        m.put("channel", CHANNEL);
        m.put("lastPollAt", lastPollAt == null ? null : lastPollAt.toString());
        m.put("lastError", lastError);
        m.put("diagnostics", diagnostics);
        m.put("waiting", waiting);
        m.put("skippedUnsafe", skippedUnsafe.get());
        m.put("counts", counts());
        m.put("routing", routingText(s));
        m.put("guarantees", "Claimed files are not read again on later polls. A restart submits a claim only when no gateway message with that fileClaimId exists (at-most-once per claim while the database and inflight file survive). Not exactly-once: a publication marked PENDING can be written again if the application already removed the incoming file. Successful originals are moved to temp/outgoing/{mt,mx}, not deleted. Failures stay in the simulator state directory. temp/incoming is never opened. The simulator does not clean incoming.");
        KenexLayout.Result resolved = s.root == null || s.root.isBlank() ? null : KenexLayout.resolve(s.root, s.stateDir);
        if (resolved != null && resolved.error != null) m.put("layoutError", resolved.error);
        KenexLayout layout = resolved == null ? null : resolved.layout;
        m.put("paths", paths(s, layout));
        if (s.fileEnabled()) {
            m.put("recentErrors", recentClaims(List.of("ERROR", "ECHO")));
            m.put("recentPublications", recentPublications());
        } else {
            m.put("recentErrors", List.of());
            m.put("recentPublications", List.of());
            m.put("disabledNote", "HTTP mode does not create, list, or read the payment-application folders.");
        }
        return m;
    }

    public Map<String, Object> update(Map<String, ?> body) {
        Settings next = cfg.get().copy();
        if (body.containsKey("mode")) next.mode = required(str(body.get("mode")), MODES, "mode");
        if (body.containsKey("originate")) {
            String o = str(body.get("originate"));
            if (o == null || o.isBlank() || "AUTO".equalsIgnoreCase(o)) next.originate = "";
            else next.originate = required(o, Set.of(HTTP, FILE), "originate");
        }
        if (body.containsKey("root")) next.root = str(body.get("root")) == null ? "" : str(body.get("root")).trim();
        if (body.containsKey("stateDir")) next.stateDir = str(body.get("stateDir")) == null ? "" : str(body.get("stateDir")).trim();
        if (body.containsKey("pollMs")) next.pollMs = longVal(body.get("pollMs"), next.pollMs);
        if (body.containsKey("stableMs")) next.stableMs = longVal(body.get("stableMs"), next.stableMs);
        if (body.containsKey("maxBytes")) next.maxBytes = longVal(body.get("maxBytes"), next.maxBytes);
        normalize(next);
        if (next.fileEnabled()) {
            KenexLayout.Result resolved = KenexLayout.resolve(next.root, next.stateDir);
            if (resolved.error != null) throw new IllegalArgumentException(resolved.error);
        }
        saveSettings(next);
        cfg.set(next);
        log.info("file transport updated mode={} originate={} root={}", next.mode, next.effectiveOriginate(), next.root.isBlank() ? "(none)" : next.root);
        if (!next.fileEnabled()) log.info("file transport disabled; payment-application folders will not be opened");
        return status();
    }

    // ------------------------------------------------------------------------------------------------ poll

    private void scan(KenexLayout layout, Settings s, String kind, List<Map<String, Object>> waitingNow) {
        Path dir = layout.outgoing(kind);
        if (!usableDir(dir)) {
            waitingNow.add(wait(kind, "", "outgoing directory is not a usable real directory"));
            return;
        }
        List<Path> files;
        try {
            files = listTop(dir);
        } catch (IOException e) {
            lastError = dir + ": " + e.getMessage();
            log.warn("cannot list {}: {}", dir, e.getMessage());
            return;
        }
        long now = System.currentTimeMillis();
        for (Path file : files) {
            if (!running.get()) return;
            String name = file.getFileName().toString();
            InboundPolicy.Skip skip = InboundPolicy.classify(dir, file, kind, Long.MAX_VALUE);
            if (skip != InboundPolicy.Skip.OK) {
                if (skip == InboundPolicy.Skip.SYMLINK || skip == InboundPolicy.Skip.ESCAPE || skip == InboundPolicy.Skip.UNREADABLE) {
                    skippedUnsafe.incrementAndGet();
                    log.warn("ignoring unsafe {} entry {}: {}", kind, name, skip);
                }
                if (skip != InboundPolicy.Skip.NOT_FILE) waitingNow.add(wait(kind, name, skip.name()));
                continue;
            }
            long size;
            long mtime;
            try {
                size = Files.size(file);
                FileTime mt = Files.getLastModifiedTime(file, NOFOLLOW);
                mtime = mt.toMillis();
            } catch (IOException e) {
                waitingNow.add(wait(kind, name, "unreadable: " + e.getMessage()));
                continue;
            }
            String key = file.toAbsolutePath().normalize().toString();
            if (!stability.ready(key, size, mtime, s.stableMs, now)) {
                waitingNow.add(wait(kind, name, "size or mtime still changing (stable-ms=" + s.stableMs + ")"));
                continue;
            }
            try {
                if (size > s.maxBytes) quarantine(file, kind, layout, size, null, "ERROR", "file exceeds sim.transport.file.max-bytes (" + s.maxBytes + ")");
                else ingest(file, kind, layout);
                stability.forget(key);
            } catch (Exception e) {
                lastError = name + ": " + e.getMessage();
                log.warn("failed to ingest {}: {}", name, e.toString());
            }
        }
    }

    private void ingest(Path source, String kind, KenexLayout layout) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        String sha = sha256(bytes);
        String sourcePath = source.toAbsolutePath().normalize().toString();
        if (publications.existsBySha256(sha)) {
            FileClaim echo = moveIn(source, kind, layout, sha, bytes.length);
            echo.setStatus("ECHO");
            echo.setErrorReason("echo of simulator output suppressed; these bytes match a file already published to incoming and were not submitted. Kept under the simulator error directory, not temp/outgoing.");
            claims.saveAndFlush(echo);
            archive(echo, layout);
            log.warn("suppressed echo of simulator output: {}", echo.getOriginalName());
            return;
        }
        Optional<FileClaim> open = claims.findFirstBySourcePathAndSha256AndStatusIn(sourcePath, sha, OPEN);
        if (open.isPresent()) {
            Path inflight = open.get().getInflightPath() == null ? null : Path.of(open.get().getInflightPath());
            if (inflight != null && Files.isRegularFile(inflight, NOFOLLOW)) {
                try {
                    Files.deleteIfExists(source);
                } catch (IOException e) {
                    log.warn("claimed file still in outgoing {}: {}", sourcePath, e.getMessage());
                }
            }
            return;
        }
        String text;
        try {
            text = decode(bytes);
        } catch (CharacterCodingException e) {
            FileClaim bad = moveIn(source, kind, layout, sha, bytes.length);
            bad.setStatus("ERROR");
            bad.setErrorReason("encoding is not valid UTF-8 or UTF-16: " + e.getMessage());
            claims.saveAndFlush(bad);
            archive(bad, layout);
            return;
        }
        FileClaim claim = moveIn(source, kind, layout, sha, bytes.length);
        submitClaim(claim, text, layout);
    }

    private void submitClaim(FileClaim claim, String text, KenexLayout layout) {
        try {
            SubmitResult r = gateway.submit(text, SubmitOptions.builder().channel(CHANNEL).outboundTransport(FILE).fileClaimId(claim.getId()).build());
            claim.setMessageId(r.getId());
            boolean nak = r.getStatus() == null || !"ACK".equals(r.getStatus());
            claim.setStatus(nak ? "ERROR" : "SUBMITTED");
            if (nak) claim.setErrorReason(trunc(reason(r), 4000));
            claim.setUpdatedAt(Instant.now());
            claims.saveAndFlush(claim);
            log.info("folder input {} -> message {} {} {}", claim.getOriginalName(), r.getId(), r.getMsgType(), r.getStatus());
            if (nak) log.warn("folder input isolated {} message {}: {}", claim.getOriginalName(), r.getId(), claim.getErrorReason());
        } catch (Exception e) {
            claim.setStatus("ERROR");
            claim.setErrorReason(trunc("submit failed: " + e.getMessage(), 4000));
            claim.setUpdatedAt(Instant.now());
            claims.saveAndFlush(claim);
            log.warn("folder submit failed for {}: {}", claim.getOriginalName(), e.toString());
        }
        archive(claim, layout);
    }

    private void quarantine(Path source, String kind, KenexLayout layout, long size, String sha, String status, String reason) throws IOException {
        FileClaim c = moveIn(source, kind, layout, sha, size);
        c.setStatus(status);
        c.setErrorReason(reason);
        claims.saveAndFlush(c);
        archive(c, layout);
        log.warn("folder input isolated {}: {}", c.getOriginalName(), reason);
    }

    private FileClaim moveIn(Path source, String kind, KenexLayout layout, String sha, long size) throws IOException {
        String original = source.getFileName().toString();
        Path inflightDir = layout.inflight(kind);
        Files.createDirectories(inflightDir);
        String suffix = original.length() > 120 ? original.substring(0, 120) : original;
        Path inflight = inflightDir.resolve(UUID.randomUUID().toString().replace("-", "") + "_" + suffix);
        boolean atomic = true;
        try {
            Files.move(source, inflight, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            atomic = false;
            Path tmp = inflight.resolveSibling(inflight.getFileName().toString() + ".partial");
            Files.copy(source, tmp);
            try (var ch = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.READ)) {
                ch.force(true);
            }
            Files.move(tmp, inflight, StandardCopyOption.ATOMIC_MOVE);
        }
        FileClaim c = new FileClaim();
        c.setKind(kind);
        c.setOriginalName(original);
        c.setSourcePath(source.toAbsolutePath().normalize().toString());
        c.setInflightPath(inflight.toAbsolutePath().normalize().toString());
        c.setSha256(sha);
        c.setSizeBytes(size);
        c.setStatus("CLAIMED");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(c.getCreatedAt());
        claims.saveAndFlush(c);
        if (!atomic) {
            try {
                if (Files.isRegularFile(source, NOFOLLOW) && sha != null && sha.equals(sha256(Files.readAllBytes(source)))) Files.deleteIfExists(source);
            } catch (IOException e) {
                log.warn("could not remove claimed original {}: {}", source, e.getMessage());
            }
        }
        return c;
    }

    private void recover(KenexLayout layout) {
        for (FileClaim c : claims.findByStatusIn(OPEN)) {
            if (!running.get()) return;
            if ("CLAIMED".equals(c.getStatus())) {
                Optional<MessageRecord> existing = messages.findByFileClaimId(c.getId());
                if (existing.isPresent()) {
                    MessageRecord msg = existing.get();
                    c.setMessageId(msg.getId());
                    boolean nak = "NAK".equals(msg.getStatus());
                    c.setStatus(nak ? "ERROR" : "SUBMITTED");
                    if (nak && c.getErrorReason() == null) c.setErrorReason(msg.getStatusReason());
                    c.setUpdatedAt(Instant.now());
                    claims.saveAndFlush(c);
                    log.info("folder claim {} already submitted as message {}; not submitting again", c.getId(), msg.getId());
                } else {
                    byte[] bytes = readClaimBytes(c, layout);
                    if (bytes == null) {
                        c.setStatus("ERROR");
                        c.setErrorReason("claimed file is missing after restart; it was not submitted");
                        c.setUpdatedAt(Instant.now());
                        claims.saveAndFlush(c);
                        log.warn("folder claim {} lost its inflight file before submit", c.getId());
                        continue;
                    }
                    try {
                        submitClaim(c, decode(bytes), layout);
                    } catch (CharacterCodingException e) {
                        c.setStatus("ERROR");
                        c.setErrorReason("encoding is not valid UTF-8 or UTF-16: " + e.getMessage());
                        c.setUpdatedAt(Instant.now());
                        claims.saveAndFlush(c);
                        archive(c, layout);
                    }
                    continue;
                }
            }
            archive(c, layout);
        }
    }

    private void adoptOrphans(KenexLayout layout) throws IOException {
        for (String kind : List.of("MT", "MX")) {
            Path dir = layout.inflight(kind);
            if (!usableDir(dir)) continue;
            for (Path file : listTop(dir)) {
                if (!running.get()) return;
                String name = file.getFileName().toString();
                if (InboundPolicy.temporaryName(name) || Files.isSymbolicLink(file) || !Files.isRegularFile(file, NOFOLLOW)) continue;
                String path = file.toAbsolutePath().normalize().toString();
                if (claims.findByInflightPath(path).isPresent()) continue;
                byte[] bytes = Files.readAllBytes(file);
                String original = originalFromInflight(name);
                FileClaim c = new FileClaim();
                c.setKind(kind);
                c.setOriginalName(original);
                c.setSourcePath(layout.outgoing(kind).resolve(original).toAbsolutePath().normalize().toString());
                c.setInflightPath(path);
                c.setSha256(sha256(bytes));
                c.setSizeBytes((long) bytes.length);
                c.setStatus("CLAIMED");
                c.setCreatedAt(Instant.now());
                c.setUpdatedAt(c.getCreatedAt());
                claims.saveAndFlush(c);
                log.warn("adopted inflight file with no claim row: {}", path);
            }
        }
    }

    private byte[] readClaimBytes(FileClaim c, KenexLayout layout) {
        try {
            if (c.getInflightPath() != null) {
                Path p = Path.of(c.getInflightPath());
                if (Files.isRegularFile(p, NOFOLLOW) && !Files.isSymbolicLink(p)) return Files.readAllBytes(p);
            }
            if (c.getSourcePath() != null && c.getSha256() != null) {
                Path p = Path.of(c.getSourcePath()).toAbsolutePath().normalize();
                if ((p.startsWith(layout.outgoingMt) || p.startsWith(layout.outgoingMx))
                        && Files.isRegularFile(p, NOFOLLOW) && !Files.isSymbolicLink(p)) {
                    byte[] bytes = Files.readAllBytes(p);
                    if (c.getSha256().equals(sha256(bytes))) return bytes;
                }
            }
        } catch (IOException e) {
            log.warn("cannot read claim {}: {}", c.getId(), e.getMessage());
        }
        return null;
    }

    private void archive(FileClaim c, KenexLayout layout) {
        boolean success = "SUBMITTED".equals(c.getStatus()) || "PROCESSED".equals(c.getStatus());
        Path src = c.getInflightPath() == null ? null : Path.of(c.getInflightPath());
        try {
            if (src == null || !Files.isRegularFile(src, NOFOLLOW)) {
                if ("SUBMITTED".equals(c.getStatus())) {
                    c.setStatus("PROCESSED");
                    c.setUpdatedAt(Instant.now());
                    claims.save(c);
                }
                return;
            }
            // Success moves into temp/outgoing. Failures and suppressed echoes stay in the simulator state dir.
            Path destDir = success ? layout.archived(c.getKind()) : layout.error(c.getKind());
            if (destDir.startsWith(layout.appTempIncoming)) throw new IOException("refusing to archive under temp/incoming");
            if (Files.isSymbolicLink(destDir)) throw new IOException("refusing symlink archive dir " + destDir);
            Files.createDirectories(destDir);
            Path dest = uniqueIn(destDir, c.getOriginalName());
            try {
                Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(src, dest);
            }
            c.setArchivePath(dest.toAbsolutePath().normalize().toString());
            if ("SUBMITTED".equals(c.getStatus())) c.setStatus("PROCESSED");
            c.setUpdatedAt(Instant.now());
            claims.saveAndFlush(c);
            if ("ERROR".equals(c.getStatus()) || "ECHO".equals(c.getStatus())) writeReason(dest, c);
        } catch (IOException e) {
            lastError = "archive " + c.getOriginalName() + ": " + e.getMessage();
            log.warn("archive {} failed: {}", c.getOriginalName(), e.getMessage());
        }
    }

    private void writeReason(Path archived, FileClaim c) {
        String name = archived.getFileName().toString() + ".reason.txt";
        String text = "file=" + c.getOriginalName() + "\nstatus=" + c.getStatus() + "\nmessageId=" + c.getMessageId()
                + "\nsha256=" + c.getSha256() + "\nsource=" + c.getSourcePath() + "\n" + (c.getErrorReason() == null ? "" : c.getErrorReason()) + "\n";
        try {
            AtomicFiles.publishNew(archived.getParent(), name, text.getBytes(StandardCharsets.UTF_8));
        } catch (FileAlreadyExistsException ignored) {
            // keep the reason written by the first attempt
        } catch (IOException e) {
            log.warn("reason file for {} failed: {}", c.getOriginalName(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ publish

    private void retryPublications(KenexLayout layout) {
        synchronized (publishLock) {
            for (FilePublication pub : publications.findByStatusIn(List.of("PENDING", "ERROR"))) {
                if (!running.get()) return;
                if ("message gone".equals(pub.getErrorReason())) continue;
                MessageRecord msg = messages.findById(pub.getMessageId()).orElse(null);
                if (msg == null) {
                    pub.setStatus("ERROR");
                    pub.setErrorReason("message gone");
                    pub.setUpdatedAt(Instant.now());
                    publications.save(pub);
                    continue;
                }
                String payload = msg.getDeliveredRaw() != null ? msg.getDeliveredRaw() : msg.getRaw();
                if (payload == null) continue;
                writePublication(layout, pub, payload);
            }
        }
    }

    private void writePublication(KenexLayout layout, FilePublication pub, String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);
        pub.setSha256(sha);
        boolean xml = OutboundNames.xmlPayload(pub.getFormat(), payload);
        Path dir = layout.incoming(xml);
        try {
            if (Files.isSymbolicLink(dir)) throw new IOException("refusing symlink incoming directory " + dir);
            if (!Files.isDirectory(dir, NOFOLLOW)) Files.createDirectories(dir);
            if (pub.getFileName() != null) {
                Path target = dir.resolve(pub.getFileName());
                if (Files.isRegularFile(target, NOFOLLOW) && !Files.isSymbolicLink(target) && sha.equals(sha256(Files.readAllBytes(target)))) {
                    markPublished(pub, target);
                    return;
                }
                if (!Files.exists(target) && !Files.isSymbolicLink(target)) {
                    try {
                        Path written = AtomicFiles.publishNew(dir, pub.getFileName(), bytes);
                        markPublished(pub, written);
                        log.info("published message {} to {}", pub.getMessageId(), written);
                        return;
                    } catch (FileAlreadyExistsException e) {
                        pub.setFileName(null);
                    }
                } else {
                    pub.setFileName(null);
                }
            }
            FileAlreadyExistsException collision = null;
            for (int i = 0; i < 8 && pub.getFileName() == null; i++) {
                String name = OutboundNames.nextName(pub.getFormat(), pub.getMsgType(), payload);
                pub.setFileName(name);
                pub.setStatus("PENDING");
                pub.setUpdatedAt(Instant.now());
                publications.saveAndFlush(pub);
                try {
                    Path written = AtomicFiles.publishNew(dir, name, bytes);
                    markPublished(pub, written);
                    log.info("published message {} to {}", pub.getMessageId(), written);
                    return;
                } catch (FileAlreadyExistsException e) {
                    collision = e;
                    pub.setFileName(null);
                }
            }
            throw collision == null ? new IOException("could not choose a free incoming file name") : collision;
        } catch (Exception e) {
            String reason = trunc(e.getMessage(), 1000);
            boolean repeat = reason != null && reason.equals(pub.getErrorReason());
            pub.setStatus("ERROR");
            pub.setErrorReason(reason);
            pub.setUpdatedAt(Instant.now());
            publications.save(pub);
            lastError = "publish " + pub.getMessageId() + ": " + reason;
            if (!repeat) log.warn("kenex publish {} failed: {}", pub.getMessageId(), reason);
        }
    }

    private void markPublished(FilePublication pub, Path target) {
        pub.setStatus("PUBLISHED");
        pub.setAbsolutePath(target.toAbsolutePath().normalize().toString());
        pub.setErrorReason(null);
        pub.setUpdatedAt(Instant.now());
        publications.save(pub);
    }

    private KenexLayout requireLayout() {
        Settings s = cfg.get();
        KenexLayout.Result resolved = KenexLayout.resolve(s.root, s.stateDir);
        if (resolved.error != null) {
            lastError = resolved.error;
            return null;
        }
        List<String> problems = prepare(resolved.layout);
        if (!problems.isEmpty()) {
            diagnostics = List.copyOf(problems);
            lastError = problems.get(0);
        }
        return resolved.layout;
    }

    // ------------------------------------------------------------------------------------------------ paths

    private List<String> prepare(KenexLayout layout) {
        List<String> problems = new ArrayList<>();
        try {
            if (Files.isSymbolicLink(layout.root)) {
                problems.add("refusing to follow symlink root " + layout.root);
                return problems;
            }
        } catch (Exception e) {
            problems.add("root: " + e.getMessage());
            return problems;
        }
        Path[] needed = {layout.root, layout.outgoingMt, layout.outgoingMx, layout.incomingMt, layout.incomingMx,
                layout.archivedMt, layout.archivedMx,
                layout.stateRoot, layout.inflightMt, layout.inflightMx, layout.errorMt, layout.errorMx};
        for (Path p : needed) {
            try {
                if (Files.isSymbolicLink(p)) {
                    problems.add("refusing symlink " + p);
                    continue;
                }
                if (!Files.isDirectory(p, NOFOLLOW)) Files.createDirectories(p);
                if (Files.isSymbolicLink(p) || !Files.isDirectory(p, NOFOLLOW)) problems.add("not a real directory: " + p);
                else if (!Files.isReadable(p) || !Files.isWritable(p)) problems.add("need read and write permission: " + p);
            } catch (IOException e) {
                problems.add(p + ": " + e.getMessage());
            }
        }
        return problems;
    }

    private boolean usableDir(Path dir) {
        try {
            return !Files.isSymbolicLink(dir) && Files.isDirectory(dir, NOFOLLOW) && Files.isReadable(dir) && Files.isWritable(dir);
        } catch (Exception e) {
            return false;
        }
    }

    private List<Path> listTop(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir)) throw new IOException("symlink directory refused: " + dir);
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) out.add(p);
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    private static Path uniqueIn(Path dir, String original) throws IOException {
        String name = original == null || original.isBlank() ? "message.txt" : original.replace('\\', '_').replace('/', '_');
        Path dest = dir.resolve(name);
        int n = 2;
        while (Files.exists(dest) || Files.isSymbolicLink(dest)) {
            int dot = name.lastIndexOf('.');
            String alt = dot > 0 ? name.substring(0, dot) + "-" + n + name.substring(dot) : name + "-" + n;
            dest = dir.resolve(alt);
            if (++n > 1000) throw new IOException("no free archive name for " + original);
        }
        if (!dest.getParent().equals(dir)) throw new IOException("archive name escapes " + dir);
        return dest;
    }

    private List<Map<String, Object>> paths(Settings s, KenexLayout layout) {
        List<Map<String, Object>> list = new ArrayList<>();
        String rootText = layout == null ? (s.root == null || s.root.isBlank() ? "" : s.root) : layout.root.toString();
        boolean probe = s.fileEnabled() && layout != null;
        list.add(pathRow("SIMULATOR_INPUT", "支付应用发出的 MT", "outgoing/mt", layout == null ? join(rootText, "outgoing/mt") : layout.outgoingMt.toString(), probe));
        list.add(pathRow("SIMULATOR_INPUT", "支付应用发出的 MX", "outgoing/mx", layout == null ? join(rootText, "outgoing/mx") : layout.outgoingMx.toString(), probe));
        list.add(pathRow("SIMULATOR_OUTPUT", "发给支付应用的 MT", "incoming/mt", layout == null ? join(rootText, "incoming/mt") : layout.incomingMt.toString(), probe));
        list.add(pathRow("SIMULATOR_OUTPUT", "发给支付应用的 MX", "incoming/mx", layout == null ? join(rootText, "incoming/mx") : layout.incomingMx.toString(), probe));
        Map<String, Object> temp = new LinkedHashMap<>();
        temp.put("role", "APP_MANAGED");
        temp.put("appView", "支付应用已读暂存（模拟器不访问）");
        temp.put("relative", "temp/incoming");
        temp.put("absolute", layout == null ? join(rootText, "temp/incoming") : layout.appTempIncoming.toString());
        temp.put("simulatorAccess", "NONE");
        temp.put("note", "支付应用把已消费的 incoming 文件移到这里。模拟器不监听、不读取、不删除、不创建该目录。");
        list.add(temp);
        list.add(pathRow("SIMULATOR_ARCHIVE", "模拟器收报清理归档 MT（移动，不删除）", "temp/outgoing/mt",
                layout == null ? join(rootText, "temp/outgoing/mt") : layout.archivedMt.toString(), probe));
        list.add(pathRow("SIMULATOR_ARCHIVE", "模拟器收报清理归档 MX（移动，不删除）", "temp/outgoing/mx",
                layout == null ? join(rootText, "temp/outgoing/mx") : layout.archivedMx.toString(), probe));
        if (layout != null) {
            list.add(pathRow("SIMULATOR_STATE", "模拟器领取中的文件（未完成，不算成功）", ".swiftsim/inflight", layout.inflightMt.getParent().toString(), probe));
            list.add(pathRow("SIMULATOR_STATE", "模拟器失败隔离（含回声抑制，不是成功归档）", ".swiftsim/error", layout.errorMt.getParent().toString(), probe));
        }
        return list;
    }

    private Map<String, Object> pathRow(String role, String appView, String relative, String absolute, boolean probe) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("appView", appView);
        m.put("relative", relative);
        m.put("absolute", absolute);
        if (!probe || absolute == null || absolute.isBlank()) {
            m.put("probed", false);
            return m;
        }
        probe(Path.of(absolute), m);
        return m;
    }

    private static void probe(Path p, Map<String, Object> m) {
        try {
            boolean symlink = Files.isSymbolicLink(p);
            boolean dir = !symlink && Files.isDirectory(p, NOFOLLOW);
            m.put("probed", true);
            m.put("exists", Files.exists(p, NOFOLLOW));
            m.put("symlink", symlink);
            m.put("directory", dir);
            m.put("readable", dir && Files.isReadable(p));
            m.put("writable", dir && Files.isWritable(p));
            if (symlink) m.put("error", "symlink refused");
            else if (!Files.exists(p, NOFOLLOW)) m.put("error", "missing");
            else if (!dir) m.put("error", "not a directory");
            else if (!Files.isReadable(p) || !Files.isWritable(p)) m.put("error", "need read and write permission");
        } catch (Exception e) {
            m.put("probed", true);
            m.put("error", e.getMessage());
        }
    }

    private Map<String, Object> counts() {
        Map<String, Object> c = new LinkedHashMap<>();
        try {
            c.put("claims", claims.count());
            c.put("processed", claims.findByStatusIn(List.of("PROCESSED")).size());
            c.put("isolated", claims.findByStatusIn(List.of("ERROR")).size());
            c.put("echoSuppressed", claims.findByStatusIn(List.of("ECHO")).size());
            c.put("open", claims.findByStatusIn(OPEN).size());
            c.put("published", publications.findByStatusIn(List.of("PUBLISHED")).size());
            c.put("publishPending", publications.findByStatusIn(List.of("PENDING", "ERROR")).size());
        } catch (Exception e) {
            c.put("error", e.getMessage());
        }
        return c;
    }

    private List<Map<String, Object>> recentClaims(Collection<String> statuses) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FileClaim c : claims.findTop20ByStatusInOrderByIdDesc(statuses)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("file", c.getOriginalName());
            m.put("kind", c.getKind());
            m.put("status", c.getStatus());
            m.put("messageId", c.getMessageId());
            m.put("reason", c.getErrorReason());
            m.put("archive", c.getArchivePath());
            m.put("at", c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> recentPublications() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FilePublication p : publications.findTop10ByOrderByIdDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("messageId", p.getMessageId());
            m.put("status", p.getStatus());
            m.put("file", p.getFileName());
            m.put("path", p.getAbsolutePath());
            m.put("msgType", p.getMsgType());
            m.put("reason", p.getErrorReason());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ settings

    private Path settingsFile() {
        return dataDir.resolve("file-transport.properties");
    }

    private void saveSettings(Settings s) {
        Properties p = new Properties();
        p.setProperty("mode", s.mode);
        p.setProperty("originate", s.originate == null ? "" : s.originate);
        p.setProperty("root", s.root == null ? "" : s.root);
        p.setProperty("stateDir", s.stateDir == null ? "" : s.stateDir);
        p.setProperty("pollMs", Long.toString(s.pollMs));
        p.setProperty("stableMs", Long.toString(s.stableMs));
        p.setProperty("maxBytes", Long.toString(s.maxBytes));
        try {
            Files.createDirectories(dataDir);
            Path file = settingsFile();
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "swift-sim file transport. Paths are from the payment application's point of view.");
            }
            try {
                Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // non-posix volume
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot save file transport settings: " + e.getMessage());
        }
    }

    private static void apply(Settings s, Properties p) {
        if (p.containsKey("mode")) s.mode = p.getProperty("mode");
        if (p.containsKey("originate")) s.originate = p.getProperty("originate");
        if (p.containsKey("root")) s.root = p.getProperty("root");
        if (p.containsKey("stateDir")) s.stateDir = p.getProperty("stateDir");
        if (p.containsKey("pollMs")) s.pollMs = parseLong(p.getProperty("pollMs"), s.pollMs);
        if (p.containsKey("stableMs")) s.stableMs = parseLong(p.getProperty("stableMs"), s.stableMs);
        if (p.containsKey("maxBytes")) s.maxBytes = parseLong(p.getProperty("maxBytes"), s.maxBytes);
    }

    private static void normalize(Settings s) {
        s.mode = norm(s.mode);
        if (!MODES.contains(s.mode)) s.mode = HTTP;
        s.originate = s.originate == null ? "" : s.originate.trim().toUpperCase();
        if (!s.originate.isBlank() && !HTTP.equals(s.originate) && !FILE.equals(s.originate)) s.originate = "";
        if (s.root == null) s.root = "";
        if (s.stateDir == null) s.stateDir = "";
        s.root = s.root.trim();
        s.stateDir = s.stateDir.trim();
        if (s.pollMs < 200) s.pollMs = 200;
        if (s.stableMs < 0) s.stableMs = 0;
        if (s.maxBytes < 1) s.maxBytes = 2_000_000;
    }

    private void noteBlock(String msg) {
        diagnostics = List.of(msg);
        if (!msg.equals(lastLoggedBlock)) {
            lastLoggedBlock = msg;
            if (msg.startsWith("file transport disabled")) log.info(msg);
            else log.warn("file transport: {}", msg);
        }
    }

    private static String routingText(Settings s) {
        return "Labels are the payment application's. outgoing/* is simulator input; incoming/* is simulator output and is not cleaned by the simulator. "
                + "Successfully consumed originals are moved to temp/outgoing/{mt,mx}. temp/incoming is never opened. "
                + "Inflight and failures stay in the simulator state directory, not in temp/outgoing. "
                + "Mode " + s.mode + ", simulator-originated messages use " + s.effectiveOriginate() + ". "
                + "In BOTH, a folder drop's ACK, delivery notice, and scenario reply are written to incoming, and an HTTP submission's reply is not. "
                + "Legacy data/inbox stays the simulator drop folder (channel FILE) and is not the kenex tree. "
                + "data/outbox remains the simulator's own archive.";
    }

    private static Map<String, Object> wait(String kind, String name, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("folder", kind);
        m.put("name", name);
        m.put("reason", reason);
        return m;
    }

    private static String reason(SubmitResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("status=").append(r.getStatus());
        if (r.getReason() != null) sb.append("\n").append(r.getReason());
        if (r.getErrors() != null && !r.getErrors().isEmpty()) sb.append("\n").append(String.join("\n", r.getErrors()));
        return sb.toString();
    }

    private static String originalFromInflight(String fileName) {
        int i = fileName.indexOf('_');
        if (i == 32 && i + 1 < fileName.length()) return fileName.substring(i + 1);
        return fileName;
    }

    static String decode(byte[] bytes) throws CharacterCodingException {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 2, bytes.length - 2)).toString();
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 2, bytes.length - 2)).toString();
        }
        int off = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) off = 3;
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String required(String raw, Set<String> allowed, String key) {
        String v = norm(raw);
        if (!allowed.contains(v)) throw new IllegalArgumentException(key + " must be " + allowed);
        return v;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long longVal(Object o, long fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.longValue();
        return parseLong(String.valueOf(o), fallback);
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String trunc(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }

    private static String join(String root, String rel) {
        if (root == null || root.isBlank()) return rel;
        return Path.of(root).resolve(rel).toString();
    }

    static final class Settings {
        String mode = HTTP;
        String originate = "";
        String root = "";
        String stateDir = "";
        long pollMs = 2000;
        long stableMs = 1000;
        long maxBytes = 2_000_000;

        boolean fileEnabled() {
            return FILE.equals(mode) || BOTH.equals(mode);
        }

        String effectiveOriginate() {
            if (originate != null && !originate.isBlank()) return originate;
            return FILE.equals(mode) ? FILE : HTTP;
        }

        Settings copy() {
            Settings s = new Settings();
            s.mode = mode;
            s.originate = originate;
            s.root = root;
            s.stateDir = stateDir;
            s.pollMs = pollMs;
            s.stableMs = stableMs;
            s.maxBytes = maxBytes;
            return s;
        }
    }
}
