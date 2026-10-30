package com.example.swiftsim.gateway;

import com.example.swiftsim.config.Bics;
import com.example.swiftsim.config.ConfigService;
import com.example.swiftsim.config.Participant;
import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.domain.MessageRepository;
import com.example.swiftsim.ledger.LedgerService;
import com.example.swiftsim.parse.MtHandler;
import com.example.swiftsim.parse.MxHandler;
import com.example.swiftsim.parse.ParsedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The simulated SWIFT gateway. Network level: parse + validate -> ACK/NAK (FIN-like) -> (delay) deliver in output
 * format with MOR -> delivery notification. Business level (receiver's application): ledger check/apply -> scenario
 * reaction / business reject / manual review queue (see ScenarioEngine).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService {
    private final ConfigService cfg;
    private final MtHandler mt;
    private final MxHandler mx;
    private final MessageRepository repo;
    private final TaskScheduler scheduler;
    private final ApplicationEventPublisher events;
    private final SessionService sessions;
    private final LedgerService ledger;

    @Value("${sim.data-dir:./data}")
    private String dataDir;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final DateTimeFormatter ACK_TS = DateTimeFormatter.ofPattern("yyMMddHHmm");
    private static final DateTimeFormatter D6 = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");
    private static final Pattern B1 = Pattern.compile("\\{1:[^}]*}");
    private static final Pattern B2 = Pattern.compile("\\{2:I(\\d{3})([A-Z0-9]{12})([SUN])?([123])?(\\d{3})?}");
    private static final Pattern B5 = Pattern.compile("\\{5:((?:\\{[^}]*})*)}\\s*$");

    private RestClient webhookClient() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(5000);
        return RestClient.builder().requestFactory(f).build();
    }

    public ParsedMessage parse(String raw, String fromOverride, String toOverride) {
        ParsedMessage pm;
        if (MxHandler.looksLikeMx(raw)) {
            pm = mx.parse(raw);
            String f = fromOverride != null ? bicOf(fromOverride) : pm.getSender();
            String t = toOverride != null ? bicOf(toOverride) : pm.getReceiver();
            if (fromOverride != null && pm.getSender() != null && !pm.getSender().equals(f))
                pm.getErrors().add("SIM-H12 ?from=" + fromOverride + " conflicts with AppHdr Fr " + pm.getSender());
            if (toOverride != null && pm.getReceiver() != null && !pm.getReceiver().equals(t))
                pm.getErrors().add("SIM-H12 ?to=" + toOverride + " conflicts with AppHdr To " + pm.getReceiver());
            if (!"true".equals(pm.getVars().get("_apphdr"))) {
                if (cfg.getConfig().getGateway().isRequireAppHdr()) pm.getErrors().add("SIM-H10 MX without Business Application Header (AppHdr) not accepted");
                else pm.getWarnings().add("MX without AppHdr: routing from ?from=/?to= or defaults (a real FINplus/MX message always carries an AppHdr)");
            }
            if (f == null) f = Bics.toBic11(cfg.getConfig().getGateway().getDefaultMxSender());
            if (t == null) t = Bics.toBic11(cfg.getConfig().getGateway().getDefaultMxReceiver());
            pm.setSender(f);
            pm.setReceiver(t);
        } else if (MtHandler.looksLikeMt(raw)) {
            mt.setFieldFormatValidation(cfg.getConfig().getGateway().isFieldFormatValidation());
            pm = mt.parse(raw);
        } else {
            pm = new ParsedMessage();
            pm.setFormat("UNKNOWN");
            pm.setMsgType("UNKNOWN");
            pm.getErrors().add("SIM-E01 cannot detect format: MT must start with {1:, MX with <");
        }
        return pm;
    }

    public synchronized SubmitResult submit(String raw, SubmitOptions o) {
        SubmitResult r = new SubmitResult();
        if (raw == null || raw.isBlank()) {
            r.setStatus("NAK");
            r.setReason("SIM-E00 empty message");
            return r;
        }
        raw = raw.replace("\r\n", "\n").strip();
        // convenience: bare MT block 4 (":20:...") + from/to/mtType => wrap headers
        if (raw.startsWith(":") && o.getFrom() != null && o.getTo() != null && o.getMtType() != null) {
            raw = wrapMt(o.getMtType(), bicOf(o.getFrom()), bicOf(o.getTo()), raw, false);
        }
        ParsedMessage pm = parse(raw, o.getFrom(), o.getTo());
        Optional<Participant> sp = cfg.byBic(pm.getSender());
        Optional<Participant> rp = cfg.byBic(pm.getReceiver());
        if (pm.getSender() == null) pm.getErrors().add("SIM-A00 sender BIC not determinable");
        else if (sp.isEmpty()) pm.getErrors().add("SIM-A01 sender " + pm.getSender() + " is not a registered participant");
        if (pm.getReceiver() == null) pm.getErrors().add("SIM-R00 receiver BIC not determinable");
        else if (rp.isEmpty()) pm.getErrors().add("SIM-R01 receiver " + pm.getReceiver() + " unknown in simulator directory");
        if (pm.getSender() != null && pm.getSender().equals(pm.getReceiver())) pm.getWarnings().add("sender and receiver are the same BIC");

        MessageRecord rec = new MessageRecord();
        rec.setCreatedAt(Instant.now());
        rec.setUpdatedAt(rec.getCreatedAt());
        boolean fromSut = sp.map(Participant::isSut).orElse(false);
        boolean toSut = rp.map(Participant::isSut).orElse(false);
        rec.setDirection(fromSut ? "IN" : toSut ? "OUT" : "INTERNAL");
        rec.setChannel(o.getChannel());
        rec.setFormat(pm.getFormat());
        rec.setMsgType(pm.getMsgType());
        rec.setSenderBic(pm.getSender());
        rec.setReceiverBic(pm.getReceiver());
        rec.setTxRef(trunc(pm.getTxRef(), 64));
        rec.setRelatedRef(trunc(pm.getRelatedRef(), 64));
        rec.setProwideClass(pm.getProwideClass());
        rec.setScenarioId(o.getProducedBy());
        rec.setInReplyTo(o.getInReplyTo());
        rec.setScenarioOverride(o.getScenario());
        rec.setDepth(o.getDepth());
        rec.setBusinessReject(o.isBusinessReject() ? Boolean.TRUE : null);
        rec.setRaw(raw);
        rec.setMur(trunc(pm.getVars().get("_mur"), 64));
        rec.setContentHash(contentHash(raw, pm));
        // ---- FIN-like session / sequence numbers (MT) or network reference (MX)
        if ("MT".equals(pm.getFormat()) && sp.isPresent()) {
            long expected = sessions.peekIsn(pm.getSender()) + 1;
            String given = pm.getVars().get("_isn");
            if (given != null && !given.matches("0+") && Long.parseLong(given) != expected) {
                String w = "ISN " + given + " in block 1 is not the expected next input sequence number " + String.format("%06d", expected);
                if (cfg.getConfig().getGateway().isEnforceIsn()) pm.getErrors().add("SIM-S01 " + w);
                else pm.getWarnings().add(w + " - gateway assigned " + String.format("%06d", expected) + " (real FIN would abort the session)");
            }
            LtSession s = sessions.nextIsn(pm.getSender());
            rec.setSessionNo(s.getSessionNo());
            rec.setIsn(s.getLastIsn());
            rec.setMir(LocalDate.now().format(D6) + Bics.toLt(pm.getSender(), 'A') + String.format("%04d%06d", s.getSessionNo(), s.getLastIsn()));
            rec.setUetr(pm.getVars().get("_uetr"));
        } else if ("MX".equals(pm.getFormat())) {
            rec.setUetr(UUID.randomUUID().toString());
        }
        // ---- possible duplicate emission
        if ("true".equals(pm.getVars().get("_pde"))) {
            rec.setPossibleDuplicate(true);
            List<MessageRecord> prior = rec.getContentHash() == null ? List.of()
                    : repo.findByContentHashAndSenderBicAndIdLessThanOrderByIdAsc(rec.getContentHash(), pm.getSender(), Long.MAX_VALUE).stream()
                    .filter(m -> !"NAK".equals(m.getStatus())).toList();
            if (!prior.isEmpty()) {
                rec.setDuplicateOf(prior.get(0).getId());
                pm.getWarnings().add("PDE: possible duplicate of message #" + prior.get(0).getId() + " (same sender and content); the receiver will not process it again");
            } else {
                pm.getWarnings().add("PDE: possible duplicate flag set but no original found - delivered and processed as a new message");
            }
        } else if (rec.getMur() != null && sp.isPresent() && repo.existsBySenderBicAndMurAndIdNot(pm.getSender(), rec.getMur(), -1L)) {
            pm.getWarnings().add(("MX".equals(pm.getFormat()) ? "BizMsgIdr " : "MUR {108:} ") + rec.getMur() + " already used by this sender (not a network error; check your reference generation)");
        }
        rec.setWarnings(pm.getWarnings().isEmpty() ? null : trunc(String.join("\n", pm.getWarnings()), 4000));
        boolean ok = pm.valid();
        rec.setStatus(ok ? "ACK" : "NAK");
        rec.setStatusReason(ok ? null : trunc(String.join("\n", pm.getErrors()), 4000));
        repo.save(rec);
        if ("MX".equals(pm.getFormat())) {
            rec.setNetworkRef("SIMSNF" + LocalDate.now().format(D6) + String.format("%08d", rec.getId()));
            repo.save(rec);
        }

        // ACK / NAK service message back to the sender
        MessageRecord ack = new MessageRecord();
        ack.setCreatedAt(Instant.now());
        ack.setUpdatedAt(ack.getCreatedAt());
        ack.setDirection("SYS");
        ack.setChannel("GATEWAY");
        ack.setFormat("SYS");
        ack.setMsgType(ok ? "ACK" : "NAK");
        ack.setSenderBic("SIMGATEWAY");
        ack.setReceiverBic(pm.getSender());
        ack.setTxRef(rec.getTxRef());
        ack.setRelatedRef(rec.getRelatedRef());
        ack.setInReplyTo(rec.getId());
        ack.setStatus(ok ? "ACK" : "NAK");
        ack.setStatusReason(rec.getStatusReason());
        ack.setMir(rec.getMir());
        ack.setRaw(ackText(pm, rec, ok));
        ack.setDeliveredRaw(ack.getRaw());
        repo.save(ack);
        if (sp.isPresent() && sp.get().isSut()) {
            writeOutbox(ack, sp.get().bic11());
            webhook(sp.get(), ack);
        }

        r.setId(rec.getId());
        r.setStatus(rec.getStatus());
        r.setReason(rec.getStatusReason());
        r.setFormat(pm.getFormat());
        r.setMsgType(pm.getMsgType());
        r.setTxRef(pm.getTxRef());
        r.setSender(pm.getSender());
        r.setReceiver(pm.getReceiver());
        r.setProwideClass(pm.getProwideClass());
        r.setAckId(ack.getId());
        r.setAck(ack.getRaw());
        r.setMir(rec.getMir());
        r.setSessionNo(rec.getSessionNo());
        r.setIsn(rec.getIsn());
        r.setMur(rec.getMur());
        r.setUetr(rec.getUetr());
        r.setNetworkRef(rec.getNetworkRef());
        r.setPossibleDuplicate(rec.getPossibleDuplicate());
        r.setDuplicateOf(rec.getDuplicateOf());
        r.getErrors().addAll(pm.getErrors());
        r.getWarnings().addAll(pm.getWarnings());

        if (ok) {
            long delay = cfg.getConfig().getGateway().getDeliveryDelayMs();
            final ParsedMessage fpm = pm;
            scheduler.schedule(() -> deliver(rec.getId(), fpm, o), Instant.now().plusMillis(delay));
        }
        log.info("{} {} {} {} -> {} ref={} {}", rec.getId(), rec.getDirection(), rec.getMsgType(), rec.getSenderBic(),
                rec.getReceiverBic(), rec.getTxRef(), rec.getStatus());
        return r;
    }

    private void deliver(Long id, ParsedMessage pm, SubmitOptions o) {
        try {
            MessageRecord rec = repo.findById(id).orElseThrow();
            if (!"ACK".equals(rec.getStatus())) return; // already delivered (e.g. re-delivery after restart)
            Participant recv = cfg.byBic(rec.getReceiverBic()).orElseThrow();
            LtSession s = sessions.nextOsn(recv.bic11());
            LocalDateTime now = LocalDateTime.now();
            rec.setStatus("DELIVERED");
            rec.setUpdatedAt(Instant.now());
            if ("MT".equals(rec.getFormat())) {
                rec.setOsn(s.getLastOsn());
                rec.setMor(now.format(D6) + Bics.toLt(recv.bic11(), 'A') + String.format("%04d%06d", s.getSessionNo(), s.getLastOsn()));
                rec.setDeliveredRaw(toOutput(rec, recv, s, now));
            } else {
                rec.setDeliveredRaw(rec.getRaw());
            }
            // business level: receiver's application checks the message against the undertaking ledger
            LedgerService.Outcome out = ledger.process(rec, pm, recv.isSut(), o.isBusinessReject());
            rec.setBusinessStatus(out.status());
            rec.setBusinessReason(out.errors().isEmpty() ? null : trunc(String.join("\n", out.errors()), 4000));
            if (out.ref() != null && ledger.existsAny(out.ref())) rec.setUndertakingRef(trunc(out.ref(), 64));
            repo.save(rec);
            rec.setOutboxFile(writeOutbox(rec, recv.bic11()));
            rec.setWebhookStatus(webhook(recv, rec));
            repo.save(rec);
            Optional<Participant> sender = cfg.byBic(rec.getSenderBic());
            boolean requested = "MT".equals(rec.getFormat()) && Set.of("2", "3").contains(pm.getVars().getOrDefault("_monitoring", ""));
            if (sender.isPresent() && (requested || sender.get().isSut() && sender.get().isDeliveryNotifications())) {
                MessageRecord dlv = new MessageRecord();
                dlv.setCreatedAt(Instant.now());
                dlv.setUpdatedAt(dlv.getCreatedAt());
                dlv.setDirection("SYS");
                dlv.setChannel("GATEWAY");
                dlv.setFormat("SYS");
                dlv.setMsgType("DLV");
                dlv.setSenderBic("SIMGATEWAY");
                dlv.setReceiverBic(rec.getSenderBic());
                dlv.setTxRef(rec.getTxRef());
                dlv.setInReplyTo(rec.getId());
                dlv.setStatus("SENT");
                dlv.setMir(rec.getMir());
                dlv.setMor(rec.getMor());
                dlv.setRaw(dlvText(rec, now));
                dlv.setDeliveredRaw(dlv.getRaw());
                repo.save(dlv);
                if (sender.get().isSut()) {
                    writeOutbox(dlv, sender.get().bic11());
                    webhook(sender.get(), dlv);
                }
            }
            if (!recv.isSut()) events.publishEvent(new DeliveredEvent(rec, pm, o, out));
        } catch (Exception e) {
            log.error("delivery of {} failed", id, e);
        }
    }

    /** After a restart: deliver messages that were ACKed but not yet delivered. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void redeliverAfterRestart() {
        List<MessageRecord> list = repo.findByStatusAndDirectionNot("ACK", "SYS");
        for (MessageRecord m : list) {
            ParsedMessage pm = parse(m.getRaw(), null, null);
            pm.setSender(m.getSenderBic());
            pm.setReceiver(m.getReceiverBic());
            SubmitOptions o = SubmitOptions.builder().channel(m.getChannel()).scenario(m.getScenarioOverride()).producedBy(m.getScenarioId())
                    .inReplyTo(m.getInReplyTo()).depth(m.getDepth() == null ? 0 : m.getDepth()).businessReject(Boolean.TRUE.equals(m.getBusinessReject())).build();
            note(m.getTxRef(), null, m.getId(), "Gateway restarted: message #" + m.getId() + " was ACKed but not yet delivered - delivering now");
            scheduler.schedule(() -> deliver(m.getId(), pm, o), Instant.now().plusMillis(1500));
        }
        if (!list.isEmpty()) log.info("re-delivering {} ACKed-but-undelivered messages after restart", list.size());
    }

    /** MT input -> output format as seen by the receiver (approximation of FIN output). */
    String toOutput(MessageRecord rec, Participant recv, LtSession s, LocalDateTime now) {
        String raw = rec.getRaw();
        String inTime = LocalDateTime.ofInstant(rec.getCreatedAt(), ZoneId.systemDefault()).format(HHMM);
        Matcher m2 = B2.matcher(raw);
        String b2 = m2.find()
                ? "{2:O" + m2.group(1) + inTime + rec.getMir() + now.format(D6) + now.format(HHMM) + (m2.group(3) == null ? "N" : m2.group(3)) + "}"
                : "{2:O" + rec.getMsgType().replace("MT", "") + inTime + rec.getMir() + now.format(D6) + now.format(HHMM) + "N}";
        String out = B1.matcher(raw).replaceFirst(Matcher.quoteReplacement("{1:F01" + Bics.toLt(recv.bic11(), 'A') + String.format("%04d%06d", s.getSessionNo(), s.getLastOsn()) + "}"));
        out = B2.matcher(out).replaceFirst(Matcher.quoteReplacement(b2));
        String userTrailer = "";
        Matcher m5 = B5.matcher(out);
        if (m5.find()) {
            userTrailer = m5.group(1);
            out = out.substring(0, m5.start());
        }
        String b4 = raw.contains("{4:") ? raw.substring(raw.indexOf("{4:")) : raw;
        String chk = sha(b4).substring(0, 12).toUpperCase();
        String trailer = "{5:{CHK:" + chk + "}" + (cfg.getConfig().getGateway().isTestAndTrainingTrailer() ? "{TNG:}" : "")
                + userTrailer.replaceAll("\\{(CHK|TNG):[^}]*}", "") + "}";
        return out + trailer;
    }

    private String dlvText(MessageRecord rec, LocalDateTime now) {
        if (!"MT".equals(rec.getFormat())) {
            return "<SimDeliveryNotification xmlns=\"urn:swiftsim:gateway:dlv\">\n  <MsgId>" + rec.getId() + "</MsgId>\n  <NetworkRef>" + esc(rec.getNetworkRef())
                    + "</NetworkRef>\n  <BizMsgIdr>" + esc(rec.getMur()) + "</BizMsgIdr>\n  <MsgDefIdr>" + esc(rec.getMsgType()) + "</MsgDefIdr>\n  <Receiver>"
                    + esc(rec.getReceiverBic()) + "</Receiver>\n  <DeliveredAt>" + rec.getUpdatedAt() + "</DeliveredAt>\n"
                    + "  <Note>Simulated delivery notification (approximation, not a SWIFTNet/FINplus system message)</Note>\n</SimDeliveryNotification>";
        }
        String lt = Bics.toLt(rec.getSenderBic(), 'A');
        String inTime = LocalDateTime.ofInstant(rec.getCreatedAt(), ZoneId.systemDefault()).format(HHMM);
        return "{1:F01" + lt + String.format("%04d%06d", rec.getSessionNo() == null ? 0 : rec.getSessionNo(), 0) + "}"
                + "{2:O011" + now.format(HHMM) + now.format(D6) + "N}"
                + "{4:{175:" + inTime + "}{106:" + rec.getMir() + "}" + (rec.getMur() != null ? "{108:" + rec.getMur() + "}" : "")
                + "{175:" + now.format(HHMM) + "}{107:" + rec.getMor() + "}}";
    }

    /** Write a NOTE entry in the log (e.g. timeout scenario). */
    public void note(String txRef, String scenario, Long inReplyTo, String text) {
        MessageRecord n = new MessageRecord();
        n.setCreatedAt(Instant.now());
        n.setUpdatedAt(n.getCreatedAt());
        n.setDirection("SYS");
        n.setChannel("SCENARIO");
        n.setFormat("SYS");
        n.setMsgType("NOTE");
        n.setSenderBic("SIMULATOR");
        n.setTxRef(trunc(txRef, 64));
        n.setScenarioId(scenario);
        n.setInReplyTo(inReplyTo);
        n.setStatus("INFO");
        n.setRaw(text);
        repo.save(n);
    }

    public void tagScenario(Long id, String scenario) {
        repo.findById(id).ifPresent(r -> {
            if (r.getScenarioId() == null) {
                r.setScenarioId("matched:" + scenario);
                repo.save(r);
            }
        });
    }

    public void setBusinessStatus(Long id, String status) {
        repo.findById(id).ifPresent(r -> {
            r.setBusinessStatus(status);
            repo.save(r);
        });
    }

    public String bicOf(String idOrBic) {
        return cfg.resolve(idOrBic).map(Participant::bic11).orElse(Bics.toBic11(idOrBic));
    }

    public static String wrapMt(String type, String fromBic, String toBic, String body, boolean pde) {
        String mur = ("SIM" + Long.toString(System.nanoTime(), 36)).toUpperCase();
        if (mur.length() > 16) mur = mur.substring(0, 16);
        String b = body.strip();
        if (b.endsWith("-")) b = b.substring(0, b.length() - 1).strip();
        String b3 = "{108:" + mur + "}" + (Set.of("103", "202", "205").contains(type) ? "{121:" + UUID.randomUUID() + "}" : "");
        return "{1:F01" + Bics.toLt(fromBic, 'A') + "0000000000}"
                + "{2:I" + type + Bics.toLt(toBic, 'X') + "N}"
                + "{3:" + b3 + "}"
                + "{4:\n" + b + "\n-}"
                + (pde ? "{5:{PDE:}}" : "");
    }

    /** Mark an already rendered message as possible duplicate (MT: PDE trailer, MX: AppHdr PssblDplct). */
    public static String asPossibleDuplicate(String raw) {
        if (MxHandler.looksLikeMx(raw)) {
            if (raw.contains("PssblDplct>")) return raw;
            Matcher m = Pattern.compile("</((?:\\w+:)?)AppHdr>").matcher(raw);
            if (m.find()) return raw.substring(0, m.start()) + "<" + m.group(1) + "PssblDplct>true</" + m.group(1) + "PssblDplct>" + raw.substring(m.start());
            return raw;
        }
        if (raw.contains("{PDE:")) return raw;
        Matcher m5 = B5.matcher(raw);
        if (m5.find()) return raw.substring(0, m5.start()) + "{5:" + m5.group(1) + "{PDE:}}";
        return raw + "{5:{PDE:}}";
    }

    private String ackText(ParsedMessage pm, MessageRecord rec, boolean ok) {
        if ("MX".equals(pm.getFormat())) {
            StringBuilder sb = new StringBuilder();
            sb.append("<SimGatewayAck xmlns=\"urn:swiftsim:gateway:ack\">\n")
                    .append("  <Status>").append(ok ? "ACK" : "NAK").append("</Status>\n")
                    .append("  <MsgId>").append(rec.getId()).append("</MsgId>\n")
                    .append("  <NetworkRef>").append(esc(rec.getNetworkRef())).append("</NetworkRef>\n")
                    .append("  <TransportUUID>").append(esc(rec.getUetr())).append("</TransportUUID>\n")
                    .append("  <BizMsgIdr>").append(esc(rec.getMur())).append("</BizMsgIdr>\n")
                    .append("  <MsgDefIdr>").append(esc(pm.getMsgType())).append("</MsgDefIdr>\n")
                    .append("  <TxRef>").append(esc(pm.getTxRef())).append("</TxRef>\n");
            if (Boolean.TRUE.equals(rec.getPossibleDuplicate())) sb.append("  <PossibleDuplicateOf>").append(rec.getDuplicateOf() == null ? "" : rec.getDuplicateOf()).append("</PossibleDuplicateOf>\n");
            for (String e : pm.getErrors()) sb.append("  <Error>").append(esc(e)).append("</Error>\n");
            sb.append("  <Note>Simulated gateway acknowledgement, not a SWIFT xsys/FINplus message</Note>\n");
            return sb.append("</SimGatewayAck>").toString();
        }
        String ts = LocalDateTime.now().format(ACK_TS);
        String lt = pm.getSender() == null ? "UNKNOWNXXXXX" : Bics.toLt(pm.getSender(), 'A');
        String seq = String.format("%04d%06d", rec.getSessionNo() == null ? 0 : rec.getSessionNo(), rec.getIsn() == null ? 0 : rec.getIsn());
        String mur = rec.getMur() != null ? "{108:" + rec.getMur() + "}" : "";
        String b4 = ok ? "{177:" + ts + "}{451:0}" + mur : "{177:" + ts + "}{451:1}{405:" + firstCode(pm.getErrors()) + "}" + mur;
        // FIN-like: the ACK/NAK service message is followed by a copy of the original input message
        return "{1:F21" + lt + seq + "}{4:" + b4 + "}" + rec.getRaw();
    }

    private static String firstCode(List<String> errs) {
        if (errs.isEmpty()) return "SIM00";
        String c = errs.get(0).split(" ", 2)[0];
        return c.replace("-", "");
    }

    static String contentHash(String raw, ParsedMessage pm) {
        String body;
        if ("MT".equals(pm.getFormat())) {
            int i = raw.indexOf("{4:"), j = raw.lastIndexOf("-}");
            body = i >= 0 && j > i ? raw.substring(i, j) : raw;
        } else if ("MX".equals(pm.getFormat())) {
            int i = raw.indexOf("<Document"), j = raw.lastIndexOf("Document>");
            if (i < 0) { i = raw.indexOf(":Document"); i = i > 0 ? raw.lastIndexOf('<', i) : -1; }
            body = i >= 0 && j > i ? raw.substring(i, j) : raw;
            body = body.replaceAll("\\s+", " ");
        } else return null;
        return sha(pm.getMsgType() + "|" + pm.getReceiver() + "|" + body);
    }

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return null;
        }
    }

    String writeOutbox(MessageRecord rec, String bic) {
        try {
            Path dir = Path.of(dataDir, "outbox", bic);
            Files.createDirectories(dir);
            String ext = "MX".equals(rec.getFormat()) ? ".xml" : "MT".equals(rec.getFormat()) ? ".fin" : ".txt";
            String name = LocalDateTime.now().format(TS) + "_" + rec.getId() + "_" + rec.getMsgType() + ext;
            Path p = dir.resolve(name);
            Files.writeString(p, rec.getDeliveredRaw() != null ? rec.getDeliveredRaw() : rec.getRaw());
            return p.toString();
        } catch (Exception e) {
            log.warn("outbox write failed: {}", e.getMessage());
            return null;
        }
    }

    String webhook(Participant p, MessageRecord rec) {
        if (p.getWebhookUrl() == null || p.getWebhookUrl().isBlank()) return null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", rec.getId());
            body.put("msgType", rec.getMsgType());
            body.put("format", rec.getFormat());
            body.put("sender", rec.getSenderBic());
            body.put("receiver", rec.getReceiverBic());
            body.put("txRef", rec.getTxRef());
            body.put("status", rec.getStatus());
            body.put("mir", rec.getMir());
            body.put("mor", rec.getMor());
            body.put("possibleDuplicate", rec.getPossibleDuplicate());
            body.put("raw", rec.getDeliveredRaw() != null ? rec.getDeliveredRaw() : rec.getRaw());
            var resp = webhookClient().post().uri(p.getWebhookUrl()).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
            return "HTTP " + resp.getStatusCode().value();
        } catch (Exception e) {
            return "ERROR " + trunc(e.getMessage(), 400);
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String trunc(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }
}
