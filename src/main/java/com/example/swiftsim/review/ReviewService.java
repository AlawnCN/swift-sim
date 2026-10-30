package com.example.swiftsim.review;

import com.example.swiftsim.config.ConfigService;
import com.example.swiftsim.config.Participant;
import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.domain.MessageRepository;
import com.example.swiftsim.gateway.GatewayService;
import com.example.swiftsim.gateway.SubmitOptions;
import com.example.swiftsim.gateway.SubmitResult;
import com.example.swiftsim.ledger.LedgerService;
import com.example.swiftsim.parse.ParsedMessage;
import com.example.swiftsim.scenario.ScenarioEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Manual / AI operator for a virtual bank in review mode: turns a decision (ACCEPT, REFUSE, PAY, EXTEND, ACK,
 * BUSINESS_REJECT, SCENARIO, CUSTOM, DISMISS) into the corresponding SWIFT message(s) sent by that bank.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {
    private final ReviewItemRepository repo;
    private final MessageRepository messages;
    private final ConfigService cfg;
    private final GatewayService gateway;
    private final ScenarioEngine engine;
    private final LedgerService ledger;

    public List<ReviewItem> list(String status, String participant) {
        List<ReviewItem> l = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
                ? repo.findTop500ByOrderByIdDesc() : repo.findByStatusOrderByIdAsc(status.toUpperCase());
        if (participant != null && !participant.isBlank())
            l = l.stream().filter(i -> participant.equalsIgnoreCase(i.getParticipantId()) || participant.equalsIgnoreCase(i.getParticipantBic())).toList();
        return l;
    }

    public Map<String, Object> detail(Long id) {
        ReviewItem it = repo.findById(id).orElseThrow(() -> new NoSuchElementException("unknown review item " + id));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", it);
        messages.findById(it.getMessageId()).ifPresent(r -> m.put("message", r));
        String lref = messages.findById(it.getMessageId()).map(MessageRecord::getUndertakingRef).orElse(it.getTxRef());
        ledger.contextAny(lref != null ? lref : it.getTxRef()).ifPresent(c -> m.put("LC".equals(c.get("ldg_family")) ? "documentaryCredit" : "COLL".equals(c.get("ldg_family")) ? "collection" : "undertaking", c));
        m.put("actions", Arrays.asList(it.getSuggestedActions().split(",")));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<String> reasons(Object o) {
        if (o == null) return new ArrayList<>();
        if (o instanceof Collection<?> c) return c.stream().map(String::valueOf).toList();
        return new ArrayList<>(Arrays.asList(String.valueOf(o).split("\\R")));
    }

    /** Draft of what {@link #respond} would send for this decision - nothing is submitted, scheduled or saved. */
    public synchronized Map<String, Object> preview(Long id, Map<String, Object> body) {
        String action = String.valueOf(body.getOrDefault("action", "")).toUpperCase();
        if (Set.of("SCENARIO", "DISMISS").contains(action)) throw new IllegalArgumentException("preview is not available for " + action);
        Map<String, Object> res = new LinkedHashMap<>();
        if ("CUSTOM".equals(action)) {
            res.put("preview", true);
            res.put("action", action);
            res.put("drafts", List.of(Map.of("raw", String.valueOf(body.getOrDefault("raw", "")))));
            return res;
        }
        List<Map<String, Object>> drafts = engine.preview(() -> respond0(id, body, true));
        res.put("preview", true);
        res.put("action", action);
        res.put("drafts", drafts);
        return res;
    }

    public synchronized Map<String, Object> respond(Long id, Map<String, Object> body) {
        return respond0(id, body, false);
    }

    private Map<String, Object> respond0(Long id, Map<String, Object> body, boolean dry) {
        ReviewItem it = repo.findById(id).orElseThrow(() -> new NoSuchElementException("unknown review item " + id));
        if (!"PENDING_REVIEW".equals(it.getStatus())) throw new IllegalStateException("review item " + id + " is already " + it.getStatus());
        String action = String.valueOf(body.getOrDefault("action", "")).toUpperCase();
        if (action.isBlank()) throw new IllegalArgumentException("action required: " + it.getSuggestedActions());
        MessageRecord rec = messages.findById(it.getMessageId()).orElseThrow();
        ParsedMessage pm = gateway.parse(rec.getRaw(), null, null);
        pm.setSender(rec.getSenderBic());
        pm.setReceiver(rec.getReceiverBic());
        Participant me = cfg.byId(it.getParticipantId()).orElseThrow();
        Participant other = cfg.byBic(rec.getSenderBic()).orElseThrow();
        boolean mx = "MX".equals(rec.getFormat());
        Map<String, String> base = engine.baseVars(pm, other, me);
        ledger.contextAny(rec.getUndertakingRef() != null ? rec.getUndertakingRef() : pm.getTxRef()).ifPresent(base::putAll);
        base.put("inboundId", String.valueOf(rec.getId()));
        List<String> rs = reasons(body.get("reasons"));
        if (rs.isEmpty() && it.getLedgerErrors() != null) rs = Arrays.asList(it.getLedgerErrors().split("\n"));
        if (rs.isEmpty()) rs = List.of("REFUSED BY " + me.getName().toUpperCase());
        if (body.get("amount") != null) {
            String a = String.valueOf(body.get("amount")).replace(',', '.');
            base.put("amountIso", new java.math.BigDecimal(a).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            base.put("amount", base.get("amountIso").replace('.', ','));
        }
        if (body.get("newExpiry") != null) {
            LocalDate d = LocalDate.parse(String.valueOf(body.get("newExpiry")));
            base.put("newExpiryIso", d.toString());
            base.put("newExpiry6", d.format(DateTimeFormatter.ofPattern("yyMMdd")));
        }
        LedgerService.Action type = LedgerService.classify(pm.getShortType());
        List<Map<String, Object>> sent = new ArrayList<>();
        String label = "review:" + me.getId();
        String fam = base.getOrDefault("ldg_family", ScenarioEngine.isCollInstruction(pm) ? "COLL" : "");
        if (("LC".equals(fam) || "COLL".equals(fam)) && !Set.of("DISMISS", "BUSINESS_REJECT", "SCENARIO", "CUSTOM").contains(action)) {
            tradeAction(action, fam, pm, base, rs, me, other, rec, label, sent);
            action = action + "";
        } else switch (action) {
            case "DISMISS" -> { }
            case "ACK" -> sent.add(send(label, mx ? "tsrv019-status.xml" : "mt768-ack.fin", base, Map.of("statusCategory", category(type), "status", "ACCP",
                    "info", "ACKNOWLEDGED BY " + me.getName().toUpperCase()), me, other, rec));
            case "ACCEPT" -> {
                switch (type) {
                    case AMEND -> sent.add(send(label, mx ? "tsrv008-response.xml" : "mt787-response.fin", base, Map.of("status", "ACPT", "mxStatus", "ACCP"), me, other, rec));
                    case EOP_REQUEST -> sent.add(send(label, "tsrv015-extend.xml", base, Map.of("mxStatus", "EXTD"), me, other, rec));
                    case DEMAND -> pay(label, base, mx, me, other, rec, sent);
                    default -> sent.add(send(label, mx ? "tsrv019-status.xml" : "mt768-ack.fin", base, Map.of("statusCategory", category(type), "status", "ACCP",
                            "info", "ACCEPTED BY " + me.getName().toUpperCase()), me, other, rec));
                }
            }
            case "REFUSE", "REJECT" -> {
                switch (type) {
                    case AMEND -> sent.add(send(label, mx ? "tsrv008-response.xml" : "mt787-response.fin", base,
                            Map.of("status", "REJT\n" + ScenarioEngine.wrap(String.join("\n", rs), 35, 6, false), "mxStatus", "REJT"), me, other, rec));
                    case DEMAND -> sent.add(send(label, mx ? "tsrv016-refusal.xml" : "mt786-refusal.fin", base,
                            Map.of("reason", mx ? String.join("; ", rs) : ScenarioEngine.wrap(String.join("\n", rs), 50, 70, true)), me, other, rec));
                    default -> businessReject(rec, pm, me, other, rs, base, sent);
                }
            }
            case "PAY" -> {
                if (type == LedgerService.Action.EOP_REQUEST) sent.add(send(label, "tsrv015-extend.xml", base, Map.of("mxStatus", "PAYD"), me, other, rec));
                else if (type == LedgerService.Action.DEMAND) pay(label, base, mx, me, other, rec, sent);
                else throw new IllegalArgumentException("PAY only applies to a demand (MT765/tsrv.013) or tsrv.014");
            }
            case "EXTEND" -> {
                if (type == LedgerService.Action.EOP_REQUEST) sent.add(send(label, "tsrv015-extend.xml", base, Map.of("mxStatus", "EXTD"), me, other, rec));
                else if (type == LedgerService.Action.DEMAND) sent.add(send(label, mx ? "tsrv005-extension.xml" : "mt767-extension.fin", base, Map.of(), me, other, rec));
                else throw new IllegalArgumentException("EXTEND only applies to an extend-or-pay demand or tsrv.014");
            }
            case "BUSINESS_REJECT" -> businessReject(rec, pm, me, other, rs, base, sent);
            case "SCENARIO" -> {
                String sc = String.valueOf(body.getOrDefault("scenario", ""));
                if (!engine.runScenarioFor(sc, rec, pm, me)) throw new IllegalArgumentException("scenario " + sc + " has no reaction for " + pm.getShortType() + " to " + me.getId());
                sent.add(Map.of("scenario", sc, "note", "scenario reaction scheduled (see /api/pending-responses)"));
            }
            case "CUSTOM" -> {
                String raw = String.valueOf(body.getOrDefault("raw", "")).strip();
                if (raw.isBlank()) throw new IllegalArgumentException("CUSTOM requires 'raw' (full MT/MX, or MT block 4 with 'mtType')");
                SubmitOptions.SubmitOptionsBuilder ob = SubmitOptions.builder().channel("REVIEW").producedBy(label).inReplyTo(rec.getId()).depth(1);
                if (raw.startsWith(":")) {
                    if (body.get("mtType") == null) throw new IllegalArgumentException("bare block 4 requires mtType");
                    ob.from(me.getId()).to(other.getId()).mtType(String.valueOf(body.get("mtType")));
                } else {
                    ParsedMessage p2 = gateway.parse(raw.replace("\r\n", "\n"), null, null);
                    if ("MX".equals(p2.getFormat()) && p2.getSender() == null) ob.from(me.getId()).to(other.getId());
                    else if (p2.getSender() != null && !p2.getSender().equals(me.bic11()))
                        throw new IllegalArgumentException("CUSTOM message sender " + p2.getSender() + " is not the reviewed bank " + me.bic11());
                }
                sent.add(result(gateway.submit(raw, ob.build())));
            }
            default -> throw new IllegalArgumentException("unknown action " + action + "; allowed: " + it.getSuggestedActions());
        }
        if (dry) return Map.of("sent", sent);
        it.setStatus("DISMISS".equals(action) ? "DISMISSED" : "RESPONDED");
        it.setResponseAction(action);
        it.setRespondedAt(Instant.now());
        it.setOperatorNote(body.get("note") == null ? null : String.valueOf(body.get("note")));
        StringBuilder ids = new StringBuilder();
        for (Map<String, Object> s : sent) if (s.get("messageId") != null) ids.append(ids.isEmpty() ? "" : ",").append(s.get("messageId"));
        it.setResponseMessageIds(ids.toString());
        repo.save(it);
        gateway.note(it.getTxRef(), label, rec.getId(), "operator decision on review item #" + it.getId() + ": " + action);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("item", it);
        res.put("sent", sent);
        return res;
    }

    /** review decisions for documentary credits (MT730/752/734/756/744/202) and collections (MT410/412/416/400/202) */
    private void tradeAction(String action, String fam, ParsedMessage pm, Map<String, String> base, List<String> rs, Participant me, Participant other,
                             MessageRecord rec, String label, List<Map<String, Object>> sent) {
        String t = pm.getShortType();
        String why = ScenarioEngine.wrap(String.join("\n", rs), 50, 70, true);
        if ("LC".equals(fam)) {
            boolean pres = Set.of("MT754", "MT750").contains(t);
            switch (action) {
                case "ACK" -> sent.add(send(label, Set.of("MT700", "MT705", "MT707", "MT710", "MT720", "MT740", "MT747").contains(t) ? "mt730-ack.fin" : "mt799-free.fin", base,
                        Map.of("narrative", "YOUR MT" + t.replace("MT", "") + " RECEIVED AND NOTED"), me, other, rec));
                case "CONFIRM" -> sent.add(send(label, "mt730-ack.fin", base, Map.of("ackLine", "/CONFIRMED/"), me, other, rec));
                case "ACCEPT" -> {
                    if ("MT707".equals(t)) sent.add(send(label, "mt730-ack.fin", base, Map.of("ackLine", "/BENACC/"), me, other, rec));
                    else if (pres) sent.add(send(label, "mt752-authorisation.fin", base, Map.of("authCode", "ACCEPT"), me, other, rec));
                    else sent.add(send(label, "mt730-ack.fin", base, Map.of(), me, other, rec));
                }
                case "REFUSE", "REJECT" -> {
                    if ("MT707".equals(t)) sent.add(send(label, "mt730-ack.fin", base, Map.of("ackLine", "/BENREJ/" + rs.get(0).replaceAll("[^A-Za-z0-9 .,-]", " ")), me, other, rec));
                    else if (pres) sent.add(send(label, "mt734-refusal.fin", base, Map.of("reason", why), me, other, rec));
                    else if ("MT742".equals(t)) sent.add(send(label, "mt744-claim-reject.fin", base, Map.of("reason", ScenarioEngine.wrap(String.join("\n", rs), 50, 20, true)), me, other, rec));
                    else businessReject(rec, pm, me, other, rs, base, sent);
                }
                case "PAY" -> {
                    if (pres) {
                        sent.add(send(label, "mt756-reimbursement.fin", base, Map.of(), me, other, rec));
                        Long pid = engine.sendLater("REVIEW", label, "mt202-lc-settlement.fin", base, Map.of(), me, other, rec.getId(), 2);
                        sent.add(Map.of("template", "mt202-lc-settlement.fin", "pendingResponseId", pid == null ? "" : pid, "note", "sent in ~2s"));
                    } else if ("MT742".equals(t)) sent.add(send(label, "mt202-lc-settlement.fin", base, Map.of(), me, other, rec));
                    else throw new IllegalArgumentException("PAY applies to MT754 / MT750 presentations and MT742 claims");
                }
                default -> throw new IllegalArgumentException("unknown action " + action + " for " + pm.getMsgType());
            }
            return;
        }
        boolean instr = ScenarioEngine.isCollInstruction(pm);
        switch (action) {
            case "ACK" -> sent.add(send(label, instr ? "mt410-ack.fin" : "MT420".equals(t) ? "mt422-fate.fin" : "mt499-free.fin", base,
                    Map.of("narrative", "YOUR " + pm.getMsgType() + " RECEIVED AND NOTED BY " + me.getName().toUpperCase()), me, other, rec));
            case "ACCEPT" -> sent.add(send(label, "mt412-acceptance.fin", base, Map.of(), me, other, rec));
            case "REFUSE", "REJECT" -> sent.add(send(label, "mt416-non-payment.fin", base, Map.of("reason", ScenarioEngine.wrap(String.join("\n", rs), 35, 20, false)), me, other, rec));
            case "PAY" -> {
                sent.add(send(label, "mt400-payment.fin", base, Map.of(), me, other, rec));
                Long pid = engine.sendLater("REVIEW", label, "mt202-coll-cover.fin", base, Map.of(), me, other, rec.getId(), 2);
                sent.add(Map.of("template", "mt202-coll-cover.fin", "pendingResponseId", pid == null ? "" : pid, "note", "sent in ~2s"));
            }
            default -> throw new IllegalArgumentException("unknown action " + action + " for " + pm.getMsgType());
        }
    }

    private void pay(String label, Map<String, String> base, boolean mx, Participant me, Participant other, MessageRecord rec, List<Map<String, Object>> sent) {
        if (mx) {
            sent.add(send(label, "tsrv019-status.xml", base, Map.of("statusCategory", "DMND", "status", "ACCP", "info", "DEMAND HONOURED - PAYMENT EXECUTED"), me, other, rec));
        } else {
            sent.add(send(label, "mt202-settlement.fin", base, Map.of(), me, other, rec));
            Long pid = engine.sendLater("REVIEW", label, "mt769-reduction.fin", base, Map.of(), me, other, rec.getId(), 2);
            sent.add(Map.of("template", "mt769-reduction.fin", "pendingResponseId", pid == null ? "" : pid, "note", "sent in ~2s"));
        }
    }

    private void businessReject(MessageRecord rec, ParsedMessage pm, Participant me, Participant other, List<String> rs, Map<String, String> base, List<Map<String, Object>> sent) {
        List<Long> ids = engine.sendBusinessReject(rec, pm, me, other, rs, base, 0, "REVIEW_REJECT");
        sent.add(Map.of("template", "MX".equals(rec.getFormat()) ? "tsrv019-status.xml (REJT)" : "COLL".equals(base.get("ldg_family")) ? "mt499-business-reject.fin" : "mt759-business-reject.fin", "pendingResponseIds", ids));
    }

    private Map<String, Object> send(String label, String tpl, Map<String, String> base, Map<String, String> vars, Participant me, Participant other, MessageRecord rec) {
        SubmitResult r = engine.sendNow("REVIEW", label, tpl, base, vars, me, other, rec.getId());
        Map<String, Object> m = result(r);
        m.put("template", tpl);
        return m;
    }

    private static Map<String, Object> result(SubmitResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r == null) { m.put("status", "NOT_SENT"); return m; }
        m.put("messageId", r.getId());
        m.put("msgType", r.getMsgType());
        m.put("status", r.getStatus());
        if (!r.getErrors().isEmpty()) m.put("errors", r.getErrors());
        return m;
    }

    private static String category(LedgerService.Action a) {
        return switch (a) {
            case ISSUE -> "ISSU";
            case APPLY -> "APPL";
            case DEMAND, DEMAND_WITHDRAWAL -> "DMND";
            case NON_EXT, NON_EXT_REQ -> "NEXT";
            case TERMINATION, REDUCTION -> "TERM";
            default -> "GENR";
        };
    }
}
