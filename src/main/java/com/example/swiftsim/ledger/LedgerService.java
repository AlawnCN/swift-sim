package com.example.swiftsim.ledger;

import com.example.swiftsim.config.ConfigService;
import com.example.swiftsim.config.Participant;
import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.domain.MessageRepository;
import com.example.swiftsim.parse.ParsedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Transaction ledger + lifecycle state machine of guarantees / standby LCs. Every message delivered by the gateway is
 * run through {@link #process}: the receiving bank's "application" checks the message against what is known about
 * the undertaking (state, parties/roles, amounts, currency, sequence numbers) and either applies it or rejects it.
 * All codes BIZ-xxx are simulator codes (not SWIFT codes).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {
    private final UndertakingRepository repo;
    private final UndertakingPartyRepository parties;
    private final AmendmentRepository amendments;
    private final DemandRepository demands;
    private final MovementRepository movements;
    private final LedgerEventRepository events;
    private final MessageRepository messages;
    private final ConfigService cfg;
    private final BusinessClock clock;
    private final PlatformTransactionManager txm;
    /** documentary credit + collection ledgers (Category 7 / Category 4) */
    private final List<TradeLedger> trade;

    public static final Set<String> TERMINAL = Set.of("RELEASED", "EXPIRED", "TERMINATED");
    static final Set<String> NON_ISSUER = Set.of("ADVISING", "SECOND_ADVISING", "BENEFICIARY");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    public enum Action {
        ISSUE, APPLY, AMEND, AMEND_RESP, DEMAND, DEMAND_REFUSAL, DEMAND_WITHDRAWAL, SETTLEMENT, EOP_REQUEST, EOP_RESPONSE,
        NON_EXT, NON_EXT_REQ, REDUCTION, TERMINATION, ACK, STATUS, ANCILLARY, OTHER
    }

    public record Outcome(String status, List<String> errors, String ref, String fromStatus, String toStatus, Map<String, String> vars) {
        public boolean rejected() { return "REJECTED".equals(status); }
    }

    public static Action classify(String shortType) {
        if (shortType == null) return Action.OTHER;
        return switch (shortType) {
            case "MT760", "tsrv.001" -> Action.ISSUE;
            case "tsin.005" -> Action.APPLY;
            case "MT767", "tsrv.005" -> Action.AMEND;
            case "MT787", "tsrv.008" -> Action.AMEND_RESP;
            case "MT765", "tsrv.013" -> Action.DEMAND;
            case "MT786", "tsrv.016" -> Action.DEMAND_REFUSAL;
            case "tsrv.017" -> Action.DEMAND_WITHDRAWAL;
            case "MT202" -> Action.SETTLEMENT;
            case "tsrv.014" -> Action.EOP_REQUEST;
            case "tsrv.015" -> Action.EOP_RESPONSE;
            case "MT785", "tsrv.011" -> Action.NON_EXT;
            case "tsrv.010" -> Action.NON_EXT_REQ;
            case "MT769" -> Action.REDUCTION;
            case "tsrv.012" -> Action.TERMINATION;
            case "MT768" -> Action.ACK;
            case "tsrv.019" -> Action.STATUS;
            case "MT759" -> Action.ANCILLARY;
            default -> Action.OTHER;
        };
    }

    // ------------------------------------------------------------------------------------------------ processing

    private TransactionTemplate tx() {
        return new TransactionTemplate(txm);
    }

    /** Check + apply one delivered message. Synchronized: single simulator instance, strictly ordered ledger. */
    public synchronized Outcome process(MessageRecord rec, ParsedMessage pm, boolean receiverIsSut, boolean businessReject) {
        try {
            return tx().execute(s -> doProcess(rec, pm, receiverIsSut, businessReject));
        } catch (Exception e) {
            log.error("ledger processing of message {} failed", rec.getId(), e);
            return new Outcome("INFO", List.of("ledger error: " + e.getMessage()), pm.getTxRef(), null, null, Map.of());
        }
    }

    /** ledger family owning a message: a trade ledger for Cat 7 / Cat 4 types, else (null) the undertaking ledger */
    TradeLedger tradeFor(ParsedMessage pm) {
        for (TradeLedger t : trade) if (t.ownsMessage(pm)) return t;
        String st = pm.getShortType();
        if (st != null && Set.of("MT759", "MT799", "MT202", "MT103", "MT499").contains(st)) {
            String ref = Facts.of(pm).ref;
            if (ref != null && repo.findByRef(ref).isPresent()) return null;
            for (TradeLedger t : trade) if (t.references(pm)) return t;
        }
        return null;
    }

    /** MT759 demand withdrawal convention (no dedicated MT since SR2021; MX = tsrv.017): 23H OTHERFNC, 45D contains WITHDRAW, :21: = demand reference */
    static boolean isWithdrawal759(ParsedMessage pm, Facts f) {
        return "MT759".equals(pm.getShortType()) && "OTHERFNC".equals(f.code23H) && f.narrative != null && f.narrative.toUpperCase().contains("WITHDRAW")
                && !f.narrative.toUpperCase().contains("BUSINESS REJECTION");
    }

    /** MT759 from a counterparty bank asking the SUT something (simulator heuristic: 45D contains QUERY / PLEASE / ADVISE / '?') */
    static boolean isQuery759(Facts f) {
        if (f.narrative == null) return false;
        String n = f.narrative.toUpperCase();
        return !n.contains("BUSINESS REJECTION") && !n.contains("WITHDRAW") && (n.contains("QUERY") || n.contains("PLEASE") || n.contains("KINDLY ADVISE") || n.contains("?") || n.contains("CHASER"));
    }

    private Outcome doProcess(MessageRecord rec, ParsedMessage pm, boolean receiverIsSut, boolean businessReject) {
        TradeLedger tl = tradeFor(pm);
        if (tl != null) return tl.process(rec, pm, receiverIsSut, businessReject);
        Facts f = Facts.of(pm);
        Action a0 = classify(pm.getShortType());
        if (a0 == Action.ANCILLARY && isWithdrawal759(pm, f)) a0 = Action.DEMAND_WITHDRAWAL;
        else if ("MT759".equals(pm.getShortType())) f.demandRef = null;
        final Action a = a0;
        String ref = f.ref;
        Undertaking u = ref == null ? null : repo.findByRef(ref).orElse(null);
        String sender = rec.getSenderBic(), receiver = rec.getReceiverBic();
        Map<String, String> out = new LinkedHashMap<>();

        if (businessReject) {
            if (u != null) event(u, "BUSINESS_REJECT_SENT", u.getStatus(), u.getStatus(), rec, "business reject sent by " + sender + " to " + receiver);
            else orphan(ref, "BUSINESS_REJECT_SENT", rec, "business reject (no undertaking known)");
            return outcome("INFO", List.of(), ref, u, null, out);
        }
        // possible duplicate emission: ignore at business level if the original was already processed
        if (Boolean.TRUE.equals(rec.getPossibleDuplicate()) && rec.getDuplicateOf() != null) {
            MessageRecord orig = messages.findById(rec.getDuplicateOf()).orElse(null);
            if (orig != null && orig.getBusinessStatus() != null) {
                if (u != null) event(u, "DUPLICATE_IGNORED", u.getStatus(), u.getStatus(), rec, "PDE copy of message #" + orig.getId() + " (already processed: " + orig.getBusinessStatus() + ") - not processed again");
                return outcome("DUPLICATE_IGNORED", List.of(), ref, u, u == null ? null : u.getStatus(), out);
            }
        }
        if (a == Action.OTHER) {
            if (u != null) event(u, "MESSAGE", u.getStatus(), u.getStatus(), rec, pm.getMsgType() + " linked (no lifecycle effect)");
            return outcome("NOT_APPLICABLE", List.of(), ref, u, null, out);
        }
        if (u != null) expireIfDue(u, rec);
        String from = u == null ? null : u.getStatus();
        List<String> errs = new ArrayList<>();
        Runnable apply = null;

        switch (a) {
            case ISSUE -> {
                if (ref == null) errs.add("BIZ-V00 undertaking number missing");
                else if (u != null && !"APPLIED".equals(u.getMilestone()))
                    errs.add("BIZ-U02 duplicate undertaking number " + ref + ": already known (status " + u.getStatus() + ", issuer " + u.getIssuerBic() + ", issued " + u.getIssueDate() + ")");
                if (u != null && "APPLIED".equals(u.getMilestone()) && !roles(u, sender).contains("ISSUER"))
                    errs.add("BIZ-R03 " + sender + " is not the bank the application " + ref + " was sent to (issuer " + u.getIssuerBic() + ")");
                if (f.currency == null || f.amount == null) errs.add("BIZ-V03 undertaking currency/amount missing");
                if (f.expiryDate != null && f.issueDate != null && f.expiryDate.isBefore(f.issueDate))
                    errs.add("BIZ-V01 expiry date " + f.expiryDate + " is before the issue date " + f.issueDate);
                if (f.expiryDate != null && f.expiryDate.isBefore(clock.today()))
                    errs.add("BIZ-V04 expiry date " + f.expiryDate + " is already in the past (business date " + clock.today() + ")");
                if ("FIXD".equals(f.expiryType) && f.expiryDate == null)
                    errs.add("BIZ-V02 expiry type FIXD but no expiry date given");
                if (errs.isEmpty()) {
                    final Undertaking ex = u;
                    apply = () -> issue(ex, f, pm, rec, out);
                }
            }
            case APPLY -> {
                if (ref == null) errs.add("BIZ-V00 applicant reference missing");
                else if (u != null) errs.add("BIZ-U02 duplicate reference " + ref + ": already known (status " + u.getStatus() + ")");
                if (errs.isEmpty()) apply = () -> applyApplication(f, pm, rec, out);
            }
            default -> {
                if (u == null) {
                    boolean preIssuance = a == Action.ANCILLARY && f.code23H != null && cfg.getConfig().getLedger().getPreIssuanceMt759Codes().contains(f.code23H);
                    if (preIssuance || !cfg.getConfig().getLedger().isRejectUnknownReferences() || a == Action.ACK && ref == null) {
                        orphan(ref, "UNLINKED_MESSAGE", rec, pm.getMsgType() + " for unknown reference accepted (pre-issuance / lenient)");
                        return outcome("INFO", List.of(), ref, null, null, out);
                    }
                    errs.add("BIZ-U01 reference " + ref + " does not match any undertaking known to " + receiver
                            + (a == Action.AMEND ? " (amendment before issuance?)" : ""));
                } else {
                    errs.addAll(roleAndStateErrors(u, a, sender, receiver));
                    if (errs.isEmpty()) apply = specific(a, u, f, pm, rec, errs, out);
                }
            }
        }
        if (!errs.isEmpty()) {
            String st = receiverIsSut ? "INCONSISTENT" : "REJECTED";
            String detail = (receiverIsSut ? "message to SUT is inconsistent with the ledger (your system should reject it): " : "business reject by " + receiver + ": ")
                    + String.join("; ", errs);
            if (u != null) event(u, receiverIsSut ? "INCONSISTENT_MESSAGE" : "BUSINESS_REJECTED", u.getStatus(), u.getStatus(), rec, detail);
            else orphan(ref, receiverIsSut ? "INCONSISTENT_MESSAGE" : "BUSINESS_REJECTED", rec, detail);
            return outcome(st, errs, ref, u, from, out);
        }
        if (apply != null) apply.run();
        Undertaking after = ref == null ? null : repo.findByRef(ref).orElse(null);
        return outcome("APPLIED", List.of(), ref, after, from, out);
    }

    private Outcome outcome(String status, List<String> errs, String ref, Undertaking u, String from, Map<String, String> out) {
        out.put("ldg_outcome", status);
        if (u != null) {
            out.put("ldg_status", u.getStatus());
            out.putAll(context(u));
        }
        return new Outcome(status, errs, ref, from, u == null ? null : u.getStatus(), out);
    }

    /** generic role / party / state checks (also used to suggest undertakings for kickoffs) */
    List<String> roleAndStateErrors(Undertaking u, Action a, String sender, String receiver) {
        List<String> errs = new ArrayList<>();
        Set<String> sr = roles(u, sender), rr = roles(u, receiver);
        // MT759 is free-format ancillary traffic and is frequently exchanged with banks that are not (yet) a party
        // (e.g. the applicant's bank) - simulator simplification: no party/state check, only linked to the history
        if (a == Action.ANCILLARY) return errs;
        if (sr.isEmpty()) errs.add("BIZ-R01 sender " + sender + " is not a party of undertaking " + u.getRef() + " (parties: " + partyList(u) + ")");
        if (rr.isEmpty()) errs.add("BIZ-R02 receiver " + receiver + " is not a party of undertaking " + u.getRef() + " (parties: " + partyList(u) + ")");
        if (!errs.isEmpty()) return errs;
        String need = switch (a) {
            case AMEND, DEMAND_REFUSAL, SETTLEMENT, NON_EXT -> requireRole(sr, Set.of("ISSUER"), "sender", sender, a) + requireRole(rr, NON_ISSUER, "receiver", receiver, a);
            case AMEND_RESP, DEMAND, DEMAND_WITHDRAWAL -> requireRole(sr, NON_ISSUER, "sender", sender, a) + requireRole(rr, Set.of("ISSUER"), "receiver", receiver, a);
            case EOP_REQUEST -> requireRole(sr, Set.of("ISSUER"), "sender", sender, a) + requireRole(rr, Set.of("APPLICANT"), "receiver", receiver, a);
            case EOP_RESPONSE -> requireRole(sr, Set.of("APPLICANT"), "sender", sender, a) + requireRole(rr, Set.of("ISSUER"), "receiver", receiver, a);
            case NON_EXT_REQ -> requireRole(rr, Set.of("ISSUER"), "receiver", receiver, a);
            default -> "";
        };
        if (!need.isEmpty()) errs.addAll(Arrays.asList(need.strip().split("\n")));
        if (!errs.isEmpty()) return errs;
        String st = u.getStatus();
        boolean info = a == Action.ACK || a == Action.STATUS || a == Action.ANCILLARY;
        boolean resolvesDemand = a == Action.DEMAND_REFUSAL || a == Action.SETTLEMENT || a == Action.DEMAND_WITHDRAWAL || a == Action.EOP_REQUEST || a == Action.EOP_RESPONSE;
        if ("APPLIED".equals(u.getMilestone()) && !info)
            errs.add("BIZ-S01 " + a + " not allowed before issuance: " + u.getRef() + " is only an application (APPLIED)");
        else if (TERMINAL.contains(st) && !info && !(resolvesDemand && pendingDemand(u) != null)) {
            if (a == Action.DEMAND && "EXPIRED".equals(st))
                errs.add("BIZ-S02 demand after expiry: undertaking " + u.getRef() + " expired on " + u.getExpiryDate() + " (business date " + clock.today() + ")");
            else errs.add("BIZ-S03 " + a + " not allowed: undertaking " + u.getRef() + " is " + st);
        }
        return errs;
    }

    private static String requireRole(Set<String> have, Set<String> allowed, String who, String bic, Action a) {
        for (String r : have) if (allowed.contains(r)) return "";
        return "BIZ-R0" + ("sender".equals(who) ? "3" : "4") + " " + who + " " + bic + " has role " + have + " in this undertaking; " + a
                + " requires the " + who + " to be " + allowed + "\n";
    }

    private Runnable specific(Action a, Undertaking u, Facts f, ParsedMessage pm, MessageRecord rec, List<String> errs, Map<String, String> out) {
        var L = cfg.getConfig().getLedger();
        switch (a) {
            case AMEND -> {
                Amendment pend = pendingAmendment(u);
                Demand pd = pendingDemand(u);
                boolean extensionForEop = pd != null && "PAEX".equals(pd.getType()) && f.newExpiry != null && f.increase == null && f.decrease == null;
                if (pend != null && !L.isAllowConcurrentAmendments())
                    errs.add("BIZ-A01 amendment no. " + pend.getSeqNo() + " of " + u.getRef() + " is still awaiting the beneficiary's response; a further amendment is not accepted");
                int expected = amendments.findByUndertakingIdOrderByIdAsc(u.getId()).size() + 1;
                if (f.seq == null) errs.add("BIZ-A02 amendment sequence number missing (expected " + expected + ")");
                else if (f.seq != expected) errs.add("BIZ-A02 amendment sequence number " + f.seq + " out of sequence: expected " + expected);
                currency(u, f, errs);
                if (f.decrease != null && f.decrease.compareTo(u.getOutstanding()) > 0)
                    errs.add("BIZ-M02 decrease " + fmt(f.decrease) + " exceeds the outstanding amount " + u.getCurrency() + " " + fmt(u.getOutstanding()));
                if (f.newExpiry != null && f.newExpiry.isBefore(clock.today()))
                    errs.add("BIZ-V04 new expiry date " + f.newExpiry + " is in the past");
                if (!errs.isEmpty()) return null;
                return () -> {
                    Amendment am = new Amendment();
                    am.setUndertakingId(u.getId());
                    am.setSeqNo(f.seq);
                    am.setCurrency(u.getCurrency());
                    am.setIncrease(f.increase);
                    am.setDecrease(f.decrease);
                    am.setNewExpiryDate(f.newExpiry);
                    am.setSenderBic(rec.getSenderBic());
                    am.setReceiverBic(rec.getReceiverBic());
                    am.setMessageId(rec.getId());
                    am.setCreatedAt(Instant.now());
                    String from = u.getStatus();
                    if (extensionForEop) {
                        am.setStatus("EFFECTIVE");
                        am.setNote("extension in answer to extend-or-pay demand " + pd.getDemandRef() + " - effective without further consent (simulator approximation)");
                        amendments.save(am);
                        applyAmendment(u, am, rec);
                        pd.setStatus("EXTENDED");
                        pd.setResolutionMessageId(rec.getId());
                        pd.setResolvedAt(Instant.now());
                        demands.save(pd);
                        u.setMilestone("EXTENDED");
                        save(u, rec);
                        event(u, "EXTENDED", from, u.getStatus(), rec, "expiry extended to " + u.getExpiryDate() + " (amendment " + f.seq + ", extend-or-pay " + pd.getDemandRef() + ")");
                    } else if (Boolean.FALSE.equals(f.consentRequired)) {
                        am.setStatus("EFFECTIVE");
                        am.setNote("BnfcryCnsntReqInd=false: effective without beneficiary consent");
                        amendments.save(am);
                        applyAmendment(u, am, rec);
                        u.setMilestone("AMENDED");
                        save(u, rec);
                        event(u, "AMENDED", from, u.getStatus(), rec, "amendment " + f.seq + " effective (no consent required): " + amendmentText(am));
                    } else {
                        am.setStatus("PENDING");
                        amendments.save(am);
                        save(u, rec);
                        event(u, "AMENDMENT_RECEIVED", from, u.getStatus(), rec, "amendment " + f.seq + " pending beneficiary response: " + amendmentText(am));
                    }
                    out.put("ldg_amendmentStatus", am.getStatus());
                };
            }
            case AMEND_RESP -> {
                Amendment pend = pendingAmendment(u);
                if (pend == null) errs.add("BIZ-A03 no amendment of " + u.getRef() + " is awaiting a response");
                else if (f.seq != null && !f.seq.equals(pend.getSeqNo()))
                    errs.add("BIZ-A02 response refers to amendment no. " + f.seq + " but the pending amendment is no. " + pend.getSeqNo());
                boolean accept = "ACPT".equals(f.response) || "ACCP".equals(f.response);
                boolean reject = "REJT".equals(f.response);
                if (!accept && !reject) errs.add("BIZ-A04 amendment response code '" + f.response + "' not understood (simulator accepts ACPT/REJT for MT787, ACCP/REJT for tsrv.008)");
                if (!errs.isEmpty()) return null;
                return () -> {
                    String from = u.getStatus();
                    pend.setResponseMessageId(rec.getId());
                    pend.setRespondedAt(Instant.now());
                    if (accept) {
                        pend.setStatus("ACCEPTED");
                        amendments.save(pend);
                        applyAmendment(u, pend, rec);
                        u.setMilestone("AMENDED");
                        save(u, rec);
                        event(u, "AMENDMENT_ACCEPTED", from, u.getStatus(), rec, "amendment " + pend.getSeqNo() + " accepted: " + amendmentText(pend));
                    } else {
                        pend.setStatus("REJECTED");
                        String why = pm.getVars().get("f23R");
                        pend.setNote(why == null ? null : why.replace("\n", " "));
                        amendments.save(pend);
                        save(u, rec);
                        event(u, "AMENDMENT_REJECTED", from, u.getStatus(), rec, "amendment " + pend.getSeqNo() + " rejected" + (why != null ? ": " + why.replace("\n", " ") : ""));
                    }
                    out.put("ldg_amendmentStatus", pend.getStatus());
                };
            }
            case DEMAND -> {
                Demand pd = pendingDemand(u);
                if (pd != null && !L.isAllowConcurrentDemands())
                    errs.add("BIZ-D03 demand " + pd.getDemandRef() + " on " + u.getRef() + " is still under examination; a further demand is not accepted");
                if (f.demandRef != null && demands.findByUndertakingIdOrderByIdAsc(u.getId()).stream().anyMatch(d -> f.demandRef.equals(d.getDemandRef())))
                    errs.add("BIZ-D02 duplicate demand reference " + f.demandRef + " for undertaking " + u.getRef());
                currency(u, f, errs);
                if (f.demandAmount == null) errs.add("BIZ-D05 demand amount missing");
                else if (f.demandAmount.compareTo(u.getOutstanding()) > 0)
                    errs.add("BIZ-D01 demand amount " + f.currency + " " + fmt(f.demandAmount) + " exceeds the outstanding amount " + u.getCurrency() + " " + fmt(u.getOutstanding()));
                if ("PAEX".equals(f.demandType) && f.requestedExpiry != null && u.getExpiryDate() != null && !f.requestedExpiry.isAfter(u.getExpiryDate()))
                    errs.add("BIZ-D06 extend-or-pay: requested expiry " + f.requestedExpiry + " is not later than the current expiry " + u.getExpiryDate());
                if (!errs.isEmpty()) return null;
                return () -> {
                    String from = u.getStatus();
                    Demand d = new Demand();
                    d.setUndertakingId(u.getId());
                    d.setDemandRef(f.demandRef != null ? f.demandRef : "MSG" + rec.getId());
                    d.setType(f.demandType == null ? "PAYM" : f.demandType);
                    d.setCurrency(f.currency);
                    d.setAmount(f.demandAmount);
                    d.setRequestedExpiry(f.requestedExpiry);
                    d.setStatus("RECEIVED");
                    d.setPresenterBic(rec.getSenderBic());
                    d.setMessageId(rec.getId());
                    d.setCreatedAt(Instant.now());
                    demands.save(d);
                    save(u, rec);
                    event(u, "PAEX".equals(d.getType()) ? "EXTEND_OR_PAY_RECEIVED" : "DEMAND_RECEIVED", from, u.getStatus(), rec,
                            "demand " + d.getDemandRef() + " " + d.getType() + " " + d.getCurrency() + " " + fmt(d.getAmount())
                                    + (d.getRequestedExpiry() != null ? " requested expiry " + d.getRequestedExpiry() : "") + " presented by " + d.getPresenterBic());
                    out.put("ldg_demandStatus", d.getStatus());
                };
            }
            case DEMAND_REFUSAL, SETTLEMENT, DEMAND_WITHDRAWAL, EOP_REQUEST -> {
                Demand pd = findPendingDemand(u, f.demandRef);
                if (pd == null) errs.add("BIZ-D04 no demand " + (f.demandRef != null ? f.demandRef + " " : "") + "of " + u.getRef() + " is pending");
                else if ((a == Action.DEMAND_REFUSAL || a == Action.SETTLEMENT) && !pd.getPresenterBic().equals(rec.getReceiverBic()))
                    errs.add("BIZ-R05 demand " + pd.getDemandRef() + " was presented by " + pd.getPresenterBic() + ", not by " + rec.getReceiverBic());
                else if (a == Action.DEMAND_WITHDRAWAL && !pd.getPresenterBic().equals(rec.getSenderBic()))
                    errs.add("BIZ-R05 demand " + pd.getDemandRef() + " was presented by " + pd.getPresenterBic() + "; only the presenter can withdraw it");
                else if (a == Action.EOP_REQUEST && !"PAEX".equals(pd.getType()))
                    errs.add("BIZ-D07 demand " + pd.getDemandRef() + " is a " + pd.getType() + " demand, not extend-or-pay");
                if (a == Action.SETTLEMENT || a == Action.DEMAND_REFUSAL) currency(u, f, errs);
                BigDecimal pay = a == Action.SETTLEMENT ? f.amount : null;
                if (pd != null && pay != null) {
                    if (pay.compareTo(pd.getAmount()) > 0) errs.add("BIZ-D08 payment " + fmt(pay) + " exceeds the demanded amount " + fmt(pd.getAmount()));
                    if (pay.compareTo(u.getOutstanding()) > 0) errs.add("BIZ-M01 payment " + fmt(pay) + " exceeds the outstanding amount " + fmt(u.getOutstanding()));
                }
                if (!errs.isEmpty()) return null;
                return () -> {
                    String from = u.getStatus();
                    if (a == Action.EOP_REQUEST) {
                        save(u, rec);
                        event(u, "EXTEND_OR_PAY_FORWARDED", from, u.getStatus(), rec, "extend-or-pay " + pd.getDemandRef() + " forwarded to the applicant side " + rec.getReceiverBic());
                        return;
                    }
                    pd.setResolutionMessageId(rec.getId());
                    pd.setResolvedAt(Instant.now());
                    if (a == Action.DEMAND_REFUSAL) {
                        pd.setStatus("REFUSED");
                        String why = pm.getVars().getOrDefault("f77J", pm.getVars().get("x_Nrrtv"));
                        pd.setReason(why == null ? null : why.replace("\n", " "));
                        demands.save(pd);
                        u.setMilestone("REFUSED");
                        save(u, rec);
                        event(u, "DEMAND_REFUSED", from, u.getStatus(), rec, "demand " + pd.getDemandRef() + " refused" + (why != null ? ": " + why.replace("\n", " ") : ""));
                    } else if (a == Action.DEMAND_WITHDRAWAL) {
                        pd.setStatus("WITHDRAWN");
                        demands.save(pd);
                        save(u, rec);
                        event(u, "DEMAND_WITHDRAWN", from, u.getStatus(), rec, "demand " + pd.getDemandRef() + " withdrawn by the presenter" + ("MT759".equals(pm.getShortType()) ? " (MT759 OTHERFNC withdrawal convention)" : ""));
                    } else {
                        pay(u, pd, pay != null ? pay : pd.getAmount(), rec, from);
                    }
                    out.put("ldg_demandStatus", pd.getStatus());
                };
            }
            case EOP_RESPONSE -> {
                return () -> {
                    save(u, rec);
                    event(u, "EXTEND_OR_PAY_RESPONSE", u.getStatus(), u.getStatus(), rec, "applicant side answered " + (f.status == null ? "" : f.status));
                };
            }
            case NON_EXT, NON_EXT_REQ -> {
                return () -> {
                    if (a == Action.NON_EXT) u.setNonExtensionNotified(true);
                    save(u, rec);
                    event(u, a == Action.NON_EXT ? "NON_EXTENSION_NOTIFIED" : "NON_EXTENSION_REQUESTED", u.getStatus(), u.getStatus(), rec,
                            a == Action.NON_EXT ? "issuer notified that the undertaking will not be extended beyond " + u.getExpiryDate() : "non-extension requested");
                };
            }
            case REDUCTION -> {
                currency(u, f, errs);
                BigDecimal red = f.amount;
                Demand linked = red == null ? null : demands.findByUndertakingIdOrderByIdAsc(u.getId()).stream()
                        .filter(d -> "PAID".equals(d.getStatus()) && !d.isReductionAdvised() && d.getPaidAmount() != null && d.getPaidAmount().compareTo(red) == 0)
                        .findFirst().orElse(null);
                if (linked == null && red != null && red.compareTo(u.getOutstanding()) > 0)
                    errs.add("BIZ-M01 reduction " + u.getCurrency() + " " + fmt(red) + " exceeds the outstanding amount " + u.getCurrency() + " " + fmt(u.getOutstanding()));
                if (f.outstandingAfter != null && red != null) {
                    BigDecimal expectedAfter = linked != null ? u.getOutstanding() : u.getOutstanding().subtract(red);
                    if (expectedAfter.signum() >= 0 && f.outstandingAfter.compareTo(expectedAfter) != 0)
                        errs.add("BIZ-M03 amount outstanding " + fmt(f.outstandingAfter) + " (34B) is inconsistent with the ledger: outstanding " + fmt(u.getOutstanding())
                                + (linked != null ? " (payment of demand " + linked.getDemandRef() + " already deducted)" : " - reduction " + fmt(red) + " = " + fmt(expectedAfter)));
                }
                if (!errs.isEmpty()) return null;
                return () -> {
                    String from = u.getStatus();
                    if (linked != null) {
                        linked.setReductionAdvised(true);
                        demands.save(linked);
                        u.setMilestone(u.getOutstanding().signum() == 0 ? "RELEASED" : "REDUCED");
                        save(u, rec);
                        event(u, "REDUCTION_ADVISED", from, u.getStatus(), rec, "reduction by " + fmt(red) + " following payment of demand " + linked.getDemandRef()
                                + " (already deducted at payment); outstanding " + fmt(u.getOutstanding()));
                        return;
                    }
                    BigDecimal r = red == null ? u.getOutstanding() : red;
                    u.setOutstanding(u.getOutstanding().subtract(r));
                    movement(u, u.getOutstanding().signum() == 0 ? "RELEASE" : "REDUCTION", r, rec, null, null);
                    u.setMilestone(u.getOutstanding().signum() == 0 ? "RELEASED" : "REDUCED");
                    save(u, rec);
                    event(u, u.getOutstanding().signum() == 0 ? "RELEASED" : "REDUCED", from, u.getStatus(), rec,
                            (red == null ? "full release" : "reduced by " + fmt(r)) + "; outstanding " + u.getCurrency() + " " + fmt(u.getOutstanding()));
                };
            }
            case TERMINATION -> {
                return () -> {
                    String from = u.getStatus();
                    BigDecimal r = u.getOutstanding();
                    u.setOutstanding(BigDecimal.ZERO);
                    movement(u, "TERMINATION", r, rec, null, null);
                    u.setMilestone("TERMINATED");
                    save(u, rec);
                    event(u, "TERMINATED", from, u.getStatus(), rec, "undertaking terminated" + (pm.getVars().get("mx_effectiveDate") != null ? " effective " + pm.getVars().get("mx_effectiveDate") : ""));
                };
            }
            case ACK, STATUS -> {
                return () -> {
                    String from = u.getStatus();
                    Set<String> sr = roles(u, rec.getSenderBic());
                    Set<String> rr = roles(u, rec.getReceiverBic());
                    boolean fromNonIssuer = sr.stream().anyMatch(NON_ISSUER::contains);
                    String sts = f.status;
                    if ("ISSUED".equals(u.getMilestone()) && fromNonIssuer && rr.contains("ISSUER") && (a == Action.ACK || "ACCP".equals(sts))) {
                        u.setMilestone("ADVISED");
                        save(u, rec);
                        event(u, "ADVISED", from, u.getStatus(), rec, "issuance acknowledged by " + rec.getSenderBic());
                        return;
                    }
                    Demand pd = pendingDemand(u);
                    if (a == Action.STATUS && sr.contains("ISSUER") && pd != null && "PAYM".equals(pd.getType()) && "DMND".equals(f.statusCategory) && "ACCP".equals(sts)) {
                        // MX demand-paid convention (no pacs.009 simulated): issuer's tsrv.019 DMND/ACCP = demand honoured
                        pd.setResolutionMessageId(rec.getId());
                        pd.setResolvedAt(Instant.now());
                        pay(u, pd, pd.getAmount(), rec, from);
                        return;
                    }
                    save(u, rec);
                    event(u, a == Action.ACK ? "ACKNOWLEDGED" : "STATUS_REPORT", from, u.getStatus(), rec,
                            a == Action.ACK ? "MT768 acknowledgement" : "status " + f.statusCategory + "/" + sts);
                };
            }
            case ANCILLARY -> {
                boolean toSut = cfg.byBic(rec.getReceiverBic()).map(p -> p.isSut()).orElse(false);
                boolean fromSut = cfg.byBic(rec.getSenderBic()).map(p -> p.isSut()).orElse(false);
                String f21 = pm.getVars().get("f21") == null ? null : pm.getVars().get("f21").trim();
                return () -> {
                    String text = f.narrative == null ? "" : ": " + trunc(f.narrative.replaceAll("\\s*\\R\\s*", " / "), 300);
                    if (fromSut && u.getOpenQueryRef() != null && (u.getOpenQueryRef().equals(f21) || rec.getReceiverBic().equals(u.getOpenQueryFrom()))) {
                        String q = u.getOpenQueryRef();
                        u.setOpenQueryRef(null);
                        u.setOpenQueryFrom(null);
                        u.setOpenQueryAt(null);
                        save(u, rec);
                        event(u, "QUERY_ANSWERED", u.getStatus(), u.getStatus(), rec, "MT759 answer to query " + q + text);
                        out.put("ldg_queryAnswered", "true");
                        return;
                    }
                    if (toSut && !fromSut && isQuery759(f)) {
                        boolean chaser = u.getOpenQueryRef() != null;
                        if (!chaser) {
                            u.setOpenQueryRef(trunc(pm.getVars().get("f20") == null ? rec.getTxRef() : pm.getVars().get("f20").trim(), 64));
                            u.setOpenQueryFrom(rec.getSenderBic());
                            u.setOpenQueryAt(Instant.now());
                        }
                        save(u, rec);
                        event(u, chaser ? "QUERY_CHASED" : "QUERY_RECEIVED", u.getStatus(), u.getStatus(), rec, (chaser ? "chaser for open query " + u.getOpenQueryRef() : "query " + u.getOpenQueryRef() + " awaiting the SUT's MT759 answer") + text);
                        out.put("ldg_queryOpen", "true");
                        return;
                    }
                    save(u, rec);
                    event(u, "ANCILLARY_MESSAGE", u.getStatus(), u.getStatus(), rec, "MT759 " + (f.code23H == null ? "" : f.code23H) + text);
                };
            }
            default -> {
                return null;
            }
        }
    }

    private void pay(Undertaking u, Demand pd, BigDecimal amt, MessageRecord rec, String from) {
        pd.setStatus("PAID");
        pd.setPaidAmount(amt);
        demands.save(pd);
        u.setOutstanding(u.getOutstanding().subtract(amt));
        movement(u, "PAYMENT", amt, rec, pd.getDemandRef(), null);
        u.setMilestone("PAID");
        save(u, rec);
        event(u, "DEMAND_PAID", from, u.getStatus(), rec, "demand " + pd.getDemandRef() + " paid " + u.getCurrency() + " " + fmt(amt) + "; outstanding " + fmt(u.getOutstanding()));
    }

    private void currency(Undertaking u, Facts f, List<String> errs) {
        if (f.currency != null && u.getCurrency() != null && !f.currency.equals(u.getCurrency()))
            errs.add("BIZ-C01 currency mismatch: message uses " + f.currency + " but undertaking " + u.getRef() + " is in " + u.getCurrency());
    }

    private void applyAmendment(Undertaking u, Amendment am, MessageRecord rec) {
        if (am.getIncrease() != null) {
            u.setAmount(u.getAmount().add(am.getIncrease()));
            u.setOutstanding(u.getOutstanding().add(am.getIncrease()));
            movement(u, "INCREASE", am.getIncrease(), rec, null, "amendment " + am.getSeqNo());
        }
        if (am.getDecrease() != null) {
            u.setAmount(u.getAmount().subtract(am.getDecrease()));
            u.setOutstanding(u.getOutstanding().subtract(am.getDecrease()).max(BigDecimal.ZERO));
            movement(u, "DECREASE", am.getDecrease(), rec, null, "amendment " + am.getSeqNo());
        }
        if (am.getNewExpiryDate() != null) u.setExpiryDate(am.getNewExpiryDate());
    }

    private static String amendmentText(Amendment am) {
        List<String> l = new ArrayList<>();
        if (am.getIncrease() != null) l.add("increase " + am.getCurrency() + " " + fmt(am.getIncrease()));
        if (am.getDecrease() != null) l.add("decrease " + am.getCurrency() + " " + fmt(am.getDecrease()));
        if (am.getNewExpiryDate() != null) l.add("new expiry " + am.getNewExpiryDate());
        return l.isEmpty() ? "(no amount/expiry change)" : String.join(", ", l);
    }

    private void issue(Undertaking existing, Facts f, ParsedMessage pm, MessageRecord rec, Map<String, String> out) {
        Undertaking u = existing != null ? existing : new Undertaking();
        String from = existing == null ? null : existing.getStatus();
        u.setRef(f.ref);
        u.setFormat(pm.getFormat());
        u.setFormOfUndertaking(f.form);
        u.setRules(f.rules);
        u.setCurrency(f.currency);
        u.setOriginalAmount(f.amount);
        u.setAmount(f.amount);
        u.setOutstanding(f.amount);
        u.setIssueDate(f.issueDate != null ? f.issueDate : clock.today());
        u.setExpiryType(f.expiryType != null ? f.expiryType : f.expiryDate != null ? "FIXD" : null);
        u.setExpiryDate(f.expiryDate);
        if (f.beneficiary != null) u.setBeneficiary(trunc(f.beneficiary.replace("\n", " / "), 600));
        if (f.applicant != null) u.setApplicant(trunc(f.applicant.replace("\n", " / "), 600));
        u.setIssuerBic(rec.getSenderBic());
        u.setMilestone("ISSUED");
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setOriginMessageId(rec.getId());
        save(u, rec);
        String sender = rec.getSenderBic(), receiver = rec.getReceiverBic();
        addParty(u, sender, "ISSUER", "sender of " + pm.getMsgType());
        if (f.issuerBic != null && !f.issuerBic.equals(sender))
            event(u, "NOTE", null, null, rec, "issuer field (" + f.issuerBic + ") differs from the sender " + sender + "; sender treated as issuer");
        if (receiver.equals(f.beneficiaryBic)) addParty(u, receiver, "BENEFICIARY", "receiver of " + pm.getMsgType() + " (= beneficiary)");
        else addParty(u, receiver, "ADVISING", "receiver of " + pm.getMsgType());
        if (f.advisingBic != null && !f.advisingBic.equals(receiver) && cfg.byBic(f.advisingBic).isPresent()) addParty(u, f.advisingBic, "ADVISING", "advising bank field");
        if (f.secondAdvisingBic != null && cfg.byBic(f.secondAdvisingBic).isPresent()) addParty(u, f.secondAdvisingBic, "SECOND_ADVISING", "second advising bank field");
        if (f.beneficiaryBic != null && !f.beneficiaryBic.equals(receiver) && cfg.byBic(f.beneficiaryBic).isPresent()) addParty(u, f.beneficiaryBic, "BENEFICIARY", "beneficiary field (BIC)");
        if (f.applicantBic != null && cfg.byBic(f.applicantBic).isPresent()) addParty(u, f.applicantBic, "APPLICANT", "applicant field (BIC)");
        movement(u, "ISSUE", f.amount, rec, null, null);
        event(u, "ISSUED", from, u.getStatus(), rec, pm.getMsgType() + " " + u.getFormOfUndertaking() + " " + u.getCurrency() + " " + fmt(u.getAmount())
                + " expiry " + u.getExpiryType() + " " + u.getExpiryDate() + " rules " + u.getRules() + " issuer " + sender + " -> " + receiver);
    }

    private void applyApplication(Facts f, ParsedMessage pm, MessageRecord rec, Map<String, String> out) {
        Undertaking u = new Undertaking();
        u.setRef(f.ref);
        u.setFormat(pm.getFormat());
        u.setFormOfUndertaking(f.form);
        u.setRules(f.rules);
        u.setCurrency(f.currency);
        u.setOriginalAmount(f.amount);
        u.setAmount(f.amount);
        u.setOutstanding(f.amount == null ? BigDecimal.ZERO : f.amount);
        u.setExpiryDate(f.expiryDate);
        u.setExpiryType(f.expiryType);
        if (f.beneficiary != null) u.setBeneficiary(trunc(f.beneficiary, 600));
        if (f.applicant != null) u.setApplicant(trunc(f.applicant, 600));
        String issuer = f.issuerBic != null && cfg.byBic(f.issuerBic).isPresent() ? f.issuerBic : rec.getReceiverBic();
        u.setIssuerBic(issuer);
        u.setMilestone("APPLIED");
        u.setCreatedAt(Instant.now());
        u.setOriginMessageId(rec.getId());
        save(u, rec);
        addParty(u, rec.getSenderBic(), "APPLICANT", "sender of tsin.005 (instructing party)");
        addParty(u, issuer, "ISSUER", "requested issuer (tsin.005)");
        event(u, "APPLICATION_RECEIVED", null, u.getStatus(), rec, "application " + f.ref + " " + u.getCurrency() + " " + fmt(u.getAmount()) + " from " + rec.getSenderBic());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    public Amendment pendingAmendment(Undertaking u) {
        return amendments.findByUndertakingIdOrderByIdAsc(u.getId()).stream().filter(a -> "PENDING".equals(a.getStatus())).reduce((a, b) -> b).orElse(null);
    }

    public Demand pendingDemand(Undertaking u) {
        return demands.findByUndertakingIdOrderByIdAsc(u.getId()).stream().filter(d -> "RECEIVED".equals(d.getStatus())).reduce((a, b) -> b).orElse(null);
    }

    private Demand findPendingDemand(Undertaking u, String ref) {
        List<Demand> pend = demands.findByUndertakingIdOrderByIdAsc(u.getId()).stream().filter(d -> "RECEIVED".equals(d.getStatus())).toList();
        if (ref != null) {
            Optional<Demand> m = pend.stream().filter(d -> ref.equals(d.getDemandRef())).findFirst();
            return m.orElse(null);
        }
        // MT202 carries no demand reference: use the latest pending demand
        return pend.isEmpty() ? null : pend.get(pend.size() - 1);
    }

    public Set<String> roles(Undertaking u, String bic) {
        Set<String> s = new TreeSet<>();
        for (UndertakingParty p : parties.findByUndertakingIdOrderByIdAsc(u.getId())) if (p.getBic().equals(bic)) s.add(p.getRole());
        return s;
    }

    private String partyList(Undertaking u) {
        StringBuilder sb = new StringBuilder();
        for (UndertakingParty p : parties.findByUndertakingIdOrderByIdAsc(u.getId())) sb.append(sb.isEmpty() ? "" : ", ").append(p.getRole()).append('=').append(p.getBic());
        return sb.toString();
    }

    public UndertakingParty addParty(Undertaking u, String bic, String role, String source) {
        for (UndertakingParty p : parties.findByUndertakingIdOrderByIdAsc(u.getId()))
            if (p.getBic().equals(bic) && p.getRole().equals(role)) return p;
        UndertakingParty p = new UndertakingParty();
        p.setUndertakingId(u.getId());
        p.setBic(bic);
        p.setRole(role);
        p.setSource(source);
        p.setParticipantId(cfg.byBic(bic).map(Participant::getId).orElse(null));
        return parties.save(p);
    }

    private void movement(Undertaking u, String type, BigDecimal amt, MessageRecord rec, String demandRef, String note) {
        Movement m = new Movement();
        m.setUndertakingId(u.getId());
        m.setType(type);
        m.setCurrency(u.getCurrency());
        m.setAmount(amt);
        m.setOutstandingAfter(u.getOutstanding());
        m.setMessageId(rec == null ? null : rec.getId());
        m.setDemandRef(demandRef);
        m.setNote(note);
        m.setAt(Instant.now());
        movements.save(m);
    }

    /** recompute effective status and persist */
    private void save(Undertaking u, MessageRecord rec) {
        if (u.getId() == null) {
            u.setStatus(u.getMilestone());
            u.setUpdatedAt(Instant.now());
            repo.save(u);
        }
        u.setStatus(derive(u));
        u.setUpdatedAt(Instant.now());
        if (rec != null) u.setLastMessageId(rec.getId());
        repo.save(u);
    }

    String derive(Undertaking u) {
        String m = u.getMilestone();
        if (TERMINAL.contains(m) || "APPLIED".equals(m)) return m;
        Demand d = u.getId() == null ? null : pendingDemand(u);
        if (d != null) return "PAEX".equals(d.getType()) ? "EXTEND_OR_PAY_PENDING" : "DEMAND_RECEIVED";
        if (u.getId() != null && pendingAmendment(u) != null) return "AMENDMENT_PENDING";
        return m;
    }

    private void event(Undertaking u, String type, String from, String to, MessageRecord rec, String detail) {
        LedgerEvent e = new LedgerEvent();
        e.setUndertakingId(u == null ? null : u.getId());
        e.setRef(u == null ? (rec == null ? null : rec.getTxRef()) : u.getRef());
        e.setAt(Instant.now());
        e.setBusinessDate(clock.today());
        e.setEventType(type);
        e.setFromStatus(from);
        e.setToStatus(to);
        if (rec != null) {
            e.setMessageId(rec.getId());
            e.setMsgType(rec.getMsgType());
            e.setSenderBic(rec.getSenderBic());
            e.setReceiverBic(rec.getReceiverBic());
        }
        e.setDetail(trunc(detail, 4000));
        events.save(e);
    }

    private void orphan(String ref, String type, MessageRecord rec, String detail) {
        LedgerEvent e = new LedgerEvent();
        e.setRef(ref);
        e.setAt(Instant.now());
        e.setBusinessDate(clock.today());
        e.setEventType(type);
        e.setMessageId(rec.getId());
        e.setMsgType(rec.getMsgType());
        e.setSenderBic(rec.getSenderBic());
        e.setReceiverBic(rec.getReceiverBic());
        e.setDetail(trunc(detail, 4000));
        events.save(e);
    }

    private boolean due(Undertaking u) {
        return !TERMINAL.contains(u.getMilestone()) && !"APPLIED".equals(u.getMilestone()) && "FIXD".equals(u.getExpiryType())
                && u.getExpiryDate() != null && clock.today().isAfter(u.getExpiryDate()) && pendingDemand(u) == null;
    }

    private void expireIfDue(Undertaking u, MessageRecord rec) {
        if (!due(u)) return;
        String from = u.getStatus();
        u.setMilestone("EXPIRED");
        save(u, null);
        event(u, "EXPIRED", from, u.getStatus(), null, "expired: expiry date " + u.getExpiryDate() + " passed (business date " + clock.today() + ")");
    }

    /** Expire undertakings whose fixed expiry date has passed (not while a demand is under examination). */
    @Scheduled(fixedDelayString = "${sim.expiry-sweep-ms:60000}", initialDelay = 15000)
    public synchronized int sweep() {
        Integer n = tx().execute(s -> {
            int c = 0;
            for (Undertaking u : repo.findAll()) if (due(u)) { expireIfDue(u, null); c++; }
            for (TradeLedger t : trade) c += t.sweep();
            return c;
        });
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------------------------------------ context / queries

    /** Variables describing the current ledger state, for templates (ledger context / memory). */
    public Map<String, String> context(Undertaking u) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("ref", u.getRef());
        v.put("ldg_status", u.getStatus());
        if (u.getCurrency() != null) v.put("currency", u.getCurrency());
        if (u.getOutstanding() != null) {
            v.put("outstanding", swift(u.getOutstanding()));
            v.put("outstandingIso", iso(u.getOutstanding()));
        }
        if (u.getAmount() != null) v.put("ldg_amount", swift(u.getAmount()));
        if (u.getIssuerBic() != null) v.put("ldg_issuerBic", u.getIssuerBic());
        if (u.getFormOfUndertaking() != null) v.put("ldg_form", u.getFormOfUndertaking());
        if (u.getExpiryDate() != null) {
            v.put("ldg_expiry6", u.getExpiryDate().format(YYMMDD));
            v.put("ldg_expiryIso", u.getExpiryDate().toString());
            v.put("ldg_nextExpiry6", u.getExpiryDate().plusYears(1).format(YYMMDD));
            v.put("ldg_nextExpiryIso", u.getExpiryDate().plusYears(1).toString());
        }
        if (u.getOutstanding() != null) {
            BigDecimal half = u.getOutstanding().divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            v.put("ldg_halfOutstanding", swift(half));
            v.put("ldg_halfOutstandingIso", iso(half));
            v.put("ldg_remainingAfterHalf", swift(u.getOutstanding().subtract(half)));
            v.put("ldg_remainingAfterHalfIso", iso(u.getOutstanding().subtract(half)));
        }
        v.put("ldg_family", "UNDERTAKING");
        if (u.getOpenQueryRef() != null) {
            v.put("ldg_openQueryRef", u.getOpenQueryRef());
            v.put("ldg_openQueryFrom", u.getOpenQueryFrom());
        }
        if (u.getId() != null) {
            v.put("nextAmendmentNo", String.valueOf(amendments.findByUndertakingIdOrderByIdAsc(u.getId()).size() + 1));
            Amendment pa = pendingAmendment(u);
            if (pa != null) v.put("ldg_pendingAmendmentNo", String.valueOf(pa.getSeqNo()));
            Demand pd = pendingDemand(u);
            if (pd != null) {
                v.put("ldg_pendingDemandId", pd.getDemandRef());
                v.put("ldg_pendingDemandType", pd.getType());
                v.put("ldg_pendingDemandAmount", swift(pd.getAmount()));
                v.put("ldg_pendingDemandAmountIso", iso(pd.getAmount()));
                if (pd.getRequestedExpiry() != null) {
                    v.put("ldg_requestedExpiry6", pd.getRequestedExpiry().format(YYMMDD));
                    v.put("ldg_requestedExpiryIso", pd.getRequestedExpiry().toString());
                }
            }
        }
        return v;
    }

    public Optional<Undertaking> find(String ref) {
        return ref == null ? Optional.empty() : repo.findByRef(ref);
    }

    /** Pick the most recently updated undertaking on which {@code from} may send a message of the given type to {@code to}. */
    public Optional<Undertaking> suggest(String shortType, String fromBic, String toBic) {
        Action a = classify(shortType);
        if (a == Action.ISSUE || a == Action.APPLY || a == Action.OTHER) return Optional.empty();
        for (Undertaking u : repo.findAllByOrderByUpdatedAtDesc()) {
            if (TERMINAL.contains(u.getStatus()) && a != Action.ACK && a != Action.STATUS) continue;
            if (!roleAndStateErrors(u, a, fromBic, toBic).isEmpty()) continue;
            if (a == Action.DEMAND && (pendingDemand(u) != null || u.getOutstanding().signum() <= 0)) continue;
            if (a == Action.AMEND && pendingAmendment(u) != null) continue;
            if ((a == Action.DEMAND_REFUSAL || a == Action.SETTLEMENT) && pendingDemand(u) == null) continue;
            if (a == Action.AMEND_RESP && pendingAmendment(u) == null) continue;
            return Optional.of(u);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------------ multi-family API

    public Optional<TradeLedger> family(String fam) {
        return trade.stream().filter(t -> t.family().equalsIgnoreCase(fam)).findFirst();
    }

    /** ledger context of any family (undertaking, documentary credit / presentation / claim, collection) */
    public synchronized Optional<Map<String, String>> contextAny(String ref) {
        if (ref == null) return Optional.empty();
        Optional<Undertaking> u = repo.findByRef(ref);
        if (u.isPresent()) return Optional.of(context(u.get()));
        return tx().execute(s -> {
            for (TradeLedger t : trade) {
                Optional<Map<String, String>> c = t.context(ref);
                if (c.isPresent()) return c;
            }
            return Optional.<Map<String, String>>empty();
        });
    }

    /** Dry run of the business checks for a message that is NOT sent: same rules as delivery, transaction always rolled back. */
    public synchronized Outcome dryRun(MessageRecord rec, ParsedMessage pm, boolean receiverIsSut) {
        try {
            return tx().execute(s -> {
                s.setRollbackOnly();
                return doProcess(rec, pm, receiverIsSut, false);
            });
        } catch (Exception e) {
            return new Outcome("INFO", List.of("ledger dry-run error: " + e.getMessage()), pm.getTxRef(), null, null, Map.of());
        }
    }

    /** family of a reference: UNDERTAKING, LC or COLL (also resolves presentation / claim references), empty if unknown */
    public synchronized Optional<String> familyOfRef(String ref) {
        if (ref == null) return Optional.empty();
        if (repo.findByRef(ref).isPresent()) return Optional.of("UNDERTAKING");
        for (TradeLedger t : trade) if (Boolean.TRUE.equals(tx().execute(s -> t.exists(ref) || t.context(ref).isPresent()))) return Optional.of(t.family());
        return Optional.empty();
    }

    /** ledger detail of any family */
    public Optional<Map<String, Object>> detailAny(String ref, boolean includeRaw) {
        Optional<String> f = familyOfRef(ref);
        if (f.isEmpty()) return Optional.empty();
        return "UNDERTAKING".equals(f.get()) ? detail(ref, includeRaw) : tradeDetail(f.get(), ref, includeRaw);
    }

    public boolean existsAny(String ref) {
        if (ref == null) return false;
        if (repo.findByRef(ref).isPresent()) return true;
        for (TradeLedger t : trade) if (t.exists(ref)) return true;
        return false;
    }

    /**
     * Kickoff support: context of a record on which {@code from} may send {@code shortType} to {@code to}.
     * {@code select} (optional) is a ledger-situation hint such as LC_PRESENT or COLL_PAYABLE; it also decides the family.
     */
    public synchronized Optional<Map<String, String>> suggestContext(String select, String shortType, String from, String to) {
        return tx().execute(s -> {
            String key = select != null && !select.isBlank() ? select : shortType;
            boolean lcKey = key.startsWith("LC_") || LcLedgerService.OWNED.contains(key);
            boolean collKey = key.startsWith("COLL_") || CollectionLedgerService.OWNED.contains(key);
            if (lcKey || collKey) {
                for (TradeLedger t : trade)
                    if (lcKey && "LC".equals(t.family()) || collKey && "COLL".equals(t.family())) return t.suggest(key, from, to);
                return Optional.<Map<String, String>>empty();
            }
            if (key.startsWith("UND_")) {
                for (Undertaking u : repo.findAllByOrderByUpdatedAtDesc()) {
                    if (TERMINAL.contains(u.getStatus())) continue;
                    Set<String> fr = roles(u, from), tr = roles(u, to);
                    if (fr.isEmpty() || tr.isEmpty()) continue;
                    boolean ok = switch (key) {
                        case "UND_DEMAND_PENDING" -> pendingDemand(u) != null && fr.contains("ISSUER") == false;
                        case "UND_AMENDMENT_PENDING" -> pendingAmendment(u) != null;
                        case "UND_ISSUER_TO_ADVISING" -> fr.contains("ISSUER") && tr.stream().anyMatch(NON_ISSUER::contains);
                        case "UND_OPEN_QUERY" -> u.getOpenQueryRef() != null && from.equals(u.getOpenQueryFrom());
                        case "UND_PARTIES" -> true;
                        case "UND_REDUCIBLE" -> fr.contains("ISSUER") && u.getOutstanding() != null && u.getOutstanding().signum() > 0 && pendingDemand(u) == null;
                        default -> false;
                    };
                    if (ok) return Optional.of(context(u));
                }
                return Optional.<Map<String, String>>empty();
            }
            Optional<Undertaking> u = suggest(shortType, from, to);
            if (u.isPresent()) return Optional.of(context(u.get()));
            if ("MT759".equals(shortType) || "MT799".equals(shortType) || "MT202".equals(shortType) || "MT103".equals(shortType)) {
                for (TradeLedger t : trade) {
                    Optional<Map<String, String>> c = t.suggest(shortType, from, to);
                    if (c.isPresent()) return c;
                }
            }
            return Optional.<Map<String, String>>empty();
        });
    }

    public synchronized List<Map<String, Object>> tradeList(String fam, String status, String party) {
        TradeLedger t = family(fam).orElseThrow();
        return tx().execute(s -> t.list(status, party));
    }

    public synchronized Optional<Map<String, Object>> tradeDetail(String fam, String ref, boolean includeRaw) {
        TradeLedger t = family(fam).orElseThrow();
        return t.detail(ref, includeRaw);
    }

    public synchronized Object tradeAddParty(String fam, String ref, String bic, String role) {
        TradeLedger t = family(fam).orElseThrow();
        return tx().execute(s -> t.addParty(ref, bic, role));
    }

    public synchronized long tradeClear(String fam) {
        TradeLedger t = family(fam).orElseThrow();
        Long n = tx().execute(s -> t.clear());
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> list(String status, String party) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Undertaking u : repo.findAllByOrderByUpdatedAtDesc()) {
            if (status != null && !status.equalsIgnoreCase(u.getStatus())) continue;
            List<UndertakingParty> ps = parties.findByUndertakingIdOrderByIdAsc(u.getId());
            if (party != null && ps.stream().noneMatch(p -> party.equalsIgnoreCase(p.getBic()) || party.equalsIgnoreCase(p.getParticipantId()))) continue;
            Map<String, Object> m = summary(u);
            m.put("parties", ps.stream().map(p -> p.getRole() + "=" + p.getBic() + (p.getParticipantId() != null ? "(" + p.getParticipantId() + ")" : "")).toList());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> summary(Undertaking u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", u.getRef());
        m.put("status", u.getStatus());
        m.put("milestone", u.getMilestone());
        m.put("form", u.getFormOfUndertaking());
        m.put("rules", u.getRules());
        m.put("format", u.getFormat());
        m.put("currency", u.getCurrency());
        m.put("originalAmount", u.getOriginalAmount());
        m.put("amount", u.getAmount());
        m.put("outstanding", u.getOutstanding());
        m.put("issueDate", u.getIssueDate());
        m.put("expiryType", u.getExpiryType());
        m.put("expiryDate", u.getExpiryDate());
        m.put("issuerBic", u.getIssuerBic());
        m.put("beneficiary", u.getBeneficiary());
        m.put("applicant", u.getApplicant());
        m.put("nonExtensionNotified", u.isNonExtensionNotified());
        m.put("openQueryRef", u.getOpenQueryRef());
        m.put("openQueryFrom", u.getOpenQueryFrom());
        m.put("createdAt", u.getCreatedAt());
        m.put("updatedAt", u.getUpdatedAt());
        return m;
    }

    public Optional<Map<String, Object>> detail(String ref, boolean includeRaw) {
        return repo.findByRef(ref).map(u -> {
            Map<String, Object> m = summary(u);
            m.put("parties", parties.findByUndertakingIdOrderByIdAsc(u.getId()));
            m.put("amendments", amendments.findByUndertakingIdOrderByIdAsc(u.getId()));
            m.put("demands", demands.findByUndertakingIdOrderByIdAsc(u.getId()));
            m.put("movements", movements.findByUndertakingIdOrderByIdAsc(u.getId()));
            List<LedgerEvent> hist = new ArrayList<>(events.findByRefAndUndertakingIdIsNullOrderByIdAsc(ref));
            hist.addAll(events.findByUndertakingIdOrderByIdAsc(u.getId()));
            hist.sort(Comparator.comparing(LedgerEvent::getId));
            m.put("history", hist);
            List<MessageRecord> msgs = messages.findForRef(ref);
            if (!includeRaw) msgs.forEach(x -> { x.setRaw(null); x.setDeliveredRaw(null); });
            m.put("messages", msgs);
            m.put("context", context(u));
            return m;
        });
    }

    public List<LedgerEvent> orphanEvents(String ref) {
        return events.findByRefAndUndertakingIdIsNullOrderByIdAsc(ref);
    }

    public synchronized UndertakingParty adminAddParty(String ref, String bic, String role) {
        Undertaking u = repo.findByRef(ref).orElseThrow(() -> new NoSuchElementException("unknown undertaking " + ref));
        if (!Set.of("ISSUER", "ADVISING", "SECOND_ADVISING", "BENEFICIARY", "APPLICANT").contains(role))
            throw new IllegalArgumentException("role must be ISSUER, ADVISING, SECOND_ADVISING, BENEFICIARY or APPLICANT");
        return tx().execute(s -> {
            UndertakingParty p = addParty(u, bic, role, "admin API (out-of-band relationship)");
            event(u, "PARTY_ADDED", u.getStatus(), u.getStatus(), null, role + "=" + bic + " added via admin API");
            return p;
        });
    }

    public synchronized long clear() {
        long n = repo.count();
        tx().executeWithoutResult(s -> {
            events.deleteAllInBatch();
            movements.deleteAllInBatch();
            demands.deleteAllInBatch();
            amendments.deleteAllInBatch();
            parties.deleteAllInBatch();
            repo.deleteAllInBatch();
        });
        return n;
    }

    // ------------------------------------------------------------------------------------------------ formatting

    public static String swift(BigDecimal b) {
        return b == null ? null : b.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    public static String iso(BigDecimal b) {
        return b == null ? null : b.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String fmt(BigDecimal b) {
        return swift(b);
    }

    private static String trunc(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }
}
