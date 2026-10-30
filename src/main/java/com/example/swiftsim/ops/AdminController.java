package com.example.swiftsim.ops;

import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.domain.MessageRepository;
import com.example.swiftsim.gateway.GatewayService;
import com.example.swiftsim.gateway.SubmitOptions;
import com.example.swiftsim.gateway.SubmitResult;
import com.example.swiftsim.review.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/** Admin console backend (port 8081 only, login required - see {@link PortGuardFilter}). Existing /api/** endpoints are also reachable on that port. */
@RestController
@RequestMapping("/admin/api")
@RequiredArgsConstructor
public class AdminController {
    private final SecretsService secrets;
    private final AdminSessions sessions;
    private final AuditService audit;
    private final OpsService ops;
    private final OpsProps props;
    private final PurgeService purge;
    private final GatewayService gateway;
    private final ReviewService review;
    private final MessageRepository messages;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    // ------------------------------------------------------------------------------------------------ auth

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest req, HttpServletResponse res) throws InterruptedException {
        String ip = req.getRemoteAddr();
        if (sessions.locked(ip)) return ResponseEntity.status(429).body(Map.of("error", "too many failed logins - wait 5 minutes"));
        if (!secrets.checkAdmin(body.get("username"), body.get("password"))) {
            sessions.failed(ip);
            Thread.sleep(700);
            audit.record("admin:" + body.get("username"), "admin", "POST", "/admin/api/login", "FAILED", 401);
            return ResponseEntity.status(401).body(Map.of("error", "invalid username or password", "credentialsFile", secrets.adminFile().toString()));
        }
        sessions.succeeded(ip);
        String token = sessions.create(body.get("username"));
        res.addHeader(HttpHeaders.SET_COOKIE, AdminSessions.cookieHeader(token, 12 * 3600));
        audit.record("admin:" + body.get("username"), "admin", "POST", "/admin/api/login", "OK", 200);
        return ResponseEntity.ok(Map.of("user", body.get("username")));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
        sessions.destroy(req);
        res.addHeader(HttpHeaders.SET_COOKIE, AdminSessions.cookieHeader("", 0));
        return Map.of("loggedOut", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", req.getAttribute(PortGuardFilter.ADMIN_USER_ATTR));
        m.put("ports", Map.of("gateway", props.getMainPort(), "admin", props.getAdminPort(), "ai", props.getAiPort()));
        m.put("credentialsFile", secrets.adminFile().toString());
        m.put("aiKeysFile", secrets.keysFile().toString());
        m.put("aiKeys", secrets.keySummaries());
        m.put("protectedRefs", props.protectedRefs());
        m.put("auditLog", audit.file().toAbsolutePath().normalize().toString());
        return m;
    }

    // ------------------------------------------------------------------------------------------------ read

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return ops.dashboard();
    }

    @GetMapping("/messages")
    public Map<String, Object> messages(@RequestParam Map<String, String> p) {
        int limit = Integer.parseInt(p.getOrDefault("limit", "100"));
        List<MessageRecord> l = ops.search(new OpsService.MessageQuery(p.get("bank"), p.get("from"), p.get("to"), p.get("direction"), p.get("type"), p.get("format"),
                p.get("ref"), p.get("status"), p.get("business"), lng(p.get("sinceId")), lng(p.get("beforeId")),
                OpsService.parseDate(p.get("dateFrom"), false), OpsService.parseDate(p.get("dateTo"), true), "true".equals(p.get("includeSys")), limit));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", l.stream().map(ops::summary).toList());
        m.put("nextBeforeId", l.size() >= limit && !l.isEmpty() ? l.get(l.size() - 1).getId() : null);
        return m;
    }

    @GetMapping("/messages/{id}")
    public ResponseEntity<?> message(@PathVariable Long id) {
        return ops.analyse(id, true).<ResponseEntity<?>>map(ResponseEntity::ok).orElse(ResponseEntity.status(404).body(Map.of("error", "unknown message " + id)));
    }

    @GetMapping("/business")
    public List<Map<String, Object>> business(@RequestParam(required = false) String family, @RequestParam(required = false) String status, @RequestParam(required = false) String party) {
        return ops.businessList(family, status, party);
    }

    @GetMapping("/business/{ref}")
    public ResponseEntity<?> businessDetail(@PathVariable String ref) {
        return ops.business(ref, false).<ResponseEntity<?>>map(ResponseEntity::ok).orElse(ResponseEntity.status(404).body(Map.of("error", "unknown reference " + ref)));
    }

    @GetMapping("/business/{ref}/reconcile")
    public ResponseEntity<?> reconcile(@PathVariable String ref) {
        return ops.reconcile(ref).<ResponseEntity<?>>map(ResponseEntity::ok).orElse(ResponseEntity.status(404).body(Map.of("error", "unknown reference " + ref)));
    }

    @GetMapping("/scenarios")
    public List<Map<String, Object>> scenarios() {
        return ops.scenarios();
    }

    // ------------------------------------------------------------------------------------------------ compose / validate / review draft

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, String> body) {
        return ops.validate(body.get("raw"), body.get("from"), body.get("to"), body.get("mtType"));
    }

    /** Send as any participant (SUT or virtual bank) through the normal gateway path (validation, ISN/OSN, ACK/NAK, delivery, ledger, scenarios). */
    @PostMapping("/send")
    public ResponseEntity<SubmitResult> send(@RequestBody Map<String, String> body) {
        SubmitResult r = gateway.submit(body.get("raw"), SubmitOptions.builder().channel("ADMIN").from(blank(body.get("from"))).to(blank(body.get("to")))
                .mtType(blank(body.get("mtType"))).scenario(blank(body.get("scenario"))).build());
        return ResponseEntity.status("ACK".equals(r.getStatus()) ? HttpStatus.ACCEPTED : HttpStatus.UNPROCESSABLE_ENTITY).body(r);
    }

    @PostMapping("/review-queue/{id}/preview")
    public Map<String, Object> preview(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return review.preview(id, body);
    }

    // ------------------------------------------------------------------------------------------------ logs / audit

    @GetMapping("/logs")
    public Map<String, Object> logs(@RequestParam(defaultValue = "-1") long offset, @RequestParam(required = false) String level,
                                    @RequestParam(required = false) String q) throws IOException {
        Path f = Paths.get(props.getDataDir(), "app.log");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", f.toAbsolutePath().normalize().toString());
        if (!Files.exists(f)) { m.put("offset", 0); m.put("lines", List.of()); return m; }
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            long len = raf.length();
            long start = offset < 0 || offset > len ? Math.max(0, len - 96 * 1024) : offset;
            long end = Math.min(len, start + 512 * 1024);
            byte[] buf = new byte[(int) (end - start)];
            raf.seek(start);
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n")));
            if (offset < 0 && start > 0 && !lines.isEmpty()) lines.remove(0); // partial first line
            if (end < len || (!text.endsWith("\n") && !lines.isEmpty())) {     // keep a partial last line for the next poll
                String last = lines.remove(lines.size() - 1);
                end -= last.getBytes(StandardCharsets.UTF_8).length;
            }
            String lv = level == null ? "" : level.toUpperCase();
            List<String> out = new ArrayList<>();
            for (String l : lines) {
                if (!lv.isEmpty() && !lv.equals("ALL")) {
                    boolean ok = switch (lv) {
                        case "ERROR" -> l.contains(" ERROR ");
                        case "WARN" -> l.contains(" ERROR ") || l.contains(" WARN ");
                        default -> true;
                    };
                    if (!ok) continue;
                }
                if (q != null && !q.isBlank() && !l.toLowerCase().contains(q.toLowerCase())) continue;
                out.add(l);
            }
            m.put("offset", end);
            m.put("size", len);
            m.put("lines", out.size() > 2000 ? out.subList(out.size() - 2000, out.size()) : out);
        }
        return m;
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "200") int limit, @RequestParam(required = false) String q) {
        return audit.tail(Math.min(limit, 2000), q);
    }

    // ------------------------------------------------------------------------------------------------ tools

    private Path backupDir() throws IOException {
        Path d = Paths.get(props.backupDir()).toAbsolutePath().normalize();
        Files.createDirectories(d);
        return d;
    }

    /** online H2 backup (consistent snapshot, the simulator keeps running) */
    @PostMapping("/tools/backup")
    public Map<String, Object> backup() throws IOException {
        Path f = backupDir().resolve("swiftsim-db-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip");
        jdbc.execute("BACKUP TO '" + f.toString().replace("'", "''") + "'");
        return Map.of("file", f.toString(), "bytes", Files.size(f));
    }

    @GetMapping("/tools/backups")
    public List<Map<String, Object>> backups() throws IOException {
        try (Stream<Path> s = Files.list(backupDir())) {
            return s.filter(Files::isRegularFile).sorted(Comparator.reverseOrder()).map(p -> {
                try { return Map.<String, Object>of("name", p.getFileName().toString(), "bytes", Files.size(p), "modified", Files.getLastModifiedTime(p).toString()); }
                catch (IOException e) { return Map.<String, Object>of("name", p.getFileName().toString()); }
            }).toList();
        }
    }

    @GetMapping("/tools/backups/{name}")
    public ResponseEntity<?> download(@PathVariable String name) throws IOException {
        Path d = backupDir();
        Path p = d.resolve(name).normalize();
        if (!p.startsWith(d) || !Files.isRegularFile(p)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + p.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(Files.readAllBytes(p));
    }

    /** JSON export of all business records and messages (or of one reference) */
    @GetMapping("/tools/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String ref) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exportedAt", new Date().toInstant().toString());
        if (ref != null && !ref.isBlank()) {
            m.put("business", ops.business(ref, true).orElse(null));
            m.put("reconcile", ops.reconcile(ref).orElse(null));
        } else {
            m.put("business", ops.businessList(null, null, null));
            m.put("messages", messages.findAll(Sort.by("id")));
        }
        byte[] b = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(m);
        String name = "swiftsim-export-" + (ref == null || ref.isBlank() ? "all" : ref.replaceAll("[^A-Za-z0-9._-]", "_")) + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").contentType(MediaType.APPLICATION_JSON).body(b);
    }

    /** Purge test data. Body: refs[] | prefix | all, includeProtected[], nakMessages, dryRun (default true), confirm (token from the dry run). */
    @PostMapping("/tools/purge")
    public Map<String, Object> purge(@RequestBody Map<String, Object> body) {
        boolean dry = !Boolean.FALSE.equals(body.get("dryRun"));
        return purge.purge(body, dry);
    }

    private static Long lng(String s) {
        return s == null || s.isBlank() ? null : Long.valueOf(s);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
