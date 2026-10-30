package com.example.swiftsim.ops;

import com.example.swiftsim.config.ConfigService;
import com.example.swiftsim.config.Participant;
import com.example.swiftsim.config.Scenario;
import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.domain.MessageRepository;
import com.example.swiftsim.gateway.GatewayService;
import com.example.swiftsim.gateway.SessionService;
import com.example.swiftsim.ledger.BusinessClock;
import com.example.swiftsim.ledger.LedgerService;
import com.example.swiftsim.parse.ParsedMessage;
import com.example.swiftsim.review.ReviewItem;
import com.example.swiftsim.review.ReviewItemRepository;
import com.example.swiftsim.review.ReviewModeService;
import com.example.swiftsim.scenario.PendingResponse;
import com.example.swiftsim.scenario.ScenarioEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-side services shared by the admin console and the AI API: message search, message analysis, validation (dry run), business reconciliation. */
@Service
@RequiredArgsConstructor
public class OpsService {
    private final MessageRepository messages;
    private final GatewayService gateway;
    private final LedgerService ledger;
    private final ConfigService cfg;
    private final SessionService sessions;
    private final BusinessClock clock;
    private final ReviewItemRepository reviewItems;
    private final ReviewModeService reviewMode;
    private final ScenarioEngine engine;
    private final ObjectMapper json;

    private static final Pattern CODE = Pattern.compile("^((?:SIM|BIZ)-[A-Z]\\d\\d)\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern FIELD = Pattern.compile("field\\s+:?(\\d{2}[A-Z]?):?", Pattern.CASE_INSENSITIVE);
    private Map<String, Object> stateMachines;

    // ------------------------------------------------------------------------------------------------ message search

    public record MessageQuery(String bank, String from, String to, String direction, String type, String format, String ref, String status,
                               String business, Long sinceId, Long beforeId, Instant dateFrom, Instant dateTo, boolean includeSys, int limit) { }

    public List<MessageRecord> search(MessageQuery q) {
        Specification<MessageRecord> spec = (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (!blank(q.bank())) { String b = gateway.bicOf(q.bank()); ps.add(cb.or(cb.equal(root.get("senderBic"), b), cb.equal(root.get("receiverBic"), b))); }
            if (!blank(q.from())) ps.add(cb.equal(root.get("senderBic"), gateway.bicOf(q.from())));
            if (!blank(q.to())) ps.add(cb.equal(root.get("receiverBic"), gateway.bicOf(q.to())));
            if (!blank(q.direction())) ps.add(root.get("direction").in(Arrays.stream(q.direction().toUpperCase().split(",")).map(String::trim).toList()));
            else if (!q.includeSys()) ps.add(cb.notEqual(root.get("direction"), "SYS"));
            if (!blank(q.type())) ps.add(cb.like(cb.lower(root.get("msgType")), "%" + q.type().toLowerCase().replace("mt", "") + "%"));
            if (!blank(q.format())) ps.add(cb.equal(root.get("format"), q.format().toUpperCase()));
            if (!blank(q.ref())) {
                String r = "%" + q.ref() + "%";
                ps.add(cb.or(cb.like(root.get("txRef"), r), cb.like(root.get("relatedRef"), r), cb.like(root.get("undertakingRef"), r)));
            }
            if (!blank(q.status())) ps.add(root.get("status").in(Arrays.stream(q.status().toUpperCase().split(",")).map(String::trim).toList()));
            if (!blank(q.business())) {
                String b = q.business().toUpperCase();
                if (b.equals("BIZREJECT")) ps.add(cb.or(cb.isTrue(root.get("businessReject")), root.get("businessStatus").in("REJECTED", "INCONSISTENT")));
                else ps.add(root.get("businessStatus").in(Arrays.stream(b.split(",")).map(String::trim).toList()));
            }
            if (q.sinceId() != null) ps.add(cb.greaterThan(root.get("id"), q.sinceId()));
            if (q.beforeId() != null) ps.add(cb.lessThan(root.get("id"), q.beforeId()));
            if (q.dateFrom() != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), q.dateFrom()));
            if (q.dateTo() != null) ps.add(cb.lessThan(root.get("createdAt"), q.dateTo()));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Sort sort = q.sinceId() != null ? Sort.by(Sort.Direction.ASC, "id") : Sort.by(Sort.Direction.DESC, "id");
        return messages.findAll(spec, PageRequest.of(0, Math.max(1, Math.min(q.limit(), 1000)), sort)).getContent();
    }

    public static Instant parseDate(String s, boolean end) {
        if (blank(s)) return null;
        if (s.length() == 10) {
            LocalDate d = LocalDate.parse(s);
            return (end ? d.plusDays(1) : d).atStartOfDay(ZoneId.systemDefault()).toInstant();
        }
        return OffsetDateTime.parse(s).toInstant();
    }

    public Map<String, Object> summary(MessageRecord m) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", m.getId());
        x.put("createdAt", m.getCreatedAt());
        x.put("direction", m.getDirection());
        x.put("channel", m.getChannel());
        x.put("format", m.getFormat());
        x.put("msgType", m.getMsgType());
        x.put("sender", m.getSenderBic());
        x.put("receiver", m.getReceiverBic());
        x.put("txRef", m.getTxRef());
        x.put("relatedRef", m.getRelatedRef());
        x.put("status", m.getStatus());
        if (m.getStatusReason() != null) x.put("statusReason", m.getStatusReason());
        if (m.getBusinessStatus() != null) x.put("businessStatus", m.getBusinessStatus());
        if (m.getBusinessReason() != null) x.put("businessReason", m.getBusinessReason());
        if (Boolean.TRUE.equals(m.getBusinessReject())) x.put("businessReject", true);
        if (m.getUndertakingRef() != null) x.put("businessRef", m.getUndertakingRef());
        if (m.getInReplyTo() != null) x.put("inReplyTo", m.getInReplyTo());
        if (m.getScenarioId() != null) x.put("scenario", m.getScenarioId());
        if (m.getMir() != null) x.put("mir", m.getMir());
        if (m.getMor() != null) x.put("mor", m.getMor());
        if (m.getIsn() != null) x.put("isn", m.getIsn());
        if (m.getOsn() != null) x.put("osn", m.getOsn());
        if (m.getUetr() != null) x.put("uetr", m.getUetr());
        if (m.getMur() != null) x.put("mur", m.getMur());
        if (Boolean.TRUE.equals(m.getPossibleDuplicate())) { x.put("possibleDuplicate", true); x.put("duplicateOf", m.getDuplicateOf()); }
        if (m.getWarnings() != null) x.put("warnings", List.of(m.getWarnings().split("\n")));
        return x;
    }

    /** message + parsed fields + validation report + linked business + replies */
    public Optional<Map<String, Object>> analyse(Long id, boolean includeRaw) {
        Optional<MessageRecord> o = messages.findById(id);
        if (o.isEmpty()) return Optional.empty();
        MessageRecord m = o.get();
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("message", summary(m));
        if (includeRaw) {
            x.put("raw", m.getRaw());
            if (m.getDeliveredRaw() != null && !m.getDeliveredRaw().equals(m.getRaw())) x.put("deliveredRaw", m.getDeliveredRaw());
        }
        if (!"SYS".equals(m.getFormat()) && m.getRaw() != null) {
            ParsedMessage pm = gateway.parse(m.getRaw(), null, null);
            x.put("fields", fields(pm));
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("syntaxValid", pm.valid());
            v.put("gatewayResult", m.getStatus());
            v.put("findings", findings(pm.getErrors(), "ERROR", "syntax", List.of(m.getId())));
            List<Map<String, Object>> f = new ArrayList<>((List<Map<String, Object>>) v.get("findings"));
            f.addAll(findings(pm.getWarnings(), "WARN", "syntax", List.of(m.getId())));
            if (m.getStatusReason() != null && pm.valid()) f.addAll(findings(List.of(m.getStatusReason().split("\n")), "ERROR", "network", List.of(m.getId())));
            if (m.getBusinessReason() != null)
                f.addAll(findings(List.of(m.getBusinessReason().split("\n")), "REJECTED".equals(m.getBusinessStatus()) || "INCONSISTENT".equals(m.getBusinessStatus()) ? "ERROR" : "INFO", "business", List.of(m.getId())));
            v.put("findings", f);
            v.put("businessStatus", m.getBusinessStatus());
            x.put("validation", v);
        }
        String bref = m.getUndertakingRef() != null ? m.getUndertakingRef() : m.getTxRef();
        ledger.familyOfRef(bref).ifPresent(fam -> x.put("business", Map.of("ref", bref, "family", fam)));
        if (!x.containsKey("business") && m.getRelatedRef() != null)
            ledger.familyOfRef(m.getRelatedRef()).ifPresent(fam -> x.put("business", Map.of("ref", m.getRelatedRef(), "family", fam)));
        List<MessageRecord> rel = messages.findAll((root, cq, cb) -> cb.equal(root.get("inReplyTo"), m.getId()), Sort.by("id"));
        x.put("replies", rel.stream().map(r -> Map.of("id", r.getId(), "msgType", r.getMsgType(), "direction", r.getDirection(), "status", r.getStatus() == null ? "" : r.getStatus())).toList());
        if (m.getInReplyTo() != null) x.put("inReplyTo", m.getInReplyTo());
        return Optional.of(x);
    }

    private static Map<String, String> fields(ParsedMessage pm) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("format", pm.getFormat());
        f.put("msgType", pm.getMsgType());
        if (pm.getSender() != null) f.put("sender", pm.getSender());
        if (pm.getReceiver() != null) f.put("receiver", pm.getReceiver());
        if (pm.getTxRef() != null) f.put("txRef(20)", pm.getTxRef());
        if (pm.getRelatedRef() != null) f.put("relatedRef(21)", pm.getRelatedRef());
        pm.getVars().forEach((k, v) -> { if (!k.startsWith("_") || Set.of("_mur", "_uetr", "_isn", "_pde", "_monitoring").contains(k)) f.put(k, v); });
        return f;
    }

    public List<Map<String, Object>> findings(Collection<String> texts, String severity, String layer, List<Long> evidence) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (String t : texts) {
            if (t == null || t.isBlank()) continue;
            Matcher cm = CODE.matcher(t.trim());
            String code = cm.matches() ? cm.group(1) : layer.toUpperCase() + "-" + severity;
            Map<String, Object> f = finding(severity, code, t.trim(), evidence);
            f.put("layer", layer);
            Matcher fm = FIELD.matcher(t);
            if (fm.find()) f.put("field", fm.group(1));
            l.add(f);
        }
        return l;
    }

    private static Map<String, Object> finding(String severity, String code, String message, List<Long> evidence) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("severity", severity);
        f.put("code", code);
        f.put("message", message);
        if (evidence != null && !evidence.isEmpty()) f.put("evidence", evidence);
        return f;
    }

    // ------------------------------------------------------------------------------------------------ validate (dry run)

    /** Full check of a message WITHOUT sending it: syntax / network rules (Prowide + simulator), addressing, and business rules against the current ledger (rolled back). */
    public Map<String, Object> validate(String raw, String from, String to, String mtType) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("message text required");
        raw = raw.replace("\r\n", "\n").strip();
        if (raw.startsWith(":")) {
            if (blank(from) || blank(to) || blank(mtType)) throw new IllegalArgumentException("bare MT block 4 needs from, to and mtType");
            raw = GatewayService.wrapMt(mtType.replace("MT", ""), gateway.bicOf(from), gateway.bicOf(to), raw, false);
            out.put("wrappedRaw", raw);
        }
        ParsedMessage pm = gateway.parse(raw, blank(from) ? null : from, blank(to) ? null : to);
        List<Map<String, Object>> f = new ArrayList<>(findings(pm.getErrors(), "ERROR", "syntax", null));
        f.addAll(findings(pm.getWarnings(), "WARN", "syntax", null));
        Optional<Participant> sp = cfg.byBic(pm.getSender()), rp = cfg.byBic(pm.getReceiver());
        List<String> net = new ArrayList<>();
        if (pm.getSender() == null) net.add("SIM-A00 sender BIC not determinable");
        else if (sp.isEmpty()) net.add("SIM-A01 sender " + pm.getSender() + " is not a registered participant");
        if (pm.getReceiver() == null) net.add("SIM-R00 receiver BIC not determinable");
        else if (rp.isEmpty()) net.add("SIM-R01 receiver " + pm.getReceiver() + " unknown in simulator directory");
        f.addAll(findings(net, "ERROR", "network", null));
        boolean wouldNak = !pm.valid() || !net.isEmpty();
        out.put("format", pm.getFormat());
        out.put("msgType", pm.getMsgType());
        out.put("sender", pm.getSender());
        out.put("receiver", pm.getReceiver());
        out.put("txRef", pm.getTxRef());
        out.put("relatedRef", pm.getRelatedRef());
        out.put("direction", sp.map(Participant::isSut).orElse(false) ? "IN" : rp.map(Participant::isSut).orElse(false) ? "OUT" : "INTERNAL");
        out.put("fields", fields(pm));
        String verdict;
        if (wouldNak) {
            verdict = "WOULD_NAK";
            out.put("business", Map.of("checked", false, "reason", "message would be rejected by the gateway (NAK) and never reach the receiver"));
        } else {
            MessageRecord stub = new MessageRecord();
            stub.setCreatedAt(Instant.now());
            stub.setDirection((String) out.get("direction"));
            stub.setChannel("VALIDATE");
            stub.setFormat(pm.getFormat());
            stub.setMsgType(pm.getMsgType());
            stub.setSenderBic(pm.getSender());
            stub.setReceiverBic(pm.getReceiver());
            stub.setTxRef(pm.getTxRef());
            stub.setRelatedRef(pm.getRelatedRef());
            stub.setRaw(raw);
            stub.setStatus("DELIVERED");
            LedgerService.Outcome o = ledger.dryRun(stub, pm, rp.get().isSut());
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("checked", true);
            b.put("status", o.status());
            b.put("ref", o.ref());
            if (o.fromStatus() != null || o.toStatus() != null) { b.put("fromStatus", o.fromStatus()); b.put("toStatus", o.toStatus()); }
            ledger.familyOfRef(o.ref()).ifPresent(fam -> b.put("family", fam));
            out.put("business", b);
            boolean bad = Set.of("REJECTED", "INCONSISTENT").contains(o.status());
            f.addAll(findings(o.errors(), bad ? "ERROR" : "WARN", "business", null));
            verdict = bad ? (rp.get().isSut() ? "BUSINESS_INCONSISTENT" : "WOULD_BUSINESS_REJECT") : "OK";
        }
        out.put("verdict", verdict);
        out.put("verdict_zh", switch (verdict) {
            case "WOULD_NAK" -> "网关会 NAK（语法/网络校验不通过）";
            case "WOULD_BUSINESS_REJECT" -> "网关 ACK，但收报虚拟行会业务拒绝";
            case "BUSINESS_INCONSISTENT" -> "网关 ACK，但与业务台账不一致";
            default -> "通过（语法、网络、业务均无错误）";
        });
        out.put("findings", f);
        out.put("note", "dry run - nothing was sent, no sequence number consumed, ledger unchanged");
        return out;
    }

    // ------------------------------------------------------------------------------------------------ business + reconcile

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> business(String ref, boolean includeRaw) {
        Optional<String> fam = ledger.familyOfRef(ref);
        if (fam.isEmpty()) return Optional.empty();
        Optional<Map<String, Object>> d = ledger.detailAny(ref, includeRaw);
        if (d.isEmpty()) return Optional.empty();
        Map<String, Object> m = new LinkedHashMap<>(json.convertValue(d.get(), new TypeReference<Map<String, Object>>() { }));
        m.put("family", fam.get());
        Object msgs = m.remove("messages");
        if (msgs instanceof List<?> l) {
            List<Map<String, Object>> slim = new ArrayList<>();
            for (Object o : l) {
                Map<String, Object> mm = (Map<String, Object>) o;
                Map<String, Object> s = new LinkedHashMap<>();
                for (String k : List.of("id", "createdAt", "direction", "msgType", "senderBic", "receiverBic", "txRef", "relatedRef", "status", "statusReason", "businessStatus", "businessReason", "businessReject", "inReplyTo", "scenarioId", "raw"))
                    if (mm.get(k) != null) s.put(k, mm.get(k));
                slim.add(s);
            }
            m.put("messages", slim);
        }
        String status = String.valueOf(m.get("status"));
        Map<String, Object> sm = productMachine(fam.get());
        if (sm != null) {
            Map<String, Object> st = (Map<String, Object>) ((Map<String, Object>) sm.get("statuses")).get(status);
            if (st != null) { m.put("status_zh", st.get("zh")); m.put("expectedNext", st.get("expectedNext")); }
            m.put("terminal", ((List<String>) sm.get("terminal")).contains(status));
        }
        m.put("businessDate", clock.today().toString());
        m.put("pendingReview", reviewItems.findByTxRefOrderByIdAsc(ref).stream().filter(i -> "PENDING_REVIEW".equals(i.getStatus())).map(ReviewItem::getId).toList());
        m.put("scheduledResponses", engine.listPending(false).stream().filter(p -> ref.equals(p.getRef())).map(p -> Map.of("id", p.getId(), "template", String.valueOf(p.getTemplate()), "dueAt", String.valueOf(p.getDueAt()))).toList());
        return Optional.of(m);
    }

    private static final Map<String, List<String>> EXPECTS = Map.ofEntries(
            Map.entry("MT760", List.of("MT768", "MT730", "MT759", "MT799")), Map.entry("MT767", List.of("MT787", "MT768", "MT759")),
            Map.entry("MT765", List.of("MT786", "MT202", "MT103", "MT769", "MT767", "MT759")), Map.entry("MT700", List.of("MT730", "MT799", "MT759")),
            Map.entry("MT707", List.of("MT730", "MT799", "MT759")), Map.entry("MT754", List.of("MT752", "MT734", "MT756", "MT202", "MT759")),
            Map.entry("MT750", List.of("MT752", "MT734", "MT759")), Map.entry("MT742", List.of("MT756", "MT744", "MT202")),
            Map.entry("MT400", List.of("MT410", "MT499", "MT412", "MT416")), Map.entry("MT420", List.of("MT422", "MT499")),
            Map.entry("MT430", List.of("MT410", "MT499")));
    private static final Set<String> AWAIT = Set.of("AMENDMENT_PENDING", "DEMAND_RECEIVED", "EXTEND_OR_PAY_PENDING", "DOCS_PRESENTED", "DISCREPANT", "TRACER", "PRE_ADVISED", "APPLIED");

    /** Consistency / completeness check of one business flow: returns findings with severity, code, message and evidence (message ids). */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> reconcile(String ref) {
        Optional<Map<String, Object>> ob = business(ref, false);
        if (ob.isEmpty()) return Optional.empty();
        Map<String, Object> b = ob.get();
        String fam = (String) b.get("family");
        String status = String.valueOf(b.get("status"));
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) b.getOrDefault("messages", List.of());
        List<Map<String, Object>> f = new ArrayList<>();
        LocalDate today = clock.today();
        // known references of this flow
        Set<String> known = new HashSet<>();
        known.add(ref);
        if (b.get("ref") != null) known.add(String.valueOf(b.get("ref")));
        for (String k : List.of("collectingRef", "advisingRef", "presentationRef")) if (b.get(k) != null) known.add(String.valueOf(b.get(k)));
        for (String coll : List.of("demands", "presentations", "amendments"))
            for (Map<String, Object> it : (List<Map<String, Object>>) b.getOrDefault(coll, List.of()))
                for (String k : List.of("demandRef", "presentationRef")) if (it.get(k) != null) known.add(String.valueOf(it.get(k)));
        Set<String> seenRefs = new HashSet<>(known);
        Map<String, Long> firstByKey = new HashMap<>();
        for (Map<String, Object> m : msgs) {
            Long id = ((Number) m.get("id")).longValue();
            String dir = String.valueOf(m.get("direction"));
            String type = String.valueOf(m.get("msgType"));
            if ("SYS".equals(dir)) continue;
            if ("NAK".equals(m.get("status")))
                f.add(finding("ERROR", "REC-NAK", type + " #" + id + " was NAKed by the gateway: " + m.get("statusReason"), List.of(id)));
            else if ("ACK".equals(m.get("status")) && m.get("createdAt") != null && instant(m.get("createdAt")).isBefore(Instant.now().minusSeconds(60)))
                f.add(finding("WARN", "REC-UNDELIVERED", type + " #" + id + " ACKed but still not delivered after 60s", List.of(id)));
            String bs = (String) m.get("businessStatus");
            if ("REJECTED".equals(bs) || "INCONSISTENT".equals(bs)) {
                Map<String, Object> x = finding("ERROR", "REC-BIZ-" + bs, type + " #" + id + " " + bs.toLowerCase() + " at business level: " + m.get("businessReason"), List.of(id));
                Matcher cm = CODE.matcher(String.valueOf(m.get("businessReason")));
                if (cm.matches()) x.put("ruleCode", cm.group(1));
                f.add(x);
            }
            if (Boolean.TRUE.equals(m.get("businessReject")))
                f.add(finding("WARN", "REC-BIZREJECT-SENT", type + " #" + id + " is a business reject sent by " + m.get("senderBic"), List.of(id)));
            String rr = (String) m.get("relatedRef"), tr = (String) m.get("txRef");
            // reference chaining: the message must carry (in 20/21/23 as parsed) the business reference or a reference seen earlier in this flow
            if (!"NAK".equals(m.get("status")) && (tr == null || !seenRefs.contains(tr)) && (rr == null || !seenRefs.contains(rr)))
                f.add(finding("WARN", "REC-REF21", type + " #" + id + " references (20='" + tr + "', 21='" + rr + "') do not chain to the business reference or any earlier reference of this flow", List.of(id)));
            if (tr != null) seenRefs.add(tr);
            if (rr != null && !"NONREF".equalsIgnoreCase(rr)) seenRefs.add(rr);
            if (!"NAK".equals(m.get("status"))) {
                String key = m.get("senderBic") + "|" + type + "|" + tr + "|" + rr;
                Long prev = firstByKey.putIfAbsent(key, id);
                if (prev != null && !Boolean.TRUE.equals(m.get("possibleDuplicate")))
                    f.add(finding("WARN", "REC-DUP", type + " #" + id + " repeats #" + prev + " (same sender, type, 20 and 21)", List.of(prev, id)));
            }
            // response expected?
            List<String> exp = EXPECTS.get(type);
            if (exp != null && "DELIVERED".equals(m.get("status")) && !"REJECTED".equals(bs) && !Boolean.TRUE.equals(m.get("businessReject"))) {
                boolean answered = false;
                for (Map<String, Object> r : msgs) {
                    if (((Number) r.get("id")).longValue() <= id || "SYS".equals(r.get("direction"))) continue;
                    if (!String.valueOf(r.get("senderBic")).equals(m.get("receiverBic"))) continue;
                    if (id.equals(num(r.get("inReplyTo"))) || exp.contains(String.valueOf(r.get("msgType"))) && (tr == null || tr.equals(r.get("relatedRef")) || ref.equals(r.get("relatedRef")) || ref.equals(r.get("txRef")))) { answered = true; break; }
                }
                if (!answered) {
                    boolean sutMustAnswer = cfg.byBic(String.valueOf(m.get("receiverBic"))).map(Participant::isSut).orElse(false);
                    long age = m.get("createdAt") == null ? 0 : ChronoUnit.MINUTES.between(instant(m.get("createdAt")), Instant.now());
                    f.add(finding(sutMustAnswer && age >= 10 ? "WARN" : "INFO", "REC-NORESP",
                            type + " #" + id + " has no response yet from " + m.get("receiverBic") + (sutMustAnswer ? " (your system)" : "") + "; expected one of " + exp + "; age " + age + " min", List.of(id)));
                }
            }
        }
        // amounts and balances
        BigDecimal amount = dec(b.get("amount")), outstanding = dec(b.get("outstanding"));
        if ("UNDERTAKING".equals(fam)) {
            if (outstanding != null && outstanding.signum() < 0) f.add(finding("ERROR", "REC-BAL-NEG", "outstanding " + outstanding + " is negative", null));
            if (outstanding != null && amount != null && outstanding.compareTo(amount) > 0) f.add(finding("ERROR", "REC-BAL-OVER", "outstanding " + outstanding + " exceeds amount " + amount, null));
            List<Map<String, Object>> mv = (List<Map<String, Object>>) b.getOrDefault("movements", List.of());
            if (!mv.isEmpty() && outstanding != null) {
                BigDecimal last = dec(mv.get(mv.size() - 1).get("outstandingAfter"));
                if (last != null && last.compareTo(outstanding) != 0)
                    f.add(finding("ERROR", "REC-BAL-MOVE", "last movement outstandingAfter " + last + " differs from outstanding " + outstanding, idsOf(mv.get(mv.size() - 1))));
            }
            for (Map<String, Object> d : (List<Map<String, Object>>) b.getOrDefault("demands", List.of())) {
                BigDecimal da = dec(d.get("amount"));
                if (da != null && amount != null && da.compareTo(amount) > 0)
                    f.add(finding("WARN", "REC-DEMAND-AMT", "demand " + d.get("demandRef") + " amount " + da + " exceeds undertaking amount " + amount, idsOf(d)));
            }
            seqCheck((List<Map<String, Object>>) b.getOrDefault("amendments", List.of()), f);
        } else if ("LC".equals(fam)) {
            BigDecimal max = dec(b.get("maxAmount")), used = dec(b.get("utilised"));
            if (max != null && used != null && used.compareTo(max) > 0) f.add(finding("ERROR", "REC-LC-OVERDRAWN", "utilised " + used + " exceeds max amount " + max + " (incl. tolerance)", null));
            if (outstanding != null && outstanding.signum() < 0) f.add(finding("ERROR", "REC-BAL-NEG", "available " + outstanding + " is negative", null));
            BigDecimal ra = dec(b.get("reimbAmount")), rc = dec(b.get("reimbClaimed"));
            if (ra != null && rc != null && rc.compareTo(ra) > 0) f.add(finding("ERROR", "REC-LC-REIMB", "reimbursement claimed " + rc + " exceeds authorised " + ra, null));
            for (Map<String, Object> p : (List<Map<String, Object>>) b.getOrDefault("presentations", List.of())) {
                if ("PRESENTED".equals(p.get("status")) || "DISCREPANT".equals(p.get("status"))) {
                    LocalDate on = p.get("presentedOn") == null ? null : LocalDate.parse(String.valueOf(p.get("presentedOn")));
                    if (on != null && bankingDays(on, today) > 5)
                        f.add(finding("WARN", "REC-L06-EXAM", "presentation " + p.get("presentationRef") + " presented " + on + " is still " + p.get("status") + " after more than 5 banking days (UCP 600 art. 14b/16d)", idsOf(p)));
                }
                BigDecimal pa = dec(p.get("amount")), pp = dec(p.get("paidAmount"));
                if (pa != null && pp != null && pp.compareTo(pa) > 0) f.add(finding("ERROR", "REC-LC-PAID", "presentation " + p.get("presentationRef") + " paid " + pp + " exceeds presented " + pa, idsOf(p)));
            }
            seqCheck((List<Map<String, Object>>) b.getOrDefault("amendments", List.of()), f);
        } else if ("COLL".equals(fam)) {
            BigDecimal paid = dec(b.get("paidAmount"));
            if (amount != null && paid != null && outstanding != null && paid.add(outstanding).compareTo(amount) != 0)
                f.add(finding("ERROR", "REC-COLL-BAL", "paid " + paid + " + outstanding " + outstanding + " != amount " + amount, null));
            BigDecimal acc = dec(b.get("acceptedAmount"));
            if (acc != null && amount != null && acc.compareTo(amount) > 0) f.add(finding("ERROR", "REC-COLL-ACC", "accepted " + acc + " exceeds amount " + amount, null));
        }
        // dates / lifecycle
        boolean terminal = Boolean.TRUE.equals(b.get("terminal"));
        Object exp = b.get("expiryDate");
        if (exp != null && !terminal && !"COLL".equals(fam)) {
            LocalDate e = LocalDate.parse(String.valueOf(exp));
            if (today.isAfter(e)) f.add(finding("WARN", "REC-EXPIRY", "expiry date " + e + " passed (business date " + today + ") but status is " + status + " (pending item or sweep not run)", null));
            else if (ChronoUnit.DAYS.between(today, e) <= 7) f.add(finding("INFO", "REC-EXPIRY-SOON", "expires in " + ChronoUnit.DAYS.between(today, e) + " day(s) on " + e, null));
        }
        if (AWAIT.contains(status)) f.add(finding("INFO", "REC-AWAITING", "status " + status + ": a response is outstanding - expected next " + b.get("expectedNext"), null));
        for (Object h : (List<Object>) b.getOrDefault("history", List.of())) {
            Map<String, Object> e = (Map<String, Object>) h;
            if (String.valueOf(e.get("eventType")).contains("INCONSISTENT"))
                f.add(finding("WARN", "REC-EVENT-INCONSISTENT", "ledger event " + e.get("eventType") + ": " + e.get("detail"), idsOf(e)));
        }
        List<?> pr = (List<?>) b.get("pendingReview");
        if (pr != null && !pr.isEmpty()) f.add(finding("INFO", "REC-REVIEW", "review items waiting for an operator decision: " + pr, null));
        List<?> sr = (List<?>) b.get("scheduledResponses");
        if (sr != null && !sr.isEmpty()) f.add(finding("INFO", "REC-SCHEDULED", sr.size() + " simulator response(s) scheduled", null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", ref);
        out.put("family", fam);
        out.put("status", status);
        out.put("status_zh", b.get("status_zh"));
        out.put("currency", b.get("currency"));
        out.put("amount", b.get("amount"));
        out.put("outstanding", b.get("outstanding"));
        out.put("expiryDate", exp);
        out.put("businessDate", today.toString());
        out.put("messageCount", msgs.stream().filter(m -> !"SYS".equals(m.get("direction"))).count());
        out.put("expectedNext", b.get("expectedNext"));
        Map<String, Long> cnt = new LinkedHashMap<>();
        for (String s : List.of("ERROR", "WARN", "INFO")) cnt.put(s, f.stream().filter(x -> s.equals(x.get("severity"))).count());
        out.put("summary", Map.of("ok", cnt.get("ERROR") == 0, "counts", cnt,
                "text", cnt.get("ERROR") == 0 ? (cnt.get("WARN") == 0 ? "consistent" : "consistent with warnings") : "inconsistencies found",
                "text_zh", cnt.get("ERROR") == 0 ? (cnt.get("WARN") == 0 ? "一致" : "基本一致（有警告）") : "发现不一致"));
        out.put("findings", f);
        out.put("timeline", msgs.stream().filter(m -> !"SYS".equals(m.get("direction"))).map(m -> {
            Map<String, Object> t = new LinkedHashMap<>();
            for (String k : List.of("id", "createdAt", "direction", "msgType", "senderBic", "receiverBic", "txRef", "relatedRef", "status", "businessStatus")) if (m.get(k) != null) t.put(k, m.get(k));
            return t;
        }).toList());
        return Optional.of(out);
    }

    private static void seqCheck(List<Map<String, Object>> am, List<Map<String, Object>> f) {
        List<Integer> seq = am.stream().map(a -> a.get("seqNo")).filter(Objects::nonNull).map(o -> ((Number) o).intValue()).sorted().toList();
        for (int i = 0; i < seq.size(); i++)
            if (seq.get(i) != i + 1) { f.add(finding("WARN", "REC-AMD-SEQ", "amendment sequence numbers not contiguous: " + seq, null)); return; }
    }

    private static int bankingDays(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1))
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) n++;
        return n;
    }

    private static List<Long> idsOf(Map<String, Object> m) {
        List<Long> l = new ArrayList<>();
        for (String k : List.of("messageId", "resolutionMessageId", "responseMessageId")) if (m.get(k) instanceof Number n) l.add(n.longValue());
        return l;
    }

    private static Long num(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static Instant instant(Object o) {
        if (o instanceof Number n) return Instant.ofEpochMilli((long) (n.doubleValue() * 1000));
        return Instant.parse(String.valueOf(o));
    }

    private static BigDecimal dec(Object o) {
        if (o == null) return null;
        try { return new BigDecimal(String.valueOf(o).replace(',', '.')); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------------------------------------ lists / dashboard / reference

    public List<Map<String, Object>> businessList(String family, String status, String party) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (blank(family) || "UNDERTAKING".equalsIgnoreCase(family) || "GUARANTEE".equalsIgnoreCase(family))
            for (Map<String, Object> m : ledger.list(blank(status) ? null : status, blank(party) ? null : party)) { m.put("family", "UNDERTAKING"); out.add(m); }
        for (String fam : List.of("LC", "COLL"))
            if (blank(family) || fam.equalsIgnoreCase(family))
                for (Map<String, Object> m : ledger.tradeList(fam, blank(status) ? null : status, blank(party) ? null : party)) { Map<String, Object> x = new LinkedHashMap<>(m); x.put("family", fam); out.add(x); }
        return out;
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("now", Instant.now().toString());
        d.put("businessDate", clock.today().toString());
        d.put("businessDateOverride", clock.getOverride() == null ? null : clock.getOverride().toString());
        d.put("sessions", sessions.all());
        d.put("messages", Map.of("total", messages.count(),
                "nak", messages.count((root, q, cb) -> cb.equal(root.get("status"), "NAK")),
                "businessRejected", messages.count((root, q, cb) -> root.get("businessStatus").in("REJECTED", "INCONSISTENT"))));
        Map<String, Object> led = new LinkedHashMap<>();
        for (String fam : List.of("UNDERTAKING", "LC", "COLL")) {
            List<Map<String, Object>> l = businessList(fam, null, null);
            Map<String, Long> byStatus = new TreeMap<>();
            for (Map<String, Object> m : l) byStatus.merge(String.valueOf(m.get("status")), 1L, Long::sum);
            led.put(fam, Map.of("total", l.size(), "byStatus", byStatus));
        }
        d.put("ledgers", led);
        d.put("reviewPending", reviewItems.findByStatusOrderByIdAsc("PENDING_REVIEW").size());
        List<PendingResponse> pend = engine.listPending(false);
        d.put("scheduledResponses", pend.size());
        d.put("activeScenarios", cfg.activeMap());
        d.put("participants", cfg.getConfig().getParticipants().size());
        d.put("reviewModeParticipants", cfg.getConfig().getParticipants().stream().filter(reviewMode::isReviewMode).map(Participant::getId).toList());
        d.put("recent", search(new MessageQuery(null, null, null, null, null, null, null, null, null, null, null, null, null, false, 15)).stream().map(this::summary).toList());
        return d;
    }

    public List<Map<String, Object>> scenarios() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Scenario s : cfg.getScenarios()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            m.put("group", s.getGroup());
            m.put("description", s.getDescription());
            m.put("active", cfg.isActive(s));
            m.put("fallback", s.isFallback());
            m.put("hasKickoff", !s.getKickoff().isEmpty());
            m.put("reactsTo", s.getReactions().stream().flatMap(r -> r.getMessageTypes().stream()).distinct().toList());
            list.add(m);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> stateMachines() {
        if (stateMachines == null) stateMachines = resource("ai/state-machines.json");
        return stateMachines;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> productMachine(String fam) {
        return (Map<String, Object>) ((Map<String, Object>) stateMachines().get("products")).get(fam);
    }

    public Map<String, Object> resource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return json.readValue(in, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + path + ": " + e.getMessage());
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
