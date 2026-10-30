package com.example.swiftsim.scenario;

import com.example.swiftsim.config.ConfigService;
import com.example.swiftsim.config.Participant;
import com.example.swiftsim.config.Scenario;
import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.gateway.DeliveredEvent;
import com.example.swiftsim.gateway.GatewayService;
import com.example.swiftsim.gateway.SubmitOptions;
import com.example.swiftsim.gateway.SubmitResult;
import com.example.swiftsim.ledger.LedgerService;
import com.example.swiftsim.parse.ParsedMessage;
import com.example.swiftsim.review.ReviewItem;
import com.example.swiftsim.review.ReviewItemRepository;
import com.example.swiftsim.review.ReviewModeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prowidesoftware.swift.model.mx.AbstractMX;
import com.prowidesoftware.swift.model.mx.AppHdrFactory;
import com.prowidesoftware.swift.model.mx.BusinessAppHdrV02;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML-driven behaviour of the virtual banks: reacts to messages delivered to them (after the ledger check), sends
 * business rejects, queues messages for manual review, runs kickoff flows. Every delayed message is persisted
 * (PendingResponse) and resumed after a restart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioEngine {
    private final ConfigService cfg;
    private final GatewayService gateway;
    private final TaskScheduler scheduler;
    private final PendingResponseRepository pending;
    private final LedgerService ledger;
    private final ReviewModeService reviewMode;
    private final ReviewItemRepository reviews;
    private final ObjectMapper json = new ObjectMapper();

    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)}");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final AtomicInteger SEQ = new AtomicInteger(new Random().nextInt(9000));
    static final Scenario BUSINESS_REJECT = new Scenario();
    static final Scenario REVIEW = new Scenario();

    static {
        BUSINESS_REJECT.setId("business-reject");
        REVIEW.setId("manual-review");
    }

    record Match(Scenario scenario, Scenario.Reaction reaction) {}

    @EventListener
    public void onDelivered(DeliveredEvent ev) {
        ParsedMessage pm = ev.parsed();
        MessageRecord rec = ev.record();
        Optional<Participant> recv = cfg.byBic(rec.getReceiverBic());
        if (recv.isEmpty() || recv.get().isSut()) return;
        if (ev.options().isBusinessReject()) return; // a bank does not answer a business reject (avoids loops)
        LedgerService.Outcome out = ev.outcome();
        if (out != null && "DUPLICATE_IGNORED".equals(out.status())) {
            gateway.note(pm.getTxRef(), null, rec.getId(), recv.get().getId() + " recognised possible duplicate #" + rec.getId() + " of #" + rec.getDuplicateOf() + " - not processed again, no response");
            return;
        }
        Participant sender = cfg.byBic(rec.getSenderBic()).orElse(null);
        if (reviewMode.isReviewMode(recv.get())) {
            enqueueReview(rec, pm, recv.get(), out);
            return;
        }
        if (out != null && out.rejected()) {
            sendBusinessReject(rec, pm, recv.get(), sender, out.errors(), out.vars(), cfg.getConfig().getLedger().getRejectDelaySeconds(), "BUSINESS_REJECT");
            return;
        }
        if (ev.options().getDepth() >= cfg.getConfig().getGateway().getMaxDepth()) {
            gateway.note(pm.getTxRef(), null, rec.getId(), "max scenario depth reached - " + recv.get().getId() + " does not react");
            return;
        }
        List<Match> cands = new ArrayList<>();
        Map<String, String> condVars = new LinkedHashMap<>(pm.getVars());
        if (out != null) condVars.putAll(out.vars());
        for (Scenario s : cfg.getScenarios()) {
            for (Scenario.Reaction r : s.getReactions()) {
                if (typeMatches(r, pm) && toMatches(r, recv.get()) && whenMatches(r.getWhen(), condVars)) cands.add(new Match(s, r));
            }
        }
        Match chosen = null;
        String override = ev.options().getScenario();
        if (override != null && !override.isBlank()) {
            chosen = cands.stream().filter(m -> m.scenario().getId().equals(override)).findFirst().orElse(null);
            if (chosen == null) gateway.note(pm.getTxRef(), override, rec.getId(),
                    "Requested scenario '" + override + "' has no reaction for " + pm.getShortType() + " to " + recv.get().getId() + "; using active scenario");
        }
        if (chosen == null) chosen = cands.stream().filter(m -> !m.scenario().isFallback() && cfg.isActive(m.scenario())).findFirst().orElse(null);
        if (chosen == null) chosen = cands.stream().filter(m -> m.scenario().isFallback()).findFirst().orElse(null);
        if (chosen == null) {
            gateway.note(pm.getTxRef(), null, rec.getId(), "No scenario reaction for " + pm.getShortType() + " received by " + recv.get().getId());
            return;
        }
        runReaction(chosen.scenario(), chosen.reaction(), rec, pm, sender, recv.get(), out, ev.options().getDepth() + 1);
    }

    void runReaction(Scenario s, Scenario.Reaction r, MessageRecord rec, ParsedMessage pm, Participant sender, Participant recv,
                     LedgerService.Outcome out, int depth) {
        gateway.tagScenario(rec.getId(), s.getId());
        Map<String, String> base = baseVars(pm, sender, recv);
        if (out != null) base.putAll(out.vars());
        base.put("inboundId", String.valueOf(rec.getId()));
        for (Scenario.Step st : r.getRespond()) {
            schedule(s, st, base, "MT".equals(pm.getFormat()) ? "MT" : "MX", sender, recv, rec.getId(), depth, "SCENARIO");
        }
        if (r.getRespond().isEmpty()) {
            gateway.note(pm.getTxRef(), s.getId(), rec.getId(), "Scenario '" + s.getId() + "': no response will be sent (timeout / pending simulation)");
        }
    }

    /** Run a named scenario's reaction for a message (used by the review queue). */
    public boolean runScenarioFor(String scenarioId, MessageRecord rec, ParsedMessage pm, Participant recv) {
        Scenario s = cfg.scenario(scenarioId).orElseThrow(() -> new NoSuchElementException("unknown scenario " + scenarioId));
        Map<String, String> condVars = new LinkedHashMap<>(pm.getVars());
        String lref = rec.getUndertakingRef() != null ? rec.getUndertakingRef() : pm.getTxRef();
        Optional<Map<String, String>> lctx = ledger.contextAny(lref);
        lctx.ifPresent(condVars::putAll);
        for (Scenario.Reaction r : s.getReactions()) {
            if (typeMatches(r, pm) && toMatches(r, recv) && whenMatches(r.getWhen(), condVars)) {
                Participant sender = cfg.byBic(rec.getSenderBic()).orElse(null);
                LedgerService.Outcome out = lctx.map(c -> new LedgerService.Outcome("APPLIED", List.of(), c.get("ref"), null, c.get("ldg_status"), c)).orElse(null);
                runReaction(s, r, rec, pm, sender, recv, out, 1);
                return true;
            }
        }
        return false;
    }

    private void enqueueReview(MessageRecord rec, ParsedMessage pm, Participant recv, LedgerService.Outcome out) {
        ReviewItem it = new ReviewItem();
        it.setCreatedAt(Instant.now());
        it.setStatus("PENDING_REVIEW");
        it.setParticipantId(recv.getId());
        it.setParticipantBic(recv.bic11());
        it.setMessageId(rec.getId());
        it.setMsgType(rec.getMsgType());
        it.setFormat(rec.getFormat());
        it.setTxRef(rec.getTxRef());
        it.setSenderBic(rec.getSenderBic());
        it.setLedgerVerdict(out == null ? null : out.status());
        it.setLedgerErrors(out == null || out.errors().isEmpty() ? null : trunc(String.join("\n", out.errors()), 4000));
        it.setSuggestedActions(String.join(",", suggestedActions(pm, out)));
        reviews.save(it);
        gateway.note(pm.getTxRef(), REVIEW.getId(), rec.getId(), recv.getId() + " is in manual review mode: message #" + rec.getId()
                + " queued as review item #" + it.getId() + " (ledger verdict " + it.getLedgerVerdict() + ")");
    }

    static List<String> suggestedActions(ParsedMessage pm, LedgerService.Outcome out) {
        List<String> l = new ArrayList<>();
        if (out != null && out.rejected()) l.add("BUSINESS_REJECT");
        String fam = out == null || out.vars() == null ? null : out.vars().get("ldg_family");
        String t = pm.getShortType() == null ? "" : pm.getShortType();
        if ("LC".equals(fam) || LC_TYPES.contains(t)) {
            switch (t) {
                case "MT700", "MT705", "MT710", "MT720" -> l.addAll(List.of("ACK", "CONFIRM"));
                case "MT707" -> l.addAll(List.of("ACCEPT", "REFUSE", "ACK"));
                case "MT754", "MT750" -> l.addAll(List.of("PAY", "ACCEPT", "REFUSE"));
                case "MT742" -> l.addAll(List.of("PAY", "REFUSE"));
                case "MT734" -> l.addAll(List.of("ACK"));
                default -> l.add("ACK");
            }
        } else if ("COLL".equals(fam) || COLL_TYPES.contains(t) || isCollInstruction(pm)) {
            if (isCollInstruction(pm)) l.addAll(List.of("ACK", "PAY", "ACCEPT", "REFUSE"));
            else if ("MT420".equals(t)) l.addAll(List.of("ACK", "PAY", "REFUSE"));
            else if ("MT430".equals(t)) l.addAll(List.of("ACK", "PAY"));
            else l.add("ACK");
        } else switch (LedgerService.classify(pm.getShortType())) {
            case ISSUE, APPLY -> l.addAll(List.of("ACCEPT", "ACK"));
            case AMEND -> l.addAll(List.of("ACCEPT", "REFUSE", "ACK"));
            case DEMAND -> {
                l.addAll(List.of("PAY", "REFUSE"));
                if ("PAEX".equals(pm.getVars().get("demandType"))) l.add("EXTEND");
                l.add("ACK");
            }
            case EOP_REQUEST -> l.addAll(List.of("EXTEND", "PAY"));
            default -> l.add("ACK");
        }
        if (!l.contains("BUSINESS_REJECT")) l.add("BUSINESS_REJECT");
        l.addAll(List.of("SCENARIO", "CUSTOM", "DISMISS"));
        return l;
    }

    static final Set<String> LC_TYPES = Set.of("MT700", "MT701", "MT705", "MT707", "MT708", "MT710", "MT711", "MT720", "MT721", "MT730", "MT732",
            "MT734", "MT740", "MT742", "MT744", "MT747", "MT750", "MT752", "MT754", "MT756");
    static final Set<String> COLL_TYPES = Set.of("MT400", "MT410", "MT412", "MT416", "MT420", "MT422", "MT430", "MT450", "MT455", "MT456");

    public static boolean isCollInstruction(ParsedMessage pm) {
        String f79 = pm.getVars().get("f79");
        return "MT499".equals(pm.getShortType()) && f79 != null && f79.stripLeading().startsWith("/COLLINST/");
    }

    /** ledger family of a message / outcome: UNDERTAKING, LC or COLL */
    static String familyOf(ParsedMessage pm, Map<String, String> vars) {
        String f = vars == null ? null : vars.get("ldg_family");
        if (f != null) return f;
        String t = pm.getShortType() == null ? "" : pm.getShortType();
        if (LC_TYPES.contains(t)) return "LC";
        if (COLL_TYPES.contains(t) || "MT499".equals(t) || "MT410".equals(t)) return "COLL";
        return "UNDERTAKING";
    }

    // ------------------------------------------------------------------------------------------------ business reject

    /** The receiving virtual bank rejects the message at business level (MT759 / tsrv.019 REJT, simulator convention). */
    public List<Long> sendBusinessReject(MessageRecord rec, ParsedMessage pm, Participant recv, Participant sender, List<String> reasons,
                                         Map<String, String> ledgerVars, double delaySeconds, String kind) {
        if (sender == null) return List.of();
        Map<String, String> base = baseVars(pm, sender, recv);
        if (ledgerVars != null) base.putAll(ledgerVars);
        String lref = rec.getUndertakingRef() != null ? rec.getUndertakingRef() : pm.getTxRef();
        ledger.contextAny(lref).ifPresent(base::putAll);
        String fam = familyOf(pm, base);
        base.put("rejectReasons", "COLL".equals(familyOf(pm, base)) ? wrap(String.join("\n", reasons), 50, 30, false) : wrap(String.join("\n", reasons), 65, 140, true));
        base.put("info", trunc(("BUSINESS REJECT OF " + pm.getMsgType() + ": " + String.join("; ", reasons)), 1990));
        base.put("status", "REJT");
        base.put("statusCategory", "GENR");
        base.putIfAbsent("issuerBic", base.getOrDefault("ldg_issuerBic", recv.bic11()));
        if (base.get("ref") == null) base.put("ref", "NONREF");
        Scenario.Step st = new Scenario.Step();
        st.setDelaySeconds(delaySeconds);
        if ("COLL".equals(fam)) {
            st.setMt("mt499-business-reject.fin");           // collections: MT499 free format (simulator convention)
        } else {
            if ("LC".equals(fam)) base.put("ldg_form", "DOCR");  // MT759 22D DOCR = documentary credit
            st.setMt("mt759-business-reject.fin");
            st.setMx("tsrv019-status.xml");
        }
        if (previewing()) {
            String tpl = "MT".equals(pm.getFormat()) || st.getMx() == null ? st.getMt() : st.getMx();
            addDraft(BUSINESS_REJECT.getId(), tpl, base, Map.of(), recv, sender, rec.getId(), "after " + delaySeconds + "s");
            return List.of();
        }
        gateway.note(pm.getTxRef(), BUSINESS_REJECT.getId(), rec.getId(), recv.getId() + " rejects message #" + rec.getId() + " at business level: " + String.join("; ", reasons));
        return schedule(BUSINESS_REJECT, st, base, "MT".equals(pm.getFormat()) ? "MT" : "MX", sender, recv, rec.getId(), 99, kind);
    }

    // ------------------------------------------------------------------------------------------------ kickoff

    private static String tplOf(Scenario.Step st, String fmt) {
        return "MX".equals(fmt) ? st.getMx() : st.getMt();
    }

    private static boolean blank(String x) {
        return x == null || x.isBlank();
    }

    /** ?from=/?to= overrides replace the first step's sender / receiver in every step that uses the same participant */
    private static String mapParty(String spec, String firstFrom, String firstTo, String fromOverride, String toOverride) {
        if (spec != null && spec.equalsIgnoreCase(firstFrom) && !blank(fromOverride)) return fromOverride;
        if (spec != null && spec.equalsIgnoreCase(firstTo) && !blank(toOverride)) return toOverride;
        return spec;
    }

    /**
     * Run a scenario's kickoff steps (virtual bank initiates). The ledger record (and with it ref / amounts) is chosen ONCE per trigger
     * and shared by all steps; ?ref= picks a record explicitly. Returns a short description of what was scheduled.
     */
    public List<String> trigger(String scenarioId, String format, Map<String, String> overrides, String fromOverride, String toOverride) {
        Scenario s = cfg.scenario(scenarioId).orElseThrow(() -> new NoSuchElementException("unknown scenario " + scenarioId));
        if (s.getKickoff().isEmpty()) throw new IllegalArgumentException("scenario " + scenarioId + " has no kickoff steps; send a message from your system instead");
        String fmt = format == null ? "MT" : format.toUpperCase();
        Scenario.Step first = s.getKickoff().stream().filter(st -> tplOf(st, fmt) != null).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("scenario " + scenarioId + " has no " + fmt + " kickoff template (try format=" + ("MX".equals(fmt) ? "MT" : "MX") + ")"));
        cfg.activate(s);
        String firstFrom = first.getFrom(), firstTo = first.getTo();
        Participant from0 = cfg.resolve(blank(fromOverride) ? firstFrom : fromOverride).orElseThrow(() -> new IllegalArgumentException("kickoff from? " + (blank(fromOverride) ? firstFrom : fromOverride)));
        Participant to0 = cfg.resolve(blank(toOverride) ? firstTo : toOverride).orElseThrow(() -> new IllegalArgumentException("kickoff to? " + (blank(toOverride) ? firstTo : toOverride)));
        String select = s.getKickoff().stream().map(Scenario.Step::getSelect).filter(x -> !blank(x)).findFirst().orElse(null);
        String tpl0 = tplOf(first, fmt);
        String type = templateType(tpl0);
        LedgerService.Action act = LedgerService.classify(type);
        boolean newLc = "NEW_LC".equals(select) || select == null && Set.of("MT700", "MT705", "MT710").contains(type);
        boolean newColl = "NEW_COLL".equals(select) || select == null && tpl0.contains("collinst");
        boolean newGt = "NEW".equals(select) || select == null && (act == LedgerService.Action.ISSUE || act == LedgerService.Action.APPLY);
        Map<String, String> base = baseVars(new ParsedMessage(), null, null);
        String refOv = overrides == null ? null : overrides.get("ref");
        Optional<Map<String, String>> c = !blank(refOv) ? ledger.contextAny(refOv)
                : newLc || newColl || newGt ? Optional.empty() : ledger.suggestContext(select, type, from0.bic11(), to0.bic11());
        String ctx;
        if (c.isPresent()) {
            Map<String, String> m = c.get();
            base.putAll(m);
            String amt = m.getOrDefault("kickoffAmount", m.get("outstanding"));
            if (amt != null) { base.put("amount", amt); base.put("amountIso", amt.replace(',', '.')); }
            if (m.get("ldg_issuerBic") != null) base.put("issuerBic", m.get("ldg_issuerBic"));
            if (m.get("ldg_nextExpiry6") != null) { base.put("newExpiry6", m.get("ldg_nextExpiry6")); base.put("newExpiryIso", m.get("ldg_nextExpiryIso")); }
            if (m.get("lc_newExpiry6") != null) { base.put("newExpiry6", m.get("lc_newExpiry6")); base.put("newExpiryIso", m.get("lc_newExpiryIso")); }
            ctx = "ledger context of " + m.get("ref") + " (" + m.get("ldg_status") + ")";
        } else if (!blank(refOv)) {
            ctx = "reference " + refOv + " is not in the ledger - sent as given";
        } else if (newLc) {
            base.put("ref", "SIMLC" + LocalDate.now().format(YYMMDD) + String.format("%04d", SEQ.incrementAndGet() % 10000));
            ctx = "new documentary credit";
        } else if (newColl) {
            base.put("ref", "SIMCO" + LocalDate.now().format(YYMMDD) + String.format("%04d", SEQ.incrementAndGet() % 10000));
            ctx = "new collection";
        } else if (newGt) {
            ctx = "new reference";
        } else {
            ctx = "no matching record in the ledger" + (select != null ? " for situation " + select : "") + " - random reference (your system should reject it)";
        }
        if (overrides != null) overrides.forEach((k, v) -> { if (v != null && !v.isBlank()) base.put(k, v); });
        List<String> out = new ArrayList<>();
        for (Scenario.Step st : s.getKickoff()) {
            String tpl = tplOf(st, fmt);
            if (tpl == null) continue;
            String fs = mapParty(st.getFrom(), firstFrom, firstTo, fromOverride, toOverride);
            String tsp = mapParty(st.getTo(), firstFrom, firstTo, fromOverride, toOverride);
            Participant from = cfg.resolve(fs).orElseThrow(() -> new IllegalArgumentException("kickoff from? " + fs));
            Participant to = cfg.resolve(tsp).orElseThrow(() -> new IllegalArgumentException("kickoff to? " + tsp));
            Scenario.Step eff = new Scenario.Step();   // kickoff: from/to already resolved (incl. ?from=/?to= overrides)
            eff.setDelaySeconds(st.getDelaySeconds()); eff.setMt(st.getMt()); eff.setMx(st.getMx()); eff.setVars(st.getVars());
            eff.setRepeat(st.getRepeat()); eff.setPossibleDuplicate(st.isPossibleDuplicate()); eff.setNote(st.getNote()); eff.setWhen(st.getWhen());
            eff.setFireWhen(st.getFireWhen());
            eff.setFrom(from.getId()); eff.setTo(to.getId());
            schedule(s, eff, base, fmt, to, from, null, 0, "KICKOFF");
            out.add(st.getDelaySeconds() + "s: " + from.getId() + " -> " + to.getId() + " " + tpl + " ref=" + base.get("ref") + " [" + ctx + "]"
                    + (st.getFireWhen().isEmpty() ? "" : " (only if " + st.getFireWhen() + " when due)"));
        }
        return out;
    }

    static String templateType(String tpl) {
        Matcher m = Pattern.compile("^mt(\\d{3})").matcher(tpl);
        if (m.find()) return "MT" + m.group(1);
        Matcher x = Pattern.compile("^(tsrv|tsin)(\\d{3})").matcher(tpl);
        if (x.find()) return x.group(1) + "." + x.group(2);
        return tpl;
    }

    // ------------------------------------------------------------------------------------------------ persistent scheduling

    List<Long> schedule(Scenario s, Scenario.Step st, Map<String, String> base, String fmt,
                        Participant inboundSender, Participant inboundReceiver, Long inReplyTo, int depth, String kind) {
        if (st.getNote() != null) gateway.note(base.get("ref"), s.getId(), inReplyTo, st.getNote());
        if (!st.getWhen().isEmpty() && !whenMatches(st.getWhen(), base)) {
            gateway.note(base.get("ref"), s.getId(), inReplyTo, "step " + (st.getMt() != null ? st.getMt() : st.getMx()) + " skipped (step condition " + st.getWhen() + " not met)");
            return List.of();
        }
        // strict: MT inbound -> MT template, MX inbound -> MX template (a step without one is skipped for that format)
        String tpl = "MX".equals(fmt) ? st.getMx() : st.getMt();
        if (tpl == null) return List.of();
        Participant from = resolveParty(st.getFrom(), inboundSender, inboundReceiver);
        Participant to = resolveParty(st.getTo(), inboundSender, inboundReceiver);
        if (from == null || to == null) {
            gateway.note(base.get("ref"), s.getId(), inReplyTo, "Cannot resolve from/to " + st.getFrom() + "/" + st.getTo());
            return List.of();
        }
        String group = UUID.randomUUID().toString();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < Math.max(1, st.getRepeat()); i++) {
            PendingResponse p = new PendingResponse();
            p.setCreatedAt(Instant.now());
            p.setDueAt(Instant.now().plusMillis((long) (st.getDelaySeconds() * 1000) + i * 700L));
            p.setStatus("PENDING");
            p.setKind(kind);
            p.setScenarioId(s.getId());
            p.setTemplate(tpl);
            p.setFromId(from.getId());
            p.setToId(to.getId());
            p.setBaseVarsJson(toJson(base));
            Map<String, String> sv = new LinkedHashMap<>(st.getVars());
            st.getFireWhen().forEach((k, v) -> sv.put(FIRE_WHEN + k, v));
            p.setStepVarsJson(toJson(sv));
            p.setInReplyTo(inReplyTo);
            p.setDepth(depth);
            p.setPossibleDuplicate(i > 0 && st.isPossibleDuplicate());
            p.setGroupKey(group);
            p.setCopyIndex(i);
            p.setRef(trunc(base.get("ref"), 64));
            pending.save(p);
            ids.add(p.getId());
            final Long id = p.getId();
            scheduler.schedule(() -> fireStored(id), p.getDueAt());
        }
        return ids;
    }

    // ------------------------------------------------------------------------------------------------ preview (draft without sending)

    private static final ThreadLocal<List<Map<String, Object>>> PREVIEW = new ThreadLocal<>();

    /** Runs {@code work} in preview mode: review responses are rendered into drafts instead of being submitted / scheduled. */
    public List<Map<String, Object>> preview(Runnable work) {
        List<Map<String, Object>> drafts = new ArrayList<>();
        PREVIEW.set(drafts);
        try {
            work.run();
        } finally {
            PREVIEW.remove();
        }
        return drafts;
    }

    public static boolean previewing() {
        return PREVIEW.get() != null;
    }

    private void addDraft(String label, String tpl, Map<String, String> base, Map<String, String> stepVars, Participant from, Participant to,
                          Long inReplyTo, String timing) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("template", tpl);
        d.put("from", from.getId());
        d.put("fromBic", from.bic11());
        d.put("to", to.getId());
        d.put("toBic", to.bic11());
        d.put("timing", timing);
        try {
            d.put("raw", render(label, tpl, base, stepVars == null ? Map.of() : stepVars, from, to, inReplyTo));
        } catch (Exception e) {
            d.put("error", "render failed: " + e.getMessage());
        }
        PREVIEW.get().add(d);
    }

    /** Fire immediately (synchronously) and return the gateway result - used by the review queue. */
    public SubmitResult sendNow(String kind, String label, String tpl, Map<String, String> base, Map<String, String> stepVars,
                                Participant from, Participant to, Long inReplyTo) {
        if (previewing()) {
            addDraft(label, tpl, base, stepVars, from, to, inReplyTo, "immediately");
            SubmitResult r = new SubmitResult();
            r.setStatus("PREVIEW");
            return r;
        }
        PendingResponse p = new PendingResponse();
        p.setCreatedAt(Instant.now());
        p.setDueAt(Instant.now());
        p.setStatus("PENDING");
        p.setKind(kind);
        p.setScenarioId(label);
        p.setTemplate(tpl);
        p.setFromId(from.getId());
        p.setToId(to.getId());
        p.setBaseVarsJson(toJson(base));
        p.setStepVarsJson(toJson(stepVars == null ? Map.of() : stepVars));
        p.setInReplyTo(inReplyTo);
        p.setDepth(1);
        p.setGroupKey(UUID.randomUUID().toString());
        p.setRef(trunc(base.get("ref"), 64));
        pending.save(p);
        return fireStored(p.getId());
    }

    /** Schedule a review response step with a delay (persisted). */
    public Long sendLater(String kind, String label, String tpl, Map<String, String> base, Map<String, String> stepVars,
                          Participant from, Participant to, Long inReplyTo, double delaySeconds) {
        if (previewing()) {
            addDraft(label, tpl, base, stepVars, from, to, inReplyTo, "after " + delaySeconds + "s");
            return null;
        }
        Scenario s = new Scenario();
        s.setId(label);
        Scenario.Step st = new Scenario.Step();
        st.setDelaySeconds(delaySeconds);
        st.setFrom(from.getId());
        st.setTo(to.getId());
        if (tpl.endsWith(".xml")) st.setMx(tpl); else st.setMt(tpl);
        if (stepVars != null) st.setVars(new LinkedHashMap<>(stepVars));
        List<Long> ids = schedule(s, st, base, tpl.endsWith(".xml") ? "MX" : "MT", to, from, inReplyTo, 1, kind);
        return ids.isEmpty() ? null : ids.get(0);
    }

    static final String FIRE_WHEN = "__fw.";

    SubmitResult fireStored(Long id) {
        if (pending.claim(id) == 0) return null;
        PendingResponse p = pending.findById(id).orElse(null);
        if (p == null) return null;
        SubmitResult r = null;
        try {
            Map<String, String> sv0 = fromJson(p.getStepVarsJson());
            Map<String, String> fw = new LinkedHashMap<>();
            Map<String, String> fresh = new LinkedHashMap<>();
            sv0.forEach((k, v) -> { if (k.startsWith(FIRE_WHEN)) fw.put(k.substring(FIRE_WHEN.length()), v); });
            if (!fw.isEmpty() && p.getCopyIndex() == 0) {
                Map<String, String> now = new LinkedHashMap<>();
                ledger.contextAny(p.getRef()).ifPresent(now::putAll);
                now.forEach((k, v) -> { if (k.startsWith("ldg_") || k.startsWith("lc_") || k.startsWith("coll_") || k.startsWith("outstanding")) fresh.put(k, v); });
                if (!whenMatches(fw, now)) {
                    p.setStatus("SKIPPED");
                    p.setFiredAt(Instant.now());
                    p.setError(trunc("fireWhen " + fw + " not met (ledger now: status " + now.get("ldg_status") + ")", 2000));
                    pending.save(p);
                    gateway.note(p.getRef(), p.getScenarioId(), p.getInReplyTo(), "delayed step " + p.getTemplate() + " skipped: condition " + fw
                            + " no longer met at fire time (ledger status " + now.getOrDefault("ldg_status", "unknown") + ")");
                    return null;
                }
            }
            Participant from = cfg.byId(p.getFromId()).orElseThrow(() -> new IllegalStateException("unknown participant " + p.getFromId()));
            Participant to = cfg.byId(p.getToId()).orElseThrow(() -> new IllegalStateException("unknown participant " + p.getToId()));
            String raw;
            if (p.getPresetRaw() != null) {
                raw = p.getPresetRaw();
            } else {
                Map<String, String> base = fromJson(p.getBaseVarsJson());
                base.putAll(fresh);   // fireWhen steps see the ledger state at fire time (e.g. the open query reference)
                Map<String, String> stepVars = fromJson(p.getStepVarsJson());
                raw = render(p.getScenarioId(), p.getTemplate(), base, stepVars, from, to, p.getInReplyTo());
                if (p.getCopyIndex() == 0) {
                    for (PendingResponse sib : pending.findByGroupKeyAndCopyIndexGreaterThan(p.getGroupKey(), 0)) {
                        if (sib.getPresetRaw() == null) { sib.setPresetRaw(raw); pending.save(sib); }
                    }
                }
            }
            if (p.isPossibleDuplicate() || p.isRecovered()) raw = GatewayService.asPossibleDuplicate(raw);
            r = gateway.submit(raw, SubmitOptions.builder().channel("SCENARIO").producedBy(p.getScenarioId())
                    .inReplyTo(p.getInReplyTo()).depth(p.getDepth()).businessReject("BUSINESS_REJECT".equals(p.getKind()) || "REVIEW_REJECT".equals(p.getKind())).build());
            if (!"ACK".equals(r.getStatus())) log.warn("scenario {} produced NAKed message: {}", p.getScenarioId(), r.getErrors());
            p.setStatus("DONE");
            p.setResultMessageId(r.getId());
            p.setResultStatus(r.getStatus());
        } catch (Exception e) {
            log.error("scheduled response {} failed", id, e);
            p.setStatus("FAILED");
            p.setError(trunc(String.valueOf(e.getMessage()), 2000));
            gateway.note(p.getRef(), p.getScenarioId(), p.getInReplyTo(), "Scheduled response failed: " + e.getMessage());
        }
        p.setFiredAt(Instant.now());
        pending.save(p);
        return r;
    }

    /** Resume persisted delayed responses after a restart. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(20)
    public void resumeAfterRestart() {
        int n = 0;
        for (PendingResponse p : pending.findByStatusOrderByDueAtAsc("FIRING")) {
            // crashed while sending: send again, flagged as possible duplicate (PDE) - like a real interface would
            p.setStatus("PENDING");
            p.setRecovered(true);
            pending.save(p);
        }
        for (PendingResponse p : pending.findByStatusOrderByDueAtAsc("PENDING")) {
            Instant at = p.getDueAt().isBefore(Instant.now().plusMillis(3000)) ? Instant.now().plusMillis(3000) : p.getDueAt();
            final Long id = p.getId();
            scheduler.schedule(() -> fireStored(id), at);
            n++;
        }
        if (n > 0) {
            log.info("resumed {} pending delayed responses after restart", n);
            gateway.note(null, null, null, "Simulator restarted: resumed " + n + " pending delayed response(s)");
        }
    }

    public List<PendingResponse> listPending(boolean all) {
        return all ? pending.findTop200ByOrderByIdDesc() : pending.findByStatusOrderByDueAtAsc("PENDING");
    }

    private String render(String scenarioId, String tpl, Map<String, String> base0, Map<String, String> stepVars, Participant from, Participant to,
                          Long inReplyTo) throws Exception {
        Map<String, String> vars = new LinkedHashMap<>(base0);
        vars.put("fromBic", from.bic11());
        vars.put("fromName", from.getName());
        vars.put("toBic", to.bic11());
        vars.put("toName", to.getName());
        vars.put("newRef", newRef());
        vars.putIfAbsent("issuerBic", from.bic11());
        List<String> missing = new ArrayList<>();
        for (var e : stepVars.entrySet()) if (!e.getKey().startsWith(FIRE_WHEN)) vars.put(e.getKey(), render(e.getValue(), vars, false, missing));
        String body = cfg.readTemplate(tpl);
        boolean isMx = tpl.endsWith(".xml");
        String rendered = render(stripComments(body, isMx), vars, isMx, missing);
        String raw;
        if (isMx) {
            raw = buildMx(rendered, from.bic11(), to.bic11(), vars.get("newRef"), false);
        } else {
            Matcher m = Pattern.compile("^mt(\\d{3})").matcher(tpl);
            if (!m.find()) throw new IllegalArgumentException("MT template name must start with mtNNN: " + tpl);
            rendered = rendered.lines().filter(l -> !l.isBlank()).reduce((a, b) -> a + "\n" + b).orElse("");
            raw = GatewayService.wrapMt(m.group(1), from.bic11(), to.bic11(), rendered, false);
        }
        if (!missing.isEmpty() && !previewing()) gateway.note(vars.get("ref"), scenarioId, inReplyTo, "Template " + tpl + " had unresolved variables: " + missing);
        return raw;
    }

    private String buildMx(String document, String from, String to, String bizMsgId, boolean dup) {
        try {
            AbstractMX m = AbstractMX.parse(document);
            if (m != null) {
                BusinessAppHdrV02 h = AppHdrFactory.createBusinessAppHdrV02(from, to, bizMsgId, m.getMxId());
                if (dup) h.setPssblDplct(Boolean.TRUE);
                m.setAppHdr(h);
                return m.message();
            }
        } catch (Exception e) {
            log.warn("Prowide MX build failed ({}), using manual envelope", e.getMessage());
        }
        String ns = document.replaceAll("(?s).*?xmlns=\"urn:iso:std:iso:20022:tech:xsd:([^\"]+)\".*", "$1");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<RequestPayload>\n"
                + "<AppHdr xmlns=\"urn:iso:std:iso:20022:tech:xsd:head.001.001.02\"><Fr><FIId><FinInstnId><BICFI>" + from
                + "</BICFI></FinInstnId></FIId></Fr><To><FIId><FinInstnId><BICFI>" + to + "</BICFI></FinInstnId></FIId></To>"
                + "<BizMsgIdr>" + bizMsgId + "</BizMsgIdr><MsgDefIdr>" + ns + "</MsgDefIdr><CreDt>"
                + OffsetDateTime.now(ZoneOffset.UTC).withNano(0) + "</CreDt>" + (dup ? "<PssblDplct>true</PssblDplct>" : "") + "</AppHdr>\n"
                + document + "\n</RequestPayload>";
    }

    public Map<String, String> baseVars(ParsedMessage pm, Participant sender, Participant receiver) {
        Map<String, String> v = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        v.put("today6", today.format(YYMMDD));
        v.put("todayIso", today.toString());
        v.put("nowIso", LocalDateTime.now().withNano(0).toString());
        v.put("ref", "SIMGT" + today.format(YYMMDD) + String.format("%04d", SEQ.incrementAndGet() % 10000));
        v.put("currency", "USD");
        v.put("amount", "50000,00");
        v.put("amountIso", "50000.00");
        v.put("amendmentNo", "1");
        v.put("newExpiry6", today.plusYears(1).format(YYMMDD));
        v.put("newExpiryIso", today.plusYears(1).toString());
        v.put("expiry6", today.plusMonths(6).format(YYMMDD));
        v.put("expiryIso", today.plusMonths(6).toString());
        v.put("demandId", "DMD" + today.format(YYMMDD) + String.format("%04d", SEQ.incrementAndGet() % 10000));
        v.put("reason", "SIMULATED REASON");
        v.put("ship6", today.plusMonths(4).format(YYMMDD));
        v.put("maturity6", today.plusDays(60).format(YYMMDD));
        pm.getVars().forEach((k, val) -> { if (val != null && !k.startsWith("_")) v.put(k, val); });
        if (sender != null) { v.put("sender", sender.bic11()); v.put("senderName", sender.getName()); }
        if (receiver != null) { v.put("receiver", receiver.bic11()); v.put("receiverName", receiver.getName()); }
        if (pm.getMsgType() != null) v.put("inboundType", pm.getMsgType());
        if (!v.containsKey("issuerBic") && sender != null && pm.getMsgType() != null) v.put("issuerBic", sender.bic11());
        cfg.getConfig().getParticipants().forEach(p -> v.put("bic_" + p.getId(), p.bic11()));
        return v;
    }

    /** ${a|b|'literal'} -> first non-empty. */
    static String render(String tpl, Map<String, String> vars, boolean xmlEscape, List<String> missing) {
        Matcher m = VAR.matcher(tpl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = null;
            String expr = m.group(1);
            boolean raw = expr.startsWith("!");
            if (raw) expr = expr.substring(1);
            for (String alt : expr.split("\\|")) {
                alt = alt.trim();
                if (alt.startsWith("'") && alt.endsWith("'") && alt.length() >= 2) { val = alt.substring(1, alt.length() - 1); break; }
                String x = vars.get(alt);
                if (x != null && !x.isBlank()) { val = x; break; }
            }
            if (val == null) { missing.add(m.group(1)); val = ""; }
            if (xmlEscape && !raw) val = val.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String stripComments(String body, boolean xml) {
        if (xml) return body.replaceAll("(?s)<!--.*?-->", "").strip();
        StringBuilder sb = new StringBuilder();
        for (String l : body.split("\\R")) if (!l.startsWith("#")) sb.append(l.stripTrailing()).append('\n');
        return sb.toString().strip();
    }

    /** Make free text fit a SWIFT narrative field: charset (x or z), line length, max lines. */
    public static String wrap(String text, int width, int maxLines, boolean zCharset) {
        String allowed = zCharset ? com.example.swiftsim.parse.SwiftFormat.Z : com.example.swiftsim.parse.SwiftFormat.X;
        StringBuilder clean = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '\n') clean.append('\n');
            else if (Character.isLetterOrDigit(c) && c < 128 || allowed.indexOf(c) >= 0) clean.append(c);
            else if (c == '_' || c == '=' || c == ';') clean.append(zCharset ? c : '-');
            else clean.append(' ');
        }
        List<String> lines = new ArrayList<>();
        for (String para : clean.toString().split("\n")) {
            String rest = para.strip();
            while (rest.length() > width) {
                int cut = rest.lastIndexOf(' ', width);
                if (cut <= 0) cut = width;
                lines.add(rest.substring(0, cut).strip());
                rest = rest.substring(cut).strip();
            }
            if (!rest.isEmpty()) lines.add(rest);
        }
        if (lines.size() > maxLines) lines = lines.subList(0, maxLines);
        return String.join("\n", lines);
    }

    static String newRef() {
        return "SIM" + LocalDate.now().format(YYMMDD) + String.format("%05d", SEQ.incrementAndGet() % 100000);
    }

    private Participant resolveParty(String spec, Participant inboundSender, Participant inboundReceiver) {
        if (spec == null || spec.equals("$sender")) return inboundSender;
        if (spec.equals("$receiver")) return inboundReceiver;
        return cfg.resolve(spec).orElse(null);
    }

    private boolean typeMatches(Scenario.Reaction r, ParsedMessage pm) {
        return r.getMessageTypes().stream().anyMatch(t -> t.equals("*") || t.equalsIgnoreCase(pm.getShortType()) || t.equalsIgnoreCase(pm.getMsgType()));
    }

    private boolean toMatches(Scenario.Reaction r, Participant recv) {
        return r.getTo().isEmpty() || r.getTo().stream().anyMatch(t -> t.equalsIgnoreCase(recv.getId()) || t.equalsIgnoreCase(recv.bic11()));
    }

    static boolean whenMatches(Map<String, String> when, Map<String, String> vars) {
        for (var e : when.entrySet()) {
            String actual = vars.get(e.getKey());
            String exp = e.getValue();
            if (exp.startsWith("~")) {
                if (actual == null || !Pattern.compile(exp.substring(1), Pattern.DOTALL).matcher(actual).find()) return false;
            } else if (exp.startsWith("!~")) {   // negated regex: passes when the variable is absent or does not match
                if (actual != null && Pattern.compile(exp.substring(2), Pattern.DOTALL).matcher(actual).find()) return false;
            } else if (exp.startsWith("!")) {
                if (exp.substring(1).equals(actual)) return false;
            } else if (!exp.equals(actual)) return false;
        }
        return true;
    }

    private String toJson(Map<String, String> m) {
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, String> fromJson(String s) {
        try {
            return s == null ? new LinkedHashMap<>() : json.readValue(s, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    static String trunc(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }
}
