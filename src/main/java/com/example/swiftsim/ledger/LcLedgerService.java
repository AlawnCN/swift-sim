package com.example.swiftsim.ledger;

import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.domain.MessageRepository;
import com.example.swiftsim.parse.ParsedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.swiftsim.ledger.TradeSupport.*;

/**
 * Ledger + lifecycle state machine of documentary credits (Category 7, UCP 600 / URR 725 semantics, simplified).
 * Statuses: PRE_ADVISED, ISSUED, ADVISED, CONFIRMED, AMENDMENT_PENDING, AMENDED, DOCS_PRESENTED, DISCREPANT, REFUSED,
 * AUTHORISED, ACCEPTED, DISCHARGED, PAID, REIMBURSED, TRANSFERRED, CANCELLED, EXPIRED. Business codes BIZ-xxx are simulator codes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LcLedgerService implements TradeLedger {
    static final String K = "LC";
    private final DocCreditRepository repo;
    private final LcAmendmentRepository amendments;
    private final LcPresentationRepository items;
    private final MessageRepository messages;
    private final TradeSupport ts;
    private final BankingCalendar calendar;

    public static final Set<String> TERMINAL = Set.of("CANCELLED", "EXPIRED");
    static final Set<String> NON_ISSUER = Set.of("ADVISING", "SECOND_ADVISING", "CONFIRMING", "NOMINATED", "REQUESTED_CONFIRMER", "TRANSFERRING",
            "SECOND_BENEFICIARY_BANK", "PRESENTING", "CLAIMING");
    static final Set<String> ROLES = new TreeSet<>(Set.of("ISSUER", "ADVISING", "SECOND_ADVISING", "CONFIRMING", "NOMINATED", "REQUESTED_CONFIRMER",
            "TRANSFERRING", "SECOND_BENEFICIARY_BANK", "PRESENTING", "CLAIMING", "REIMBURSING", "APPLICANT_BANK"));
    static final Set<String> OWNED = Set.of("MT700", "MT701", "MT705", "MT707", "MT708", "MT710", "MT711", "MT720", "MT721", "MT730", "MT732",
            "MT734", "MT740", "MT742", "MT744", "MT747", "MT750", "MT752", "MT754", "MT756");
    static final Set<String> PENDING_ITEM = Set.of("PRESENTED", "DISCREPANT");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    enum A { ISSUE, PRE_ADVICE, CONTINUATION, AMEND, THIRD_BANK, TRANSFER, ACK, DISCHARGE, REFUSAL, REIMB_AUTH, CLAIM, CLAIM_REJECT, REIMB_AMEND,
        DISCREPANCY, AUTHORISATION, PRESENTATION, REIMBURSEMENT, FREE, SETTLEMENT }

    static A action(String t) {
        return switch (t) {
            case "MT700" -> A.ISSUE;
            case "MT705" -> A.PRE_ADVICE;
            case "MT701", "MT708", "MT711", "MT721" -> A.CONTINUATION;
            case "MT707" -> A.AMEND;
            case "MT710" -> A.THIRD_BANK;
            case "MT720" -> A.TRANSFER;
            case "MT730" -> A.ACK;
            case "MT732" -> A.DISCHARGE;
            case "MT734" -> A.REFUSAL;
            case "MT740" -> A.REIMB_AUTH;
            case "MT742" -> A.CLAIM;
            case "MT744" -> A.CLAIM_REJECT;
            case "MT747" -> A.REIMB_AMEND;
            case "MT750" -> A.DISCREPANCY;
            case "MT752" -> A.AUTHORISATION;
            case "MT754" -> A.PRESENTATION;
            case "MT756" -> A.REIMBURSEMENT;
            case "MT202", "MT103", "pacs.008", "pacs.009" -> A.SETTLEMENT;
            default -> A.FREE;
        };
    }

    @Override public String family() { return K; }

    @Override public boolean owns(String shortType) { return OWNED.contains(shortType); }

    @Override
    public boolean references(ParsedMessage pm) {
        return resolve(A.FREE, pm.getVars(), pm).lc != null;
    }

    record Res(DocCredit lc, LcPresentation item, String ref) {}

    private LcPresentation item(String ref) {
        if (ref == null) return null;
        List<LcPresentation> l = items.findByPresentationRef(ref.trim());
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    private DocCredit lc(String ref) {
        return ref == null ? null : repo.findByRef(ref.trim()).orElse(null);
    }

    private static String nn(String s) {
        return s == null || s.isBlank() || "NONREF".equals(s.trim()) ? null : s.trim();
    }

    Res resolve(A a, Map<String, String> v, ParsedMessage pm) {
        String f20 = nn(v.get("f20")), f21 = nn(v.get("f21")), f23 = nn(v.get("f23"));
        switch (a) {
            case ISSUE, PRE_ADVICE, REIMB_AUTH, REIMB_AMEND -> { return new Res(lc(f20), null, f20); }
            case AMEND -> {
                String r = f23 != null ? f23 : f20;
                DocCredit l = lc(r);
                if (l == null && f23 != null) { l = lc(f20); if (l != null) r = f20; }
                return new Res(l, null, r);
            }
            case CONTINUATION -> {
                String r = "MT708".equals(pm.getShortType()) ? (f23 != null ? f23 : f20) : "MT701".equals(pm.getShortType()) ? f20 : f21;
                return new Res(lc(r), null, r);
            }
            case THIRD_BANK, TRANSFER, ACK -> {
                DocCredit l = lc(f21);
                return l != null ? new Res(l, null, f21) : new Res(lc(f20), null, f21 != null ? f21 : f20);
            }
            case PRESENTATION, DISCREPANCY, CLAIM -> { return new Res(lc(f21), item(f20), f21); }
            case AUTHORISATION, REFUSAL, DISCHARGE, REIMBURSEMENT, CLAIM_REJECT -> {
                // MT744: 21 = documentary credit number, 21A = claiming bank's reference of the claim
                LcPresentation it = a == A.CLAIM_REJECT && nn(v.get("f21A")) != null ? item(nn(v.get("f21A"))) : item(f21);
                if (a == A.CLAIM_REJECT && it == null) { DocCredit l = lc(f21); return new Res(l, null, l != null ? l.getRef() : f21); }
                DocCredit l = it != null ? repo.findById(it.getLcId()).orElse(null) : lc(f20);
                return new Res(l, it, l != null ? l.getRef() : f21);
            }
            default -> {
                for (String c : new String[]{f21, f23, f20, nn(pm.getTxRef())}) {
                    LcPresentation it = item(c);
                    if (it != null) return new Res(repo.findById(it.getLcId()).orElse(null), it, c);
                    DocCredit l = lc(c);
                    if (l != null) return new Res(l, null, c);
                }
                return new Res(null, null, pm.getTxRef());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ processing

    @Override
    public LedgerService.Outcome process(MessageRecord rec, ParsedMessage pm, boolean receiverIsSut, boolean businessReject) {
        A a = action(pm.getShortType());
        Map<String, String> v = pm.getVars();
        Res r = resolve(a, v, pm);
        DocCredit lc = r.lc;
        LcPresentation it = r.item;
        String ref = lc != null ? lc.getRef() : r.ref;
        Map<String, String> out = new LinkedHashMap<>();
        out.put("ldg_family", K);
        if (businessReject) {
            ts.event(K, lc == null ? null : lc.getId(), ref, "BUSINESS_REJECT_SENT", st(lc), st(lc), rec, "business reject sent by " + rec.getSenderBic() + " to " + rec.getReceiverBic());
            return outcome("INFO", List.of(), ref, lc, it, null, out);
        }
        if (Boolean.TRUE.equals(rec.getPossibleDuplicate()) && rec.getDuplicateOf() != null) {
            MessageRecord orig = messages.findById(rec.getDuplicateOf()).orElse(null);
            if (orig != null && orig.getBusinessStatus() != null) {
                ts.event(K, lc == null ? null : lc.getId(), ref, "DUPLICATE_IGNORED", st(lc), st(lc), rec, "PDE copy of message #" + orig.getId() + " (already processed: " + orig.getBusinessStatus() + ") - not processed again");
                return outcome("DUPLICATE_IGNORED", List.of(), ref, lc, it, st(lc), out);
            }
        }
        if (lc != null) expireIfDue(lc);
        String from = st(lc);
        List<String> errs = new ArrayList<>();
        String sender = rec.getSenderBic(), receiver = rec.getReceiverBic();
        Runnable apply = null;
        LocalDate today = ts.clock.today();

        switch (a) {
            case ISSUE, PRE_ADVICE -> {
                if (ref == null) errs.add("BIZ-V00 documentary credit number (:20:) missing");
                else if (lc != null && !"PRE_ADVISED".equals(lc.getMilestone()))
                    errs.add("BIZ-U02 duplicate documentary credit number " + ref + ": already known (status " + lc.getStatus() + ", issuer " + lc.getIssuerBic() + ")");
                else if (lc != null && a == A.PRE_ADVICE) errs.add("BIZ-U02 documentary credit " + ref + " was already pre-advised");
                if (lc != null && "PRE_ADVISED".equals(lc.getMilestone()) && a == A.ISSUE && !sender.equals(lc.getIssuerBic()))
                    errs.add("BIZ-R03 " + sender + " is not the bank that pre-advised " + ref + " (" + lc.getIssuerBic() + ")");
                issueChecks(v, today, errs);
                if (errs.isEmpty()) {
                    final DocCredit ex = lc;
                    apply = () -> issue(ex, a == A.PRE_ADVICE ? "PRE_ADVISED" : "ISSUED", pm, rec);
                }
            }
            case THIRD_BANK -> {
                if (ref == null) errs.add("BIZ-V00 documentary credit number (:21:) missing");
                if (lc == null) {
                    issueChecks(v, today, errs);
                    if (errs.isEmpty()) apply = () -> issue(null, "ISSUED", pm, rec);
                } else {
                    Set<String> sr = roles(lc, sender);
                    if (sr.isEmpty()) errs.add("BIZ-R01 sender " + sender + " is not a party of documentary credit " + lc.getRef() + " (parties: " + ts.partyList(K, lc.getId()) + ")");
                    else if (sr.contains("ISSUER")) errs.add("BIZ-R03 MT710 (advice of a third bank's credit) must come from an advising bank, not from the issuer " + sender);
                    terminal(lc, a, errs);
                    if (errs.isEmpty()) apply = () -> {
                        ts.addParty(K, lc.getId(), receiver, "SECOND_ADVISING", "receiver of MT710");
                        save(lc, rec);
                        ts.event(K, lc.getId(), lc.getRef(), "ADVISED_THROUGH", from, lc.getStatus(), rec, "credit advised through second advising bank " + receiver + " (advising bank ref " + v.get("f20") + ")");
                    };
                }
            }
            default -> {
                if (lc == null) {
                    if (a == A.REIMB_AUTH) {
                        if (ref == null) errs.add("BIZ-V00 documentary credit number (:20:) missing");
                        issueChecks(v, today, errs);
                        if (errs.isEmpty()) apply = () -> { DocCredit n = issue(null, "ISSUED", pm, rec); reimbAuth(n, v, rec, n.getStatus()); };
                        break;
                    }
                    if (a == A.CONTINUATION || a == A.FREE) {
                        ts.event(K, null, ref, "UNLINKED_MESSAGE", null, null, rec, pm.getMsgType() + " for unknown documentary credit accepted (lenient)");
                        return outcome("INFO", List.of(), ref, null, null, null, out);
                    }
                    String what = Set.of(A.AUTHORISATION, A.REFUSAL, A.DISCHARGE, A.REIMBURSEMENT, A.CLAIM_REJECT).contains(a)
                            ? "presentation / claim reference " + v.get("f21") + " does not match any presentation known to " + receiver
                            : "documentary credit " + ref + " is not known to " + receiver;
                    errs.add("BIZ-U01 " + what + (a == A.AMEND ? " (amendment before issuance?)" : ""));
                    break;
                }
                apply = specific(a, lc, it, v, pm, rec, errs, out, from, today);
            }
        }
        if (!errs.isEmpty()) {
            String stt = receiverIsSut ? "INCONSISTENT" : "REJECTED";
            String detail = (receiverIsSut ? "message to SUT is inconsistent with the LC ledger (your system should reject it): " : "business reject by " + receiver + ": ") + String.join("; ", errs);
            ts.event(K, lc == null ? null : lc.getId(), ref, receiverIsSut ? "INCONSISTENT_MESSAGE" : "BUSINESS_REJECTED", st(lc), st(lc), rec, detail);
            return outcome(stt, errs, ref, lc, it, from, out);
        }
        if (apply != null) apply.run();
        DocCredit after = ref == null ? null : repo.findByRef(ref).orElse(null);
        LcPresentation itAfter = it != null ? items.findById(it.getId()).orElse(it)
                : (after != null && Set.of(A.PRESENTATION, A.DISCREPANCY, A.CLAIM).contains(a) ? item(v.get("f20")) : null);
        return outcome("APPLIED", List.of(), after != null ? after.getRef() : ref, after, itAfter, from, out);
    }

    private void issueChecks(Map<String, String> v, LocalDate today, List<String> errs) {
        String ccy = Facts.mtCurrency(v.get("f32B"), false);
        BigDecimal amt = Facts.mtAmount(v.get("f32B"));
        if (ccy == null || amt == null) errs.add("BIZ-V03 credit currency/amount (32B) missing");
        LocalDate issue = Facts.mtDate(v.get("f31C"));
        LocalDate exp = expiry(v.get("f31D"));
        if (exp != null && issue != null && exp.isBefore(issue)) errs.add("BIZ-V01 expiry date " + exp + " is before the date of issue " + issue);
        if (exp != null && exp.isBefore(today)) errs.add("BIZ-V04 expiry date " + exp + " is already in the past (business date " + today + ")");
        LocalDate ls = Facts.mtDate(v.get("f44C"));
        if (ls != null && exp != null && ls.isAfter(exp)) errs.add("BIZ-V06 latest date of shipment " + ls + " (44C) is after the expiry date " + exp);
    }

    static LocalDate expiry(String f31D) {
        return f31D == null || f31D.trim().length() < 6 ? null : Facts.mtDate(f31D.trim().substring(0, 6));
    }

    private DocCredit issue(DocCredit existing, String milestone, ParsedMessage pm, MessageRecord rec) {
        Map<String, String> v = pm.getVars();
        DocCredit lc = existing != null ? existing : new DocCredit();
        String from = existing == null ? null : existing.getStatus();
        String t = pm.getShortType();
        lc.setRef("MT710".equals(t) ? nn(v.get("f21")) : nn(v.get("f20")));
        lc.setOrigin(t);
        String form = firstLine(v.getOrDefault("f40A", v.get("f40B")));
        if (form != null) lc.setForm(form);
        if (v.get("f40E") != null) lc.setRules(firstLine(v.get("f40E")));
        if (v.get("f40F") != null && lc.getRules() == null) lc.setRules("REIMB " + firstLine(v.get("f40F")));
        lc.setTransferable(form != null && form.contains("TRANSFERABLE"));
        lc.setCurrency(Facts.mtCurrency(v.get("f32B"), false));
        BigDecimal amt = Facts.mtAmount(v.get("f32B"));
        lc.setOriginalAmount(amt);
        lc.setAmount(amt);
        int[] tol = tolerance(v.get("f39A"));
        lc.setTolerancePlus(tol[0]);
        lc.setToleranceMinus(tol[1]);
        lc.setUtilised(BigDecimal.ZERO);
        lc.setTransferredAmount(BigDecimal.ZERO);
        LocalDate issue = Facts.mtDate(v.get("f31C"));
        lc.setIssueDate(issue != null ? issue : ts.clock.today());
        lc.setExpiryDate(expiry(v.get("f31D")));
        if (v.get("f31D") != null && v.get("f31D").trim().length() > 6) lc.setExpiryPlace(trunc(v.get("f31D").trim().substring(6).trim(), 64));
        String av = v.containsKey("f41A") ? v.get("f41A") : v.get("f41D");
        if (av != null) {
            String[] l = av.trim().split("\\R");
            lc.setAvailableWith(trunc(String.join(" ", Arrays.copyOf(l, Math.max(1, l.length - 1))).trim(), 250));
            lc.setAvailableBy(l[l.length - 1].trim().replaceFirst("^BY ", ""));
            lc.setAvailableWithAnyBank(av.toUpperCase().contains("ANY BANK"));
        }
        lc.setPartialShipments(firstLine(v.get("f43P")));
        lc.setTranshipment(firstLine(v.get("f43T")));
        lc.setLatestShipment(Facts.mtDate(v.get("f44C")));
        if (v.get("f48") != null) lc.setPresentationDays(Facts.integer(v.get("f48").trim().split("/", 2)[0]));
        lc.setConfirmationInstructions(firstLine(v.get("f49")));
        if (lc.getConfirmed() == null) lc.setConfirmed(false);
        if (v.get("f50") != null) lc.setApplicant(trunc(flat(v.get("f50")), 600));
        if (v.get("f59") != null) lc.setBeneficiary(trunc(flat(v.get("f59")), 600));
        String issuerField = Facts.bicOf(v.get("f52A"));
        lc.setIssuerBic("MT710".equals(t) ? (issuerField != null ? issuerField : rec.getSenderBic()) : rec.getSenderBic());
        lc.setMilestone(milestone);
        if (lc.getCreatedAt() == null) lc.setCreatedAt(Instant.now());
        lc.setOriginMessageId(rec.getId());
        save(lc, rec);
        String s = rec.getSenderBic(), r = rec.getReceiverBic();
        if ("MT710".equals(t)) {
            if (issuerField != null && ts.isParticipant(issuerField)) ts.addParty(K, lc.getId(), issuerField, "ISSUER", "52a of MT710");
            ts.addParty(K, lc.getId(), s, "ADVISING", "sender of MT710 (first advising bank)");
            ts.addParty(K, lc.getId(), r, "SECOND_ADVISING", "receiver of MT710");
        } else if ("MT740".equals(t)) {
            ts.addParty(K, lc.getId(), s, "ISSUER", "sender of MT740 (credit known only from the authorisation to reimburse)");
        } else {
            ts.addParty(K, lc.getId(), s, "ISSUER", "sender of " + t);
            ts.addParty(K, lc.getId(), r, "ADVISING", "receiver of " + t);
        }
        String adv2 = Facts.bicOf(v.get("f57A"));
        if (adv2 != null && ts.isParticipant(adv2) && !adv2.equals(r)) ts.addParty(K, lc.getId(), adv2, "SECOND_ADVISING", "57a advise through bank");
        String nom = v.get("f41A") == null ? null : Facts.bicOf(v.get("f41A").trim().split("\\R")[0]);
        if (nom != null && ts.isParticipant(nom)) ts.addParty(K, lc.getId(), nom, "NOMINATED", "41a available with ... BY " + lc.getAvailableBy());
        String reimb = Facts.bicOf(v.get("f53A"));
        if (reimb != null && !"MT740".equals(t)) {
            lc.setReimbursingBic(reimb);
            if (ts.isParticipant(reimb)) ts.addParty(K, lc.getId(), reimb, "REIMBURSING", "53a reimbursing bank");
        }
        String conf = Facts.bicOf(v.get("f58A"));
        if (conf != null && ts.isParticipant(conf)) ts.addParty(K, lc.getId(), conf, "REQUESTED_CONFIRMER", "58a requested confirmation party");
        else if (lc.getConfirmationInstructions() != null && !"WITHOUT".equals(lc.getConfirmationInstructions()) && !"MT740".equals(t))
            ts.addParty(K, lc.getId(), r, "REQUESTED_CONFIRMER", "49 " + lc.getConfirmationInstructions() + " (receiver)");
        save(lc, rec);
        ts.movement(K, lc.getId(), "ISSUE", lc.getCurrency(), amt, available(lc), rec, null, t);
        ts.event(K, lc.getId(), lc.getRef(), "PRE_ADVISED".equals(milestone) ? "PRE_ADVISED" : "ISSUED", from, lc.getStatus(), rec,
                t + " " + lc.getForm() + " " + lc.getCurrency() + " " + sw(amt) + (lc.getTolerancePlus() > 0 || lc.getToleranceMinus() > 0 ? " +" + lc.getTolerancePlus() + "/-" + lc.getToleranceMinus() + "%" : "")
                        + " expiry " + lc.getExpiryDate() + (lc.getExpiryPlace() != null ? " " + lc.getExpiryPlace() : "") + " available " + lc.getAvailableWith() + " BY " + lc.getAvailableBy()
                        + " partial shipments " + lc.getPartialShipments() + " confirmation " + lc.getConfirmationInstructions() + " issuer " + lc.getIssuerBic() + " -> " + r);
        return lc;
    }

    static int[] tolerance(String f39A) {
        if (f39A == null) return new int[]{0, 0};
        String[] p = f39A.trim().split("/");
        try {
            return new int[]{Integer.parseInt(p[0].trim()), p.length > 1 ? Integer.parseInt(p[1].trim()) : 0};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    private Set<String> roles(DocCredit lc, String bic) {
        return ts.roles(K, lc.getId(), bic);
    }

    private static boolean any(Set<String> have, Set<String> allowed) {
        for (String r : have) if (allowed.contains(r)) return true;
        return false;
    }

    private void terminal(DocCredit lc, A a, List<String> errs) {
        if (TERMINAL.contains(lc.getMilestone())) errs.add("BIZ-S03 " + a + " not allowed: documentary credit " + lc.getRef() + " is " + lc.getMilestone());
    }

    private void currency(DocCredit lc, String ccy, List<String> errs) {
        if (ccy != null && lc.getCurrency() != null && !ccy.equals(lc.getCurrency()))
            errs.add("BIZ-C01 currency mismatch: message uses " + ccy + " but documentary credit " + lc.getRef() + " is in " + lc.getCurrency());
    }

    private void partyChecks(DocCredit lc, String sender, String receiver, List<String> errs) {
        if (roles(lc, sender).isEmpty()) errs.add("BIZ-R01 sender " + sender + " is not a party of documentary credit " + lc.getRef() + " (parties: " + ts.partyList(K, lc.getId()) + ")");
        if (receiver != null && roles(lc, receiver).isEmpty()) errs.add("BIZ-R02 receiver " + receiver + " is not a party of documentary credit " + lc.getRef() + " (parties: " + ts.partyList(K, lc.getId()) + ")");
    }

    BigDecimal maxAmount(DocCredit lc) {
        return z(lc.getAmount()).multiply(BigDecimal.valueOf(100 + (lc.getTolerancePlus() == null ? 0 : lc.getTolerancePlus()))).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    BigDecimal minFullDrawing(DocCredit lc) {
        return z(lc.getAmount()).multiply(BigDecimal.valueOf(100 - (lc.getToleranceMinus() == null ? 0 : lc.getToleranceMinus()))).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    BigDecimal pendingPresented(DocCredit lc) {
        if (lc.getId() == null) return BigDecimal.ZERO;
        return items.findByLcIdOrderByIdAsc(lc.getId()).stream().filter(p -> "PRESENTATION".equals(p.getKind()) && PENDING_ITEM.contains(p.getStatus()))
                .map(p -> z(p.getAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** amount still available for drawings: credit amount + 39A plus tolerance - utilised - presentations under examination */
    BigDecimal available(DocCredit lc) {
        if ("CANCELLED".equals(lc.getMilestone())) return BigDecimal.ZERO;
        return maxAmount(lc).subtract(z(lc.getUtilised())).subtract(pendingPresented(lc)).max(BigDecimal.ZERO);
    }

    BigDecimal unutilised(DocCredit lc) {
        return z(lc.getAmount()).subtract(z(lc.getUtilised())).subtract(pendingPresented(lc)).max(BigDecimal.ZERO);
    }

    LcAmendment pendingAmendment(DocCredit lc) {
        return lc.getId() == null ? null : amendments.findByLcIdOrderByIdAsc(lc.getId()).stream().filter(x -> "PENDING".equals(x.getStatus())).reduce((x, y) -> y).orElse(null);
    }

    private Runnable specific(A a, DocCredit lc, LcPresentation it, Map<String, String> v, ParsedMessage pm, MessageRecord rec, List<String> errs,
                              Map<String, String> out, String from, LocalDate today) {
        String sender = rec.getSenderBic(), receiver = rec.getReceiverBic();
        Set<String> sr = roles(lc, sender), rr = roles(lc, receiver);
        switch (a) {
            case CONTINUATION, FREE -> {
                return () -> {
                    save(lc, rec);
                    String mt792 = "MT792".equals(pm.getShortType())
                            ? " MT792 cancellation request recorded; it does not cancel an irrevocable credit (use MT707). " + trunc(flat(v.get("f79")), 240)
                            : "";
                    ts.event(K, lc.getId(), lc.getRef(), a == A.CONTINUATION ? "CONTINUATION" : "MT792".equals(pm.getShortType()) ? "CANCELLATION_REQUESTED" : "ANCILLARY_MESSAGE", from, lc.getStatus(), rec,
                            pm.getMsgType() + (a == A.CONTINUATION ? " continuation linked" : mt792.isEmpty() ? " free-format / ancillary message linked"
                                    + (v.get("f23H") != null ? " (" + v.get("f23H").trim() + ")" : "") + (v.get("f79") != null ? ": " + trunc(flat(v.get("f79")), 300) : "")
                                    + (v.get("f45D") != null ? ": " + trunc(flat(v.get("f45D")), 300) : "") : mt792));
                };
            }
            case AMEND -> {
                if (sr.contains("ADVISING") && rr.contains("SECOND_ADVISING") && !sr.contains("ISSUER")) {
                    return () -> { save(lc, rec); ts.event(K, lc.getId(), lc.getRef(), "AMENDMENT_FORWARDED", from, lc.getStatus(), rec, "amendment " + v.get("f26E") + " forwarded to the second advising bank " + receiver); };
                }
                partyChecks(lc, sender, receiver, errs);
                if (!errs.isEmpty()) return null;
                if (!sr.contains("ISSUER")) errs.add("BIZ-R03 sender " + sender + " has role " + sr + "; an MT707 amendment must be sent by the issuing bank");
                if (!any(rr, NON_ISSUER)) errs.add("BIZ-R04 receiver " + receiver + " has role " + rr + "; an MT707 amendment must be sent to the advising / confirming / nominated bank");
                terminal(lc, a, errs);
                LcAmendment pend = pendingAmendment(lc);
                if (pend != null && !ts.cfg.getConfig().getLedger().isAllowConcurrentAmendments())
                    errs.add("BIZ-A01 amendment no. " + pend.getSeqNo() + " of " + lc.getRef() + " is still awaiting the beneficiary's acceptance/rejection (UCP 600 art. 10); a further amendment is not accepted");
                int expected = amendments.findByLcIdOrderByIdAsc(lc.getId()).size() + 1;
                Integer seq = Facts.integer(v.get("f26E"));
                if (seq == null) errs.add("BIZ-A02 number of amendment (26E) missing (expected " + expected + ")");
                else if (seq != expected) errs.add("BIZ-A02 number of amendment " + seq + " out of sequence: expected " + expected);
                BigDecimal inc = Facts.mtAmount(v.get("f32B")), dec = Facts.mtAmount(v.get("f33B"));
                currency(lc, Facts.mtCurrency(v.containsKey("f32B") ? v.get("f32B") : v.get("f33B"), false), errs);
                if (dec != null && dec.compareTo(unutilised(lc)) > 0)
                    errs.add("BIZ-M02 decrease " + sw(dec) + " exceeds the unutilised credit amount " + lc.getCurrency() + " " + sw(unutilised(lc)));
                LocalDate ne = expiry(v.get("f31D"));
                if (ne != null && ne.isBefore(today)) errs.add("BIZ-V04 new expiry date " + ne + " is in the past");
                boolean cancel = v.get("f23S") != null && v.get("f23S").trim().startsWith("CANCEL");
                if (cancel && pendingPresented(lc).signum() > 0)
                    errs.add("BIZ-L16 cancellation requested while documents are under examination (" + lc.getCurrency() + " " + sw(pendingPresented(lc)) + " presented)");
                if (!errs.isEmpty()) return null;
                return () -> {
                    LcAmendment am = new LcAmendment();
                    am.setLcId(lc.getId());
                    am.setSeqNo(seq);
                    am.setCurrency(lc.getCurrency());
                    am.setIncrease(inc);
                    am.setDecrease(dec);
                    am.setNewExpiryDate(ne);
                    if (v.get("f39A") != null) { int[] t = tolerance(v.get("f39A")); am.setNewTolerancePlus(t[0]); am.setNewToleranceMinus(t[1]); }
                    if (v.get("f40A") != null) am.setNewForm(firstLine(v.get("f40A")));
                    if (v.get("f43P") != null) am.setNewPartialShipments(firstLine(v.get("f43P")));
                    am.setCancellation(cancel);
                    am.setStatus("PENDING");
                    am.setSenderBic(sender);
                    am.setReceiverBic(receiver);
                    am.setMessageId(rec.getId());
                    am.setCreatedAt(Instant.now());
                    amendments.save(am);
                    save(lc, rec);
                    ts.event(K, lc.getId(), lc.getRef(), "AMENDMENT_RECEIVED", from, lc.getStatus(), rec, "amendment " + seq + " pending beneficiary's acceptance: " + amendmentText(am));
                    out.put("ldg_amendmentStatus", "PENDING");
                };
            }
            case TRANSFER -> {
                if (sr.isEmpty()) errs.add("BIZ-R01 sender " + sender + " is not a party of documentary credit " + lc.getRef() + " (parties: " + ts.partyList(K, lc.getId()) + ")");
                else if (!any(sr, Set.of("ADVISING", "CONFIRMING", "NOMINATED", "SECOND_ADVISING")))
                    errs.add("BIZ-R03 sender " + sender + " has role " + sr + "; only the nominated / advising / confirming bank can act as transferring bank (UCP 600 art. 38)");
                terminal(lc, a, errs);
                if (!Boolean.TRUE.equals(lc.getTransferable())) errs.add("BIZ-L10 documentary credit " + lc.getRef() + " is not transferable (40A " + lc.getForm() + ")");
                BigDecimal amt = Facts.mtAmount(v.get("f32B"));
                currency(lc, Facts.mtCurrency(v.get("f32B"), false), errs);
                if (amt != null && amt.compareTo(available(lc)) > 0)
                    errs.add("BIZ-L01 transferred amount " + sw(amt) + " exceeds the available amount " + lc.getCurrency() + " " + sw(available(lc)));
                LocalDate te = expiry(v.get("f31D"));
                if (te != null && lc.getExpiryDate() != null && te.isAfter(lc.getExpiryDate()))
                    errs.add("BIZ-L15 expiry of the transferred credit " + te + " is later than the expiry of the original credit " + lc.getExpiryDate());
                if (!errs.isEmpty()) return null;
                return () -> {
                    lc.setTransferredAmount(z(lc.getTransferredAmount()).add(z(amt)));
                    lc.setMilestone("TRANSFERRED");
                    ts.addParty(K, lc.getId(), sender, "TRANSFERRING", "sender of MT720");
                    ts.addParty(K, lc.getId(), receiver, "SECOND_BENEFICIARY_BANK", "receiver of MT720 (advising bank of the second beneficiary)");
                    save(lc, rec);
                    ts.movement(K, lc.getId(), "TRANSFER", lc.getCurrency(), amt, available(lc), rec, v.get("f20"), "transferred to " + flat(v.get("f59")));
                    ts.event(K, lc.getId(), lc.getRef(), "TRANSFERRED", from, lc.getStatus(), rec, "credit transferred " + lc.getCurrency() + " " + sw(amt) + " to second beneficiary " + flat(v.get("f59")) + " via " + receiver + " (transfer ref " + v.get("f20") + ")");
                };
            }
            case ACK -> {
                partyChecks(lc, sender, receiver, errs);
                if (!errs.isEmpty()) return null;
                if (!any(sr, NON_ISSUER)) errs.add("BIZ-R03 sender " + sender + " has role " + sr + "; an MT730 acknowledgement is sent by the advising / confirming / transferee bank");
                boolean acc = hasCode(v, "BENACC", "72Z", "79Z"), rej = hasCode(v, "BENREJ", "72Z", "79Z"), conf = hasCode(v, "CONFIRMED", "72Z", "79Z");
                LcAmendment pend = pendingAmendment(lc);
                if ((acc || rej) && pend == null) errs.add("BIZ-A03 no amendment of " + lc.getRef() + " is awaiting the beneficiary's acceptance/rejection");
                if (acc && rej) errs.add("BIZ-A04 MT730 contains both /BENACC/ and /BENREJ/");
                if (conf && (lc.getConfirmationInstructions() == null || "WITHOUT".equals(lc.getConfirmationInstructions())))
                    errs.add("BIZ-L11 confirmation added although the issuing bank did not request/authorise it (49 " + lc.getConfirmationInstructions() + ")");
                if (!acc && !rej) terminal(lc, a, errs);
                if (!errs.isEmpty()) return null;
                return () -> {
                    if (acc || rej) {
                        pend.setResponseMessageId(rec.getId());
                        pend.setRespondedAt(Instant.now());
                        if (acc) {
                            pend.setStatus("ACCEPTED");
                            amendments.save(pend);
                            applyAmendment(lc, pend, rec);
                            lc.setMilestone(Boolean.TRUE.equals(pend.getCancellation()) ? "CANCELLED" : "AMENDED");
                            save(lc, rec);
                            ts.event(K, lc.getId(), lc.getRef(), Boolean.TRUE.equals(pend.getCancellation()) ? "CANCELLED" : "AMENDMENT_ACCEPTED", from, lc.getStatus(), rec,
                                    "beneficiary accepted amendment " + pend.getSeqNo() + ": " + amendmentText(pend));
                        } else {
                            pend.setStatus("REJECTED");
                            pend.setNote(trunc(codeValue(v.getOrDefault("f72Z", v.get("f79Z")), "BENREJ"), 500));
                            amendments.save(pend);
                            save(lc, rec);
                            ts.event(K, lc.getId(), lc.getRef(), "AMENDMENT_REJECTED", from, lc.getStatus(), rec, "beneficiary rejected amendment " + pend.getSeqNo() + (pend.getNote() != null && !pend.getNote().isBlank() ? ": " + pend.getNote() : ""));
                        }
                        out.put("ldg_amendmentStatus", pend.getStatus());
                        return;
                    }
                    if (conf) {
                        lc.setConfirmed(true);
                        ts.addParty(K, lc.getId(), sender, "CONFIRMING", "MT730 /CONFIRMED/");
                        lc.setMilestone("CONFIRMED");
                        save(lc, rec);
                        ts.event(K, lc.getId(), lc.getRef(), "CONFIRMED", from, lc.getStatus(), rec, sender + " added its confirmation (UCP 600 art. 8)");
                        return;
                    }
                    if ("ISSUED".equals(lc.getMilestone()) && any(rr, Set.of("ISSUER", "ADVISING"))) {
                        lc.setMilestone("ADVISED");
                        save(lc, rec);
                        ts.event(K, lc.getId(), lc.getRef(), "ADVISED", from, lc.getStatus(), rec, "credit acknowledged / advised to the beneficiary by " + sender);
                        return;
                    }
                    save(lc, rec);
                    ts.event(K, lc.getId(), lc.getRef(), "ACKNOWLEDGED", from, lc.getStatus(), rec, "MT730 acknowledgement from " + sender);
                };
            }
            case PRESENTATION, DISCREPANCY -> {
                String pref = nn(v.get("f20"));
                if ("CANCELLED".equals(lc.getMilestone())) terminal(lc, a, errs);
                else if ("EXPIRED".equals(lc.getMilestone()) || lc.getExpiryDate() != null && today.isAfter(lc.getExpiryDate()))
                    errs.add("BIZ-L03 presentation after expiry: documentary credit " + lc.getRef() + " expired on " + lc.getExpiryDate() + (lc.getExpiryPlace() != null ? " (" + lc.getExpiryPlace() + ")" : "") + ", business date " + today + " (UCP 600 art. 6(e))");
                if (sr.contains("ISSUER")) errs.add("BIZ-R03 the issuing bank " + sender + " cannot present documents under its own credit");
                else if (sr.isEmpty() && !Boolean.TRUE.equals(lc.getAvailableWithAnyBank()))
                    errs.add("BIZ-R01 presenter " + sender + " is not a nominated / advising / confirming bank of " + lc.getRef() + " (credit available with " + lc.getAvailableWith() + "; parties: " + ts.partyList(K, lc.getId()) + ")");
                if (!any(rr, Set.of("ISSUER", "CONFIRMING"))) errs.add("BIZ-R04 receiver " + receiver + " has role " + rr + "; documents / advices of payment are sent to the issuing or confirming bank");
                if (pref == null) errs.add("BIZ-V00 presenting bank's reference (:20:) missing");
                else if (it != null) errs.add("BIZ-L04 duplicate presentation reference " + pref + " (already used, status " + it.getStatus() + ")");
                String key = a == A.PRESENTATION && v.containsKey("f32A") ? "f32A" : "f32B";
                BigDecimal amt = Facts.mtAmount(v.get(key));
                currency(lc, Facts.mtCurrency(v.get(key), key.equals("f32A")), errs);
                if (amt == null) errs.add("BIZ-V03 drawing amount missing");
                else {
                    BigDecimal avail = available(lc);
                    if (amt.compareTo(avail) > 0)
                        errs.add("BIZ-L01 over-drawing: amount " + lc.getCurrency() + " " + sw(amt) + " exceeds the available balance " + sw(avail)
                                + " (credit amount " + sw(lc.getAmount()) + ", 39A tolerance +" + lc.getTolerancePlus() + "/-" + lc.getToleranceMinus() + "% => max " + sw(maxAmount(lc))
                                + ", utilised " + sw(z(lc.getUtilised())) + ", under examination " + sw(pendingPresented(lc)) + ")");
                    boolean prior = items.findByLcIdOrderByIdAsc(lc.getId()).stream().anyMatch(p -> "PRESENTATION".equals(p.getKind()) && !"REFUSED".equals(p.getStatus()));
                    // 43P is partial shipments. The MT carries no shipment documents, so a second drawing or a drawing
                    // below the minimum full amount is the simulator's proxy for a partial drawing.
                    if ("NOT ALLOWED".equals(lc.getPartialShipments()) && (prior || amt.compareTo(minFullDrawing(lc)) < 0))
                        errs.add("BIZ-L02 partial drawing not allowed (43P NOT ALLOWED; no shipment documents in the MT, so a further drawing or an amount below the minimum full drawing is treated as partial): "
                                + (prior ? "credit already drawn" : "amount " + sw(amt) + " is below the credit amount less tolerance " + sw(minFullDrawing(lc))));
                }
                if (!errs.isEmpty()) return null;
                LcAmendment pend = pendingAmendment(lc);
                return () -> {
                    LcPresentation p = new LcPresentation();
                    p.setLcId(lc.getId());
                    p.setKind("PRESENTATION");
                    p.setPresentationRef(pref);
                    p.setMsgType(pm.getShortType());
                    p.setDrawingType(a == A.DISCREPANCY ? "DISCREPANT" : key.equals("f32A") ? "PAID/NEGOTIATED" : "ACCEPTED/DEFERRED");
                    p.setCurrency(lc.getCurrency());
                    p.setAmount(amt);
                    p.setStatus(a == A.DISCREPANCY ? "DISCREPANT" : "PRESENTED");
                    p.setPresenterBic(sender);
                    p.setAddresseeBic(receiver);
                    p.setPresentedOn(today);
                    p.setMaturityDate(maturityOf(v));
                    p.setNominatedBankPaid(a == A.PRESENTATION && "f32A".equals(key));
                    p.setUtilisationBooked(false);
                    if (v.get("f77J") != null) p.setDiscrepancies(trunc(flat(v.get("f77J")), 2000));
                    p.setMessageId(rec.getId());
                    p.setCreatedAt(Instant.now());
                    items.save(p);
                    if (roles(lc, sender).isEmpty()) ts.addParty(K, lc.getId(), sender, "PRESENTING", "presenter (credit available with any bank)");
                    save(lc, rec);
                    ts.event(K, lc.getId(), lc.getRef(), a == A.DISCREPANCY ? "DISCREPANCY_ADVISED" : "DOCS_PRESENTED", from, lc.getStatus(), rec,
                            pm.getShortType() + " presentation " + pref + " " + lc.getCurrency() + " " + sw(amt) + " by " + sender
                                    + (a == A.DISCREPANCY ? " with discrepancies: " + p.getDiscrepancies() : " (" + p.getDrawingType() + ")")
                                    + (pend != null ? "; NOTE amendment " + pend.getSeqNo() + " still pending (UCP 600 art. 10(c): the presentation indicates acceptance or rejection)" : "")
                                    + (p.getMaturityDate() != null ? "; maturity " + p.getMaturityDate() : "")
                                    + (Boolean.TRUE.equals(p.getNominatedBankPaid()) ? "; nominated bank has paid/negotiated (32A)" : "")
                                    + (p.getMaturityDate() == null && "ACCEPTED/DEFERRED".equals(p.getDrawingType()) ? "; deferred/acceptance drawing, maturity not stated (put /MATURITY/YYMMDD in 72Z)" : "")
                                    + "; examination deadline " + calendar.plus(today, 5) + " (UCP 600 art. 14(b), 5 banking days)");
                    out.put("lc_presentationStatus", p.getStatus());
                };
            }
            case AUTHORISATION, REFUSAL, DISCHARGE, REIMBURSEMENT -> {
                if (it == null || !"PRESENTATION".equals(it.getKind())) { errs.add("BIZ-L05 no presentation " + v.get("f21") + " under " + lc.getRef()); return null; }
                if (!sender.equals(it.getAddresseeBic()) && !sr.contains("ISSUER")) errs.add("BIZ-R03 " + sender + " is not the bank the documents " + it.getPresentationRef() + " were presented to (" + it.getAddresseeBic() + ")");
                if (!receiver.equals(it.getPresenterBic())) errs.add("BIZ-R05 presentation " + it.getPresentationRef() + " was made by " + it.getPresenterBic() + ", not by " + receiver);
                String s = it.getStatus();
                Set<String> okFrom = switch (a) {
                    case AUTHORISATION, REFUSAL -> Set.of("PRESENTED", "DISCREPANT");
                    case DISCHARGE -> Set.of("REFUSED", "DISCREPANT");
                    default -> Set.of("PRESENTED", "DISCREPANT", "AUTHORISED", "ACCEPTED", "DISCHARGED", "REFUSED");
                };
                if ("PAID".equals(s) && a == A.REIMBURSEMENT) errs.add("BIZ-L09 presentation " + it.getPresentationRef() + " has already been paid");
                else if (!okFrom.contains(s)) errs.add("BIZ-L05 presentation " + it.getPresentationRef() + " is " + s + "; " + pm.getShortType() + " is not possible in this state");
                BigDecimal amt = null;
                switch (a) {
                    case REFUSAL -> {
                        currency(lc, Facts.mtCurrency(v.get("f32A"), true), errs);
                        LocalDate deadline = calendar.plus(it.getPresentedOn(), 5);
                        if (today.isAfter(deadline))
                            errs.add("BIZ-L06 refusal given later than 5 banking days after presentation (presented " + it.getPresentedOn() + ", deadline " + deadline + ", business date " + today
                                    + ") - the issuing bank is precluded from claiming non-compliance (UCP 600 art. 14(b)/16(f))");
                    }
                    case DISCHARGE -> { amt = Facts.mtAmount(v.get("f32B")); currency(lc, Facts.mtCurrency(v.get("f32B"), false), errs); }
                    case REIMBURSEMENT -> { amt = Facts.mtAmount(v.get("f33A")); currency(lc, Facts.mtCurrency(v.get("f33A"), true), errs); }
                    default -> { if (v.get("f32B") != null) currency(lc, Facts.mtCurrency(v.get("f32B"), false), errs); }
                }
                // MT756: 32B = total amount claimed (may include charges / interest), 33A = amount reimbursed or paid
                BigDecimal cap = a == A.REIMBURSEMENT ? z(it.getAmount()).max(z(Facts.mtAmount(v.get("f32B")))) : z(it.getAmount());
                if (amt != null && amt.compareTo(cap) > 0)
                    errs.add("BIZ-L07 amount " + sw(amt) + " exceeds the presented amount " + sw(it.getAmount()) + (cap.compareTo(z(it.getAmount())) > 0 ? " / total claimed " + sw(cap) : ""));
                if (!errs.isEmpty()) return null;
                final BigDecimal fAmt = amt;
                return () -> {
                    it.setResolutionMessageId(rec.getId());
                    it.setResolvedAt(Instant.now());
                    switch (a) {
                        case AUTHORISATION -> {
                            String code = firstLine(v.get("f23"));
                            if ("ACCEPT".equals(code)) {
                                it.setStatus("ACCEPTED");
                                lc.setMilestone("ACCEPTED");
                                LocalDate mat = maturityOf(v);
                                if (mat != null) it.setMaturityDate(mat);
                            }
                            else if ("REMITTED".equals(code) || "DEBIT".equals(code)) { it.setStatus("PAID"); it.setPaidAmount(it.getAmount()); lc.setMilestone("PAID"); }
                            else { it.setStatus("AUTHORISED"); lc.setMilestone("AUTHORISED"); }
                            book(lc, it, rec, "MT752 " + code);
                            items.save(it);
                            save(lc, rec);
                            ts.event(K, lc.getId(), lc.getRef(), "AUTHORISED", from, lc.getStatus(), rec, "issuer authorised presentation " + it.getPresentationRef() + " despite discrepancies (23 " + code + ") -> " + it.getStatus());
                        }
                        case REFUSAL -> {
                            it.setStatus("REFUSED");
                            it.setDisposal(firstLine(v.get("f77B")));
                            if (v.get("f77J") != null) it.setDiscrepancies(trunc(flat(v.get("f77J")), 2000));
                            items.save(it);
                            lc.setMilestone("REFUSED");
                            save(lc, rec);
                            ts.event(K, lc.getId(), lc.getRef(), "REFUSED", from, lc.getStatus(), rec, "presentation " + it.getPresentationRef() + " refused: " + it.getDiscrepancies() + "; documents " + it.getDisposal());
                        }
                        case DISCHARGE -> {
                            it.setStatus("DISCHARGED");
                            book(lc, it, rec, "MT732 discharge");
                            items.save(it);
                            lc.setMilestone("DISCHARGED");
                            save(lc, rec);
                            ts.event(K, lc.getId(), lc.getRef(), "DISCHARGED", from, lc.getStatus(), rec, "documents " + it.getPresentationRef() + " taken up, discrepancies waived (utilisation " + sw(fAmt != null ? fAmt : it.getAmount()) + ")");
                        }
                        default -> {
                            it.setStatus("PAID");
                            it.setPaidAmount(fAmt != null ? fAmt : it.getAmount());
                            book(lc, it, rec, "MT756");
                            items.save(it);
                            lc.setMilestone("PAID");
                            save(lc, rec);
                            ts.event(K, lc.getId(), lc.getRef(), "PAID", from, lc.getStatus(), rec, "presentation " + it.getPresentationRef() + " reimbursed/paid " + lc.getCurrency() + " " + sw(it.getPaidAmount()) + "; available " + sw(available(lc)));
                        }
                    }
                    out.put("lc_presentationStatus", it.getStatus());
                };
            }
            case REIMB_AUTH -> {
                if (!sr.contains("ISSUER")) errs.add("BIZ-R03 sender " + sender + " has role " + sr + "; an MT740 authorisation to reimburse must come from the issuing bank");
                terminal(lc, a, errs);
                if (lc.getReimbAmount() != null && receiver.equals(lc.getReimbursingBic())) errs.add("BIZ-L13 authorisation to reimburse for " + lc.getRef() + " was already given to " + receiver + " (use MT747 to amend it)");
                currency(lc, Facts.mtCurrency(v.get("f32B"), false), errs);
                BigDecimal amt = Facts.mtAmount(v.get("f32B"));
                if (amt != null && amt.compareTo(maxAmount(lc)) > 0) errs.add("BIZ-L12 reimbursement authorisation " + sw(amt) + " exceeds the credit amount incl. tolerance " + sw(maxAmount(lc)));
                if (!errs.isEmpty()) return null;
                return () -> reimbAuth(lc, v, rec, from);
            }
            case REIMB_AMEND -> {
                if (!sr.contains("ISSUER")) errs.add("BIZ-R03 sender " + sender + " has role " + sr + "; an MT747 must come from the issuing bank");
                if (lc.getReimbAmount() == null || !receiver.equals(lc.getReimbursingBic())) errs.add("BIZ-L20 no authorisation to reimburse (MT740) of " + lc.getRef() + " was given to " + receiver);
                terminal(lc, a, errs);
                String ak = v.containsKey("f34B") ? "f34B" : v.containsKey("f32B") ? "f32B" : "f33B";
                currency(lc, Facts.mtCurrency(v.get(ak), false), errs);
                BigDecimal n = Facts.mtAmount(v.get("f34B"));
                if (n == null && lc.getReimbAmount() != null) {
                    if (v.get("f32B") != null) n = lc.getReimbAmount().add(z(Facts.mtAmount(v.get("f32B"))));
                    else if (v.get("f33B") != null) n = lc.getReimbAmount().subtract(z(Facts.mtAmount(v.get("f33B"))));
                }
                if (n != null && n.compareTo(z(lc.getReimbClaimed())) < 0) errs.add("BIZ-L14 new reimbursement amount " + sw(n) + " is below the amount already claimed " + sw(lc.getReimbClaimed()));
                if (!errs.isEmpty()) return null;
                final BigDecimal fn = n;
                return () -> {
                    BigDecimal old = lc.getReimbAmount();
                    if (fn != null) lc.setReimbAmount(fn);
                    LocalDate ne = Facts.mtDate(v.get("f31E"));
                    if (ne != null) lc.setReimbExpiry(ne);
                    save(lc, rec);
                    ts.event(K, lc.getId(), lc.getRef(), "REIMB_AUTH_AMENDED", from, lc.getStatus(), rec, "authorisation to reimburse amended: amount " + sw(old) + " -> " + sw(lc.getReimbAmount()) + (ne != null ? ", expiry " + ne : ""));
                };
            }
            case CLAIM -> {
                String cref = nn(v.get("f20"));
                if (!rr.contains("REIMBURSING") && !receiver.equals(lc.getReimbursingBic())) errs.add("BIZ-R04 receiver " + receiver + " is not the reimbursing bank of " + lc.getRef() + " (" + lc.getReimbursingBic() + ")");
                else if (lc.getReimbAmount() == null) errs.add("BIZ-L20 no authorisation to reimburse (MT740) for " + lc.getRef() + " has been received by " + receiver + " (URR 725 art. 6)");
                if (sr.contains("ISSUER")) errs.add("BIZ-R03 the issuing bank cannot claim reimbursement under its own credit");
                if (cref == null) errs.add("BIZ-V00 claiming bank's reference (:20:) missing");
                else if (it != null) errs.add("BIZ-L04 duplicate claim reference " + cref);
                String k34 = v.containsKey("f34A") ? "f34A" : "f34B";
                BigDecimal amt = Facts.mtAmount(v.get(k34));
                currency(lc, Facts.mtCurrency(v.get(k34), k34.equals("f34A")), errs);
                if (amt == null) errs.add("BIZ-V03 total amount claimed (34a) missing");
                else if (lc.getReimbAmount() != null && amt.compareTo(z(lc.getReimbAmount()).subtract(z(lc.getReimbClaimed()))) > 0)
                    errs.add("BIZ-L21 claim " + sw(amt) + " exceeds the unclaimed reimbursement authorisation " + sw(z(lc.getReimbAmount()).subtract(z(lc.getReimbClaimed()))));
                if (lc.getReimbExpiry() != null && today.isAfter(lc.getReimbExpiry())) errs.add("BIZ-L22 claim after the expiry of the reimbursement authorisation (" + lc.getReimbExpiry() + ")");
                if (!errs.isEmpty()) return null;
                return () -> {
                    LcPresentation p = new LcPresentation();
                    p.setLcId(lc.getId());
                    p.setKind("CLAIM");
                    p.setPresentationRef(cref);
                    p.setMsgType("MT742");
                    p.setDrawingType("CLAIM");
                    p.setCurrency(lc.getCurrency());
                    p.setAmount(amt);
                    p.setPrincipalAmount(Facts.mtAmount(v.get("f32B")));
                    p.setStatus("CLAIMED");
                    p.setPresenterBic(sender);
                    p.setAddresseeBic(receiver);
                    p.setPresentedOn(today);
                    p.setUtilisationBooked(false);
                    p.setMessageId(rec.getId());
                    p.setCreatedAt(Instant.now());
                    items.save(p);
                    lc.setReimbClaimed(z(lc.getReimbClaimed()).add(amt));
                    ts.addParty(K, lc.getId(), sender, "CLAIMING", "sender of MT742");
                    save(lc, rec);
                    ts.event(K, lc.getId(), lc.getRef(), "CLAIM_RECEIVED", from, lc.getStatus(), rec, "reimbursement claim " + cref + " " + lc.getCurrency() + " " + sw(amt) + " by " + sender + " on " + receiver);
                };
            }
            case CLAIM_REJECT -> {
                if (it == null || !"CLAIM".equals(it.getKind())) { errs.add("BIZ-L05 no reimbursement claim " + (v.get("f21A") != null ? v.get("f21A") : v.get("f21")) + " under " + lc.getRef()); return null; }
                if (!sender.equals(it.getAddresseeBic())) errs.add("BIZ-R03 " + sender + " is not the reimbursing bank the claim " + it.getPresentationRef() + " was sent to");
                if (!receiver.equals(it.getPresenterBic())) errs.add("BIZ-R05 claim " + it.getPresentationRef() + " was made by " + it.getPresenterBic() + ", not by " + receiver);
                if (!"CLAIMED".equals(it.getStatus())) errs.add("BIZ-L05 claim " + it.getPresentationRef() + " is " + it.getStatus());
                if (!errs.isEmpty()) return null;
                return () -> {
                    it.setStatus("NON_CONFORMING");
                    it.setDiscrepancies(trunc(flat(v.get("f73R")), 2000));
                    it.setDisposal(firstLine(v.get("f73S")));
                    it.setResolutionMessageId(rec.getId());
                    it.setResolvedAt(Instant.now());
                    items.save(it);
                    lc.setReimbClaimed(z(lc.getReimbClaimed()).subtract(z(it.getAmount())).max(BigDecimal.ZERO));
                    save(lc, rec);
                    ts.event(K, lc.getId(), lc.getRef(), "CLAIM_REJECTED", from, lc.getStatus(), rec, "claim " + it.getPresentationRef() + " not conforming (MT744): " + it.getDiscrepancies());
                };
            }
            case SETTLEMENT -> {
                BigDecimal amt = settlementAmount(v);
                currency(lc, settlementCurrency(v), errs);
                if (it == null) {
                    if (!errs.isEmpty()) return null;
                    return () -> { save(lc, rec); ts.event(K, lc.getId(), lc.getRef(), "SETTLEMENT", from, lc.getStatus(), rec, pm.getShortType() + " " + sw(amt) + " linked to the credit (no presentation or claim reference)"); };
                }
                if (!sender.equals(it.getAddresseeBic()) && !sr.contains("ISSUER")) errs.add("BIZ-R03 " + sender + " is not the bank liable for " + it.getPresentationRef() + " (" + it.getAddresseeBic() + ")");
                if (!receiver.equals(it.getPresenterBic())) errs.add("BIZ-R05 " + it.getPresentationRef() + " was presented/claimed by " + it.getPresenterBic() + ", not by " + receiver);
                boolean claim = "CLAIM".equals(it.getKind());
                if (claim && "REIMBURSED".equals(it.getStatus())) errs.add("BIZ-L09 claim " + it.getPresentationRef() + " has already been reimbursed");
                else if (claim && !"CLAIMED".equals(it.getStatus())) errs.add("BIZ-L05 claim " + it.getPresentationRef() + " is " + it.getStatus());
                else if (!claim && "REFUSED".equals(it.getStatus())) errs.add("BIZ-L05 presentation " + it.getPresentationRef() + " was refused - payment requires a discharge (MT732) / reimbursement advice (MT756) first");
                BigDecimal cap = "PAID".equals(it.getStatus()) && it.getPaidAmount() != null ? it.getPaidAmount() : z(it.getAmount());
                if (amt != null && amt.compareTo(cap) > 0)
                    errs.add("BIZ-L07 settlement amount " + sw(amt) + " exceeds the " + (claim ? "claimed" : "presented/paid") + " amount " + sw(cap));
                if (!errs.isEmpty()) return null;
                return () -> {
                    if (claim) {
                        it.setStatus("REIMBURSED");
                        it.setPaidAmount(amt);
                        it.setResolutionMessageId(rec.getId());
                        it.setResolvedAt(Instant.now());
                        bookClaim(lc, it, v, rec);
                        items.save(it);
                        lc.setMilestone("REIMBURSED");
                        save(lc, rec);
                        ts.movement(K, lc.getId(), "REIMBURSEMENT", lc.getCurrency(), amt, z(lc.getReimbAmount()).subtract(z(lc.getReimbClaimed())), rec, it.getPresentationRef(), "reimbursing bank paid the claim");
                        ts.event(K, lc.getId(), lc.getRef(), "REIMBURSED", from, lc.getStatus(), rec, "claim " + it.getPresentationRef() + " reimbursed " + lc.getCurrency() + " " + sw(amt) + " by " + sender + " (" + pm.getShortType() + ")");
                    } else if ("PAID".equals(it.getStatus())) {
                        save(lc, rec);
                        ts.event(K, lc.getId(), lc.getRef(), "COVER_RECEIVED", from, lc.getStatus(), rec, pm.getShortType() + " cover for presentation " + it.getPresentationRef() + " " + lc.getCurrency() + " " + sw(amt));
                    } else {
                        it.setStatus("PAID");
                        it.setPaidAmount(amt);
                        it.setResolutionMessageId(rec.getId());
                        it.setResolvedAt(Instant.now());
                        book(lc, it, rec, pm.getShortType());
                        items.save(it);
                        lc.setMilestone("PAID");
                        save(lc, rec);
                        String early = it.getMaturityDate() != null && today.isBefore(it.getMaturityDate()) ? " (before recorded maturity " + it.getMaturityDate() + ")" : "";
                        ts.event(K, lc.getId(), lc.getRef(), "PAID", from, lc.getStatus(), rec, "presentation " + it.getPresentationRef() + " settled by " + pm.getShortType() + " " + lc.getCurrency() + " " + sw(amt) + early);
                    }
                };
            }
            default -> { return null; }
        }
    }

    private void reimbAuth(DocCredit lc, Map<String, String> v, MessageRecord rec, String from) {
        BigDecimal amt = Facts.mtAmount(v.get("f32B"));
        int[] tol = tolerance(v.get("f39A"));
        BigDecimal max = amt == null ? null : amt.multiply(BigDecimal.valueOf(100 + tol[0])).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        lc.setReimbursingBic(rec.getReceiverBic());
        lc.setReimbAmount(max);
        lc.setReimbClaimed(BigDecimal.ZERO);
        lc.setReimbExpiry(expiry(v.get("f31D")));
        ts.addParty(K, lc.getId(), rec.getReceiverBic(), "REIMBURSING", "receiver of MT740");
        save(lc, rec);
        ts.event(K, lc.getId(), lc.getRef(), "REIMB_AUTHORISED", from, lc.getStatus(), rec, "authorisation to reimburse up to " + lc.getCurrency() + " " + sw(max) + " given to " + rec.getReceiverBic()
                + " (40F " + firstLine(v.get("f40F")) + ", 71A " + firstLine(v.get("f71A")) + ")");
    }

    private void book(DocCredit lc, LcPresentation it, MessageRecord rec, String why) {
        bookAmount(lc, it, z(it.getAmount()), rec, why);
    }

    private void bookAmount(DocCredit lc, LcPresentation it, BigDecimal amt, MessageRecord rec, String why) {
        if (Boolean.TRUE.equals(it.getUtilisationBooked())) return;
        it.setUtilisationBooked(true);
        lc.setUtilised(z(lc.getUtilised()).add(z(amt)));
        ts.movement(K, lc.getId(), "UTILISATION", lc.getCurrency(), amt, maxAmount(lc).subtract(z(lc.getUtilised())), rec, it.getPresentationRef(), why);
    }

    /**
     * Tie an MT742 to one presentation. An explicit /PRESREF/ (or the same reference) wins.
     * Otherwise a single same-presenter presentation for the same principal is used.
     * Another presentation from the same bank does not, by itself, decide this claim's exposure.
     * Exposure booked from the claim alone is field 32B (principal), not 34a (principal + charges).
     */
    private void bookClaim(DocCredit lc, LcPresentation claim, Map<String, String> v, MessageRecord rec) {
        LcPresentation linked = linkedPresentation(lc, claim, v);
        BigDecimal principal = claim.getPrincipalAmount() != null ? claim.getPrincipalAmount() : claim.getAmount();
        if (linked != null) {
            claim.setLinkedPresentationRef(linked.getPresentationRef());
            linked.setLinkedClaimRef(claim.getPresentationRef());
            if (!Boolean.TRUE.equals(linked.getUtilisationBooked()))
                bookAmount(lc, linked, z(linked.getAmount()), rec, "reimbursement of linked presentation " + linked.getPresentationRef());
            items.save(linked);
        } else {
            bookAmount(lc, claim, z(principal), rec, "reimbursement claim paid with no linked presentation; utilisation is principal 32B");
        }
    }

    private LcPresentation linkedPresentation(DocCredit lc, LcPresentation claim, Map<String, String> v) {
        List<LcPresentation> all = items.findByLcIdOrderByIdAsc(lc.getId());
        String explicit = presRef(v.get("f72Z"));
        if (explicit == null) explicit = presRef(v.get("f77"));
        if (explicit != null) {
            String ref = explicit;
            return all.stream().filter(p -> "PRESENTATION".equals(p.getKind()) && ref.equals(p.getPresentationRef())).findFirst().orElse(null);
        }
        Optional<LcPresentation> sameRef = all.stream().filter(p -> "PRESENTATION".equals(p.getKind()) && claim.getPresentationRef().equals(p.getPresentationRef())).findFirst();
        if (sameRef.isPresent()) return sameRef.get();
        BigDecimal principal = claim.getPrincipalAmount() != null ? claim.getPrincipalAmount() : claim.getAmount();
        List<LcPresentation> cands = all.stream().filter(p -> "PRESENTATION".equals(p.getKind())
                && claim.getPresenterBic().equals(p.getPresenterBic())
                && !"REFUSED".equals(p.getStatus())
                && p.getLinkedClaimRef() == null
                && principal != null && z(p.getAmount()).compareTo(principal) == 0).toList();
        return cands.size() == 1 ? cands.get(0) : null;
    }

    private static final Pattern PRESREF = Pattern.compile("/(?:PRESREF|PRESENTATION)/([A-Za-z0-9]{1,16})");

    private static String presRef(String text) {
        if (text == null) return null;
        Matcher m = PRESREF.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static LocalDate maturityOf(Map<String, String> v) {
        for (String key : List.of("f72Z", "f77", "f79Z")) {
            String s = v.get(key);
            if (s == null) continue;
            Matcher m = Pattern.compile("/MATURITY/(\\d{6})").matcher(s);
            if (m.find()) return Facts.mtDate(m.group(1));
        }
        return null;
    }

    private static BigDecimal settlementAmount(Map<String, String> v) {
        if (v.get("f32A") != null) return Facts.mtAmount(v.get("f32A"));
        if (v.get("mx_amount") != null) return Facts.dec(v.get("mx_amount"));
        return Facts.dec(v.get("amountIso"));
    }

    private static String settlementCurrency(Map<String, String> v) {
        if (v.get("f32A") != null) return Facts.mtCurrency(v.get("f32A"), true);
        if (v.get("mx_currency") != null) return v.get("mx_currency");
        return v.get("currency");
    }

    private void applyAmendment(DocCredit lc, LcAmendment am, MessageRecord rec) {
        if (am.getIncrease() != null) {
            lc.setAmount(z(lc.getAmount()).add(am.getIncrease()));
            ts.movement(K, lc.getId(), "INCREASE", lc.getCurrency(), am.getIncrease(), available(lc), rec, null, "amendment " + am.getSeqNo());
        }
        if (am.getDecrease() != null) {
            lc.setAmount(z(lc.getAmount()).subtract(am.getDecrease()));
            ts.movement(K, lc.getId(), "DECREASE", lc.getCurrency(), am.getDecrease(), available(lc), rec, null, "amendment " + am.getSeqNo());
        }
        if (am.getNewExpiryDate() != null) lc.setExpiryDate(am.getNewExpiryDate());
        if (am.getNewTolerancePlus() != null) { lc.setTolerancePlus(am.getNewTolerancePlus()); lc.setToleranceMinus(am.getNewToleranceMinus()); }
        if (am.getNewForm() != null) { lc.setForm(am.getNewForm()); lc.setTransferable(am.getNewForm().contains("TRANSFERABLE")); }
        if (am.getNewPartialShipments() != null) lc.setPartialShipments(am.getNewPartialShipments());
    }

    private static String amendmentText(LcAmendment am) {
        List<String> l = new ArrayList<>();
        if (Boolean.TRUE.equals(am.getCancellation())) l.add("CANCELLATION of the credit");
        if (am.getIncrease() != null) l.add("increase " + am.getCurrency() + " " + sw(am.getIncrease()));
        if (am.getDecrease() != null) l.add("decrease " + am.getCurrency() + " " + sw(am.getDecrease()));
        if (am.getNewExpiryDate() != null) l.add("new expiry " + am.getNewExpiryDate());
        if (am.getNewTolerancePlus() != null) l.add("tolerance " + am.getNewTolerancePlus() + "/" + am.getNewToleranceMinus());
        if (am.getNewForm() != null) l.add("form " + am.getNewForm());
        if (am.getNewPartialShipments() != null) l.add("partial shipments " + am.getNewPartialShipments());
        return l.isEmpty() ? "(other terms only)" : String.join(", ", l);
    }

    // ------------------------------------------------------------------------------------------------ status / persistence

    private static String st(DocCredit lc) {
        return lc == null ? null : lc.getStatus();
    }

    private void save(DocCredit lc, MessageRecord rec) {
        if (lc.getId() == null) {
            lc.setStatus(lc.getMilestone());
            lc.setUpdatedAt(Instant.now());
            repo.save(lc);
        }
        lc.setStatus(derive(lc));
        lc.setUpdatedAt(Instant.now());
        if (rec != null) lc.setLastMessageId(rec.getId());
        repo.save(lc);
    }

    String derive(DocCredit lc) {
        String m = lc.getMilestone();
        if (TERMINAL.contains(m) || "PRE_ADVISED".equals(m)) return m;
        List<LcPresentation> l = items.findByLcIdOrderByIdAsc(lc.getId());
        if (l.stream().anyMatch(p -> "DISCREPANT".equals(p.getStatus()))) return "DISCREPANT";
        if (l.stream().anyMatch(p -> "PRESENTED".equals(p.getStatus()))) return "DOCS_PRESENTED";
        if (pendingAmendment(lc) != null) return "AMENDMENT_PENDING";
        return m;
    }

    private boolean due(DocCredit lc) {
        if (TERMINAL.contains(lc.getMilestone()) || "PRE_ADVISED".equals(lc.getMilestone()) || lc.getExpiryDate() == null) return false;
        if (!ts.clock.today().isAfter(lc.getExpiryDate())) return false;
        return items.findByLcIdOrderByIdAsc(lc.getId()).stream().noneMatch(p -> PENDING_ITEM.contains(p.getStatus()) || "CLAIMED".equals(p.getStatus()));
    }

    private void expireIfDue(DocCredit lc) {
        if (!due(lc)) return;
        String from = lc.getStatus();
        lc.setMilestone("EXPIRED");
        save(lc, null);
        ts.event(K, lc.getId(), lc.getRef(), "EXPIRED", from, lc.getStatus(), null, "expired: expiry date " + lc.getExpiryDate() + " passed (business date " + ts.clock.today() + "); undrawn balance " + sw(maxAmount(lc).subtract(z(lc.getUtilised())).max(BigDecimal.ZERO)) + " lapsed");
    }

    @Override
    public int sweep() {
        int c = 0;
        for (DocCredit lc : repo.findAll()) if (due(lc)) { expireIfDue(lc); c++; }
        return c;
    }

    // ------------------------------------------------------------------------------------------------ context / kickoff support

    private LedgerService.Outcome outcome(String status, List<String> errs, String ref, DocCredit lc, LcPresentation it, String from, Map<String, String> out) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (lc != null) vars.putAll(context(lc, it));
        vars.putAll(out);
        vars.put("ldg_outcome", status);
        return new LedgerService.Outcome(status, errs, ref, from, lc == null ? null : lc.getStatus(), vars);
    }

    @Override
    public boolean exists(String ref) {
        return ref != null && (repo.findByRef(ref).isPresent() || !items.findByPresentationRef(ref).isEmpty());
    }

    @Override
    public Optional<Map<String, String>> context(String ref) {
        if (ref == null) return Optional.empty();
        DocCredit lc = lc(ref);
        if (lc != null) return Optional.of(context(lc, null));
        LcPresentation it = item(ref);
        if (it != null) return repo.findById(it.getLcId()).map(l -> context(l, it));
        return Optional.empty();
    }

    private static String iso(BigDecimal b) { return LedgerService.iso(b); }

    private static String sw(BigDecimal b) { return LedgerService.swift(b); }

    Map<String, String> context(DocCredit lc, LcPresentation it) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("ref", lc.getRef());
        v.put("lcRef", lc.getRef());
        v.put("ldg_family", K);
        v.put("ldg_status", lc.getStatus());
        v.put("lc_milestone", lc.getMilestone());
        if (lc.getCurrency() != null) v.put("currency", lc.getCurrency());
        BigDecimal avail = available(lc);
        v.put("outstanding", sw(avail));
        v.put("outstandingIso", iso(avail));
        if (lc.getAmount() != null) { v.put("ldg_amount", sw(lc.getAmount())); v.put("lc_amount", sw(lc.getAmount())); v.put("lc_amountIso", iso(lc.getAmount())); }
        v.put("lc_maxAmount", sw(maxAmount(lc)));
        v.put("lc_utilised", sw(z(lc.getUtilised())));
        v.put("lc_tolerance", String.format("%02d/%02d", lc.getTolerancePlus() == null ? 0 : lc.getTolerancePlus(), lc.getToleranceMinus() == null ? 0 : lc.getToleranceMinus()));
        if (lc.getIssuerBic() != null) { v.put("ldg_issuerBic", lc.getIssuerBic()); v.put("issuerBic", lc.getIssuerBic()); }
        if (lc.getForm() != null) v.put("lc_form", lc.getForm());
        v.put("ldg_form", "DOCR");
        if (lc.getRules() != null) v.put("lc_rules", lc.getRules());
        if (lc.getAvailableBy() != null) v.put("lc_availableBy", lc.getAvailableBy());
        if (lc.getPartialShipments() != null) v.put("lc_partialShipments", lc.getPartialShipments());
        if (lc.getConfirmationInstructions() != null) v.put("lc_confirmation", lc.getConfirmationInstructions());
        v.put("lc_transferable", String.valueOf(Boolean.TRUE.equals(lc.getTransferable())));
        if (lc.getIssueDate() != null) v.put("lc_issueDate6", lc.getIssueDate().format(YYMMDD));
        if (lc.getExpiryDate() != null) {
            v.put("lc_expiry6", lc.getExpiryDate().format(YYMMDD));
            v.put("lc_expiryIso", lc.getExpiryDate().toString());
            v.put("ldg_expiry6", lc.getExpiryDate().format(YYMMDD));
            v.put("ldg_expiryIso", lc.getExpiryDate().toString());
            v.put("lc_newExpiry6", lc.getExpiryDate().plusMonths(3).format(YYMMDD));
            v.put("lc_newExpiryIso", lc.getExpiryDate().plusMonths(3).toString());
        }
        v.put("lc_expiryPlace", lc.getExpiryPlace() == null ? "NAIROBI" : lc.getExpiryPlace());
        if (lc.getApplicant() != null) v.put("lc_applicant", lc.getApplicant());
        if (lc.getBeneficiary() != null) v.put("lc_beneficiary", lc.getBeneficiary());
        if (lc.getReimbursingBic() != null) v.put("lc_reimbursingBic", lc.getReimbursingBic());
        if (lc.getReimbAmount() != null) {
            BigDecimal ra = z(lc.getReimbAmount()).subtract(z(lc.getReimbClaimed()));
            v.put("lc_reimbAvailable", sw(ra));
            v.put("lc_reimbAvailableIso", iso(ra));
            BigDecimal nr = z(lc.getReimbAmount()).add(z(lc.getAmount()).divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP));
            v.put("lc_reimbNewAmount", sw(nr));
        }
        boolean partialOk = !"NOT ALLOWED".equals(lc.getPartialShipments());
        BigDecimal draw = (partialOk ? z(lc.getAmount()).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP) : z(lc.getAmount())).min(avail);
        v.put("lc_drawAmount", sw(draw));
        v.put("lc_drawAmountIso", iso(draw));
        BigDecimal over = avail.add(new BigDecimal("1000"));
        v.put("lc_overAmount", sw(over));
        v.put("lc_overAmountIso", iso(over));
        v.put("lc_increase", sw(z(lc.getAmount()).divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP)));
        v.put("kickoffAmount", sw(draw));
        if (lc.getId() != null) {
            v.put("nextAmendmentNo", String.valueOf(amendments.findByLcIdOrderByIdAsc(lc.getId()).size() + 1));
            LcAmendment pa = pendingAmendment(lc);
            if (pa != null) v.put("ldg_pendingAmendmentNo", String.valueOf(pa.getSeqNo()));
            List<LcPresentation> all = items.findByLcIdOrderByIdAsc(lc.getId());
            LcPresentation p = it != null ? it : last(all, x -> PENDING_ITEM.contains(x.getStatus()) || "CLAIMED".equals(x.getStatus()));
            if (p != null) {
                v.put("lc_presRef", p.getPresentationRef());
                v.put("lc_presKind", p.getKind());
                v.put("lc_presStatus", p.getStatus());
                v.put("lc_presAmount", sw(p.getAmount()));
                v.put("lc_presAmountIso", iso(p.getAmount()));
                v.put("lc_presenterBic", p.getPresenterBic());
                if (p.getPresentedOn() != null) v.put("lc_presDate6", p.getPresentedOn().format(YYMMDD));
                v.put("kickoffAmount", sw(p.getAmount()));
            }
            v.put("lc_openPresentations", String.valueOf(all.stream().filter(x -> PENDING_ITEM.contains(x.getStatus())).count()));
        }
        return v;
    }

    @Override
    public Optional<Map<String, String>> suggest(String key, String from, String to) {
        String k = key == null ? "" : key;
        for (DocCredit lc : repo.findAllByOrderByUpdatedAtDesc()) {
            expireIfDue(lc);
            Set<String> fr = roles(lc, from), tr = roles(lc, to);
            boolean live = !TERMINAL.contains(lc.getMilestone()) && !"PRE_ADVISED".equals(lc.getMilestone());
            List<LcPresentation> all = items.findByLcIdOrderByIdAsc(lc.getId());
            Predicate<LcPresentation> toPresenter = p -> to.equals(p.getPresenterBic()) && (from.equals(p.getAddresseeBic()) || fr.contains("ISSUER"));
            LcPresentation pick = null;
            boolean ok;
            switch (k) {
                case "MT707", "LC_AMEND" -> ok = live && fr.contains("ISSUER") && any(tr, NON_ISSUER) && pendingAmendment(lc) == null;
                case "MT730", "LC_ACK" -> ok = !TERMINAL.contains(lc.getMilestone()) && any(fr, NON_ISSUER) && any(tr, Set.of("ISSUER", "ADVISING"));
                case "MT750", "MT754", "LC_PRESENT" -> ok = live && any(tr, Set.of("ISSUER", "CONFIRMING")) && !fr.contains("ISSUER")
                        && (!fr.isEmpty() || Boolean.TRUE.equals(lc.getAvailableWithAnyBank())) && available(lc).signum() > 0
                        && (lc.getExpiryDate() == null || !ts.clock.today().isAfter(lc.getExpiryDate()));
                case "LC_PRESENT_EXPIRED" -> ok = any(tr, Set.of("ISSUER", "CONFIRMING")) && !fr.contains("ISSUER") && !fr.isEmpty() && !"CANCELLED".equals(lc.getMilestone());
                case "MT752", "LC_PRESENTATION_DISCREPANT" -> { pick = last(all, p -> "DISCREPANT".equals(p.getStatus()) && toPresenter.test(p)); ok = pick != null; }
                case "MT734", "LC_PRESENTATION_PENDING" -> { pick = last(all, p -> PENDING_ITEM.contains(p.getStatus()) && toPresenter.test(p)); ok = pick != null; }
                case "MT756", "MT202", "MT103", "LC_PRESENTATION_PAYABLE" -> {
                    pick = last(all, p -> "PRESENTATION".equals(p.getKind()) && Set.of("PRESENTED", "DISCREPANT", "AUTHORISED", "ACCEPTED", "DISCHARGED").contains(p.getStatus()) && toPresenter.test(p));
                    ok = pick != null;
                }
                case "MT732", "LC_PRESENTATION_REFUSED" -> { pick = last(all, p -> "REFUSED".equals(p.getStatus()) && toPresenter.test(p)); ok = pick != null; }
                case "MT740" -> ok = live && fr.contains("ISSUER") && lc.getReimbAmount() == null;
                case "MT747" -> ok = live && fr.contains("ISSUER") && lc.getReimbAmount() != null && to.equals(lc.getReimbursingBic());
                case "MT742", "LC_CLAIM" -> ok = lc.getReimbAmount() != null && to.equals(lc.getReimbursingBic()) && !fr.contains("ISSUER")
                        && z(lc.getReimbAmount()).subtract(z(lc.getReimbClaimed())).signum() > 0;
                case "MT744", "LC_CLAIM_PENDING" -> { pick = last(all, p -> "CLAIMED".equals(p.getStatus()) && from.equals(p.getAddresseeBic()) && to.equals(p.getPresenterBic())); ok = pick != null; }
                case "MT720", "LC_TRANSFER" -> ok = live && Boolean.TRUE.equals(lc.getTransferable()) && any(fr, Set.of("ADVISING", "CONFIRMING", "NOMINATED")) && available(lc).signum() > 0;
                case "MT759", "MT799", "LC_ANY" -> ok = !TERMINAL.contains(lc.getMilestone()) && !fr.isEmpty() && !tr.isEmpty();
                default -> ok = false;
            }
            if (ok) {
                Map<String, String> c = context(lc, pick);
                if ("MT742".equals(k) || "LC_CLAIM".equals(k)) {
                    BigDecimal ra = z(lc.getReimbAmount()).subtract(z(lc.getReimbClaimed()));
                    LcPresentation honoured = last(all, p -> "PRESENTATION".equals(p.getKind()) && from.equals(p.getPresenterBic()) && !"REFUSED".equals(p.getStatus()));
                    BigDecimal claim = (honoured != null ? z(honoured.getAmount()) : new BigDecimal(c.get("lc_drawAmountIso"))).min(ra);
                    c.put("kickoffAmount", sw(claim));
                }
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    private static LcPresentation last(List<LcPresentation> l, Predicate<LcPresentation> p) {
        LcPresentation r = null;
        for (LcPresentation x : l) if (p.test(x)) r = x;
        return r;
    }

    // ------------------------------------------------------------------------------------------------ queries / admin

    private Map<String, Object> summary(DocCredit lc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", lc.getRef());
        m.put("family", K);
        m.put("status", lc.getStatus());
        m.put("milestone", lc.getMilestone());
        m.put("form", lc.getForm());
        m.put("rules", lc.getRules());
        m.put("format", "MT");
        m.put("origin", lc.getOrigin());
        m.put("currency", lc.getCurrency());
        m.put("originalAmount", lc.getOriginalAmount());
        m.put("amount", lc.getAmount());
        m.put("tolerance", (lc.getTolerancePlus() == null ? 0 : lc.getTolerancePlus()) + "/" + (lc.getToleranceMinus() == null ? 0 : lc.getToleranceMinus()));
        m.put("maxAmount", maxAmount(lc));
        m.put("utilised", lc.getUtilised());
        m.put("outstanding", available(lc));
        m.put("transferredAmount", lc.getTransferredAmount());
        m.put("issueDate", lc.getIssueDate());
        m.put("expiryDate", lc.getExpiryDate());
        m.put("expiryPlace", lc.getExpiryPlace());
        m.put("availableWith", lc.getAvailableWith());
        m.put("availableBy", lc.getAvailableBy());
        m.put("partialShipments", lc.getPartialShipments());
        m.put("transhipment", lc.getTranshipment());
        m.put("latestShipment", lc.getLatestShipment());
        m.put("confirmationInstructions", lc.getConfirmationInstructions());
        m.put("confirmed", lc.getConfirmed());
        m.put("transferable", lc.getTransferable());
        m.put("issuerBic", lc.getIssuerBic());
        m.put("applicant", lc.getApplicant());
        m.put("beneficiary", lc.getBeneficiary());
        m.put("reimbursingBic", lc.getReimbursingBic());
        m.put("reimbAmount", lc.getReimbAmount());
        m.put("reimbClaimed", lc.getReimbClaimed());
        m.put("reimbExpiry", lc.getReimbExpiry());
        m.put("createdAt", lc.getCreatedAt());
        m.put("updatedAt", lc.getUpdatedAt());
        return m;
    }

    @Override
    public List<Map<String, Object>> list(String status, String party) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DocCredit lc : repo.findAllByOrderByUpdatedAtDesc()) {
            if (status != null && !status.equalsIgnoreCase(lc.getStatus())) continue;
            List<TradeParty> ps = ts.parties.findByKindAndLedgerIdOrderByIdAsc(K, lc.getId());
            if (party != null && ps.stream().noneMatch(p -> party.equalsIgnoreCase(p.getBic()) || party.equalsIgnoreCase(p.getParticipantId()))) continue;
            Map<String, Object> m = summary(lc);
            m.put("parties", ps.stream().map(p -> p.getRole() + "=" + p.getBic() + (p.getParticipantId() != null ? "(" + p.getParticipantId() + ")" : "")).toList());
            out.add(m);
        }
        return out;
    }

    @Override
    public Optional<Map<String, Object>> detail(String ref, boolean includeRaw) {
        DocCredit lc0 = lc(ref);
        if (lc0 == null) { LcPresentation it = item(ref); if (it != null) lc0 = repo.findById(it.getLcId()).orElse(null); }
        if (lc0 == null) return Optional.empty();
        DocCredit lc = lc0;
        Map<String, Object> m = summary(lc);
        m.put("parties", ts.parties.findByKindAndLedgerIdOrderByIdAsc(K, lc.getId()));
        m.put("amendments", amendments.findByLcIdOrderByIdAsc(lc.getId()));
        m.put("presentations", items.findByLcIdOrderByIdAsc(lc.getId()));
        m.put("movements", ts.movements.findByKindAndLedgerIdOrderByIdAsc(K, lc.getId()));
        List<TradeEvent> hist = new ArrayList<>(ts.events.findByKindAndRefAndLedgerIdIsNullOrderByIdAsc(K, lc.getRef()));
        hist.addAll(ts.events.findByKindAndLedgerIdOrderByIdAsc(K, lc.getId()));
        hist.sort(Comparator.comparing(TradeEvent::getId));
        m.put("history", hist);
        List<MessageRecord> msgs = new ArrayList<>(messages.findForRef(lc.getRef()));
        Set<Long> seen = new HashSet<>();
        msgs.forEach(x -> seen.add(x.getId()));
        for (LcPresentation p : items.findByLcIdOrderByIdAsc(lc.getId()))
            for (MessageRecord x : messages.findForRef(p.getPresentationRef())) if (seen.add(x.getId())) msgs.add(x);
        msgs.sort(Comparator.comparing(MessageRecord::getId));
        if (!includeRaw) msgs.forEach(x -> { x.setRaw(null); x.setDeliveredRaw(null); });
        m.put("messages", msgs);
        m.put("context", context(lc, null));
        return Optional.of(m);
    }

    @Override
    public Object addParty(String ref, String bic, String role) {
        DocCredit lc = repo.findByRef(ref).orElseThrow(() -> new NoSuchElementException("unknown documentary credit " + ref));
        if (!ROLES.contains(role)) throw new IllegalArgumentException("role must be one of " + ROLES);
        TradeParty p = ts.addParty(K, lc.getId(), bic, role, "admin API (out-of-band relationship)");
        if ("REIMBURSING".equals(role)) { lc.setReimbursingBic(bic); save(lc, null); }
        ts.event(K, lc.getId(), lc.getRef(), "PARTY_ADDED", lc.getStatus(), lc.getStatus(), null, role + "=" + bic + " added via admin API");
        return p;
    }

    @Override
    public long clear() {
        long n = repo.count();
        ts.events.deleteAll(ts.events.findAll().stream().filter(e -> K.equals(e.getKind())).toList());
        ts.movements.deleteAll(ts.movements.findAll().stream().filter(e -> K.equals(e.getKind())).toList());
        ts.parties.deleteAll(ts.parties.findAll().stream().filter(e -> K.equals(e.getKind())).toList());
        items.deleteAllInBatch();
        amendments.deleteAllInBatch();
        repo.deleteAllInBatch();
        return n;
    }
}
