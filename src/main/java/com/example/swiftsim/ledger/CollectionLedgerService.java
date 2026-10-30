package com.example.swiftsim.ledger;

import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.domain.MessageRepository;
import com.example.swiftsim.parse.ParsedMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.example.swiftsim.ledger.TradeSupport.*;

/**
 * Ledger of documentary / clean collections (Category 4, URC 522 semantics, simplified).
 * The collection instruction itself has no dedicated FIN MT in SR2025 (it travels with the documents), so the simulator models it
 * as an MT499 whose :79: starts with "/COLLINST/&lt;DP|DA|CLEAN|CASH&gt;" (simulator convention).
 * Statuses: SENT, ACKNOWLEDGED, ACCEPTED, PARTIALLY_PAID, PAID, NON_ACCEPTED, NON_PAID, AMENDED, TRACER (overlay), RETURNED.
 */
@Service
@RequiredArgsConstructor
public class CollectionLedgerService implements TradeLedger {
    static final String K = "COLL";
    private final DocCollectionRepository repo;
    private final MessageRepository messages;
    private final TradeSupport ts;

    public static final Set<String> TERMINAL = Set.of("PAID", "RETURNED");
    static final Set<String> OWNED = Set.of("MT400", "MT410", "MT412", "MT416", "MT420", "MT422", "MT430", "MT450", "MT455", "MT456");
    static final Set<String> TYPES = Set.of("DP", "DA", "CLEAN", "CASH");
    static final Set<String> ROLES = new TreeSet<>(Set.of("REMITTING", "COLLECTING", "PRESENTING"));
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    enum A { INSTRUCTION, ACK, ACCEPTANCE, NON_PAYMENT, TRACER, FATE, AMEND, PAYMENT, CASH_CREDIT, CASH_ADJUST, DISHONOUR, SETTLEMENT, FREE }

    static boolean isInstruction(ParsedMessage pm) {
        String f79 = pm.getVars().get("f79");
        return "MT499".equals(pm.getShortType()) && f79 != null && f79.stripLeading().startsWith("/COLLINST/");
    }

    static A action(ParsedMessage pm) {
        return switch (pm.getShortType()) {
            case "MT499" -> isInstruction(pm) ? A.INSTRUCTION : A.FREE;
            case "MT410" -> A.ACK;
            case "MT412" -> A.ACCEPTANCE;
            case "MT416" -> A.NON_PAYMENT;
            case "MT420" -> A.TRACER;
            case "MT422" -> A.FATE;
            case "MT430" -> A.AMEND;
            case "MT400" -> A.PAYMENT;
            case "MT450" -> A.CASH_CREDIT;
            case "MT455" -> A.CASH_ADJUST;
            case "MT456" -> A.DISHONOUR;
            case "MT202", "MT103" -> A.SETTLEMENT;
            default -> A.FREE;
        };
    }

    @Override public String family() { return K; }

    @Override public boolean owns(String t) { return OWNED.contains(t); }

    /** MT499 /COLLINST/ instructions are owned too (checked by LedgerService via this method) */
    public boolean ownsMessage(ParsedMessage pm) {
        return OWNED.contains(pm.getShortType()) || isInstruction(pm);
    }

    @Override
    public boolean references(ParsedMessage pm) {
        return find(pm) != null;
    }

    private static String nn(String s) {
        return s == null || s.isBlank() || "NONREF".equals(s.trim()) ? null : s.trim();
    }

    private DocCollection byAnyRef(String r) {
        if (r == null) return null;
        return repo.findByRef(r).or(() -> repo.findFirstByCollectingRef(r)).orElse(null);
    }

    DocCollection find(ParsedMessage pm) {
        Map<String, String> v = pm.getVars();
        for (String c : new String[]{nn(v.get("f21A")), nn(v.get("f21")), nn(v.get("f20")), nn(pm.getTxRef())}) {
            DocCollection d = byAnyRef(c);
            if (d != null) return d;
        }
        return null;
    }

    static String sw(BigDecimal b) { return LedgerService.swift(b); }

    static String iso(BigDecimal b) { return LedgerService.iso(b); }

    private static String st(DocCollection c) { return c == null ? null : c.getStatus(); }

    // --------------------------------------------------------------------------------------------- processing

    @Override
    public LedgerService.Outcome process(MessageRecord rec, ParsedMessage pm, boolean receiverIsSut, boolean businessReject) {
        A a = action(pm);
        Map<String, String> v = pm.getVars();
        DocCollection c = a == A.INSTRUCTION ? repo.findByRef(String.valueOf(nn(v.get("f20")))).orElse(null) : find(pm);
        String ref = c != null ? c.getRef() : a == A.INSTRUCTION ? nn(v.get("f20")) : pm.getTxRef();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("ldg_family", K);
        if (businessReject) {
            ts.event(K, c == null ? null : c.getId(), ref, "BUSINESS_REJECT_SENT", st(c), st(c), rec, "business reject sent by " + rec.getSenderBic() + " to " + rec.getReceiverBic());
            return outcome("INFO", List.of(), ref, c, null, out);
        }
        if (Boolean.TRUE.equals(rec.getPossibleDuplicate()) && rec.getDuplicateOf() != null) {
            MessageRecord orig = messages.findById(rec.getDuplicateOf()).orElse(null);
            if (orig != null && orig.getBusinessStatus() != null) {
                ts.event(K, c == null ? null : c.getId(), ref, "DUPLICATE_IGNORED", st(c), st(c), rec, "PDE copy of message #" + orig.getId() + " (already processed) - not processed again");
                return outcome("DUPLICATE_IGNORED", List.of(), ref, c, st(c), out);
            }
        }
        String from = st(c);
        List<String> errs = new ArrayList<>();
        String sender = rec.getSenderBic(), receiver = rec.getReceiverBic();
        Runnable apply = null;
        if (a == A.INSTRUCTION) {
            String f79 = v.get("f79");
            String type = firstLine(f79).substring("/COLLINST/".length()).trim().toUpperCase();
            BigDecimal amt = Facts.mtAmount(codeValue(f79, "AMOUNT"));
            String ccy = Facts.mtCurrency(codeValue(f79, "AMOUNT"), false);
            String tenor = codeValue(f79, "TENOR");
            if (ref == null) errs.add("BIZ-V00 collection reference (:20:) missing");
            else if (c != null) errs.add("BIZ-K02 duplicate collection reference " + ref + " (already known, status " + c.getStatus() + ")");
            if (!TYPES.contains(type)) errs.add("BIZ-K11 unknown collection type '" + type + "' (expected /COLLINST/DP, DA, CLEAN or CASH)");
            if (amt == null || ccy == null) errs.add("BIZ-K03 collection amount missing (line /AMOUNT/CCYnnn,nn in :79:)");
            if ("DA".equals(type) && (tenor == null || tenor.isBlank() || tenor.toUpperCase().contains("SIGHT") && !tenor.toUpperCase().contains("AFTER")))
                errs.add("BIZ-K04 documents against acceptance require a usance tenor (/TENOR/nn DAYS AFTER SIGHT or /MATURITY/)");
            if (errs.isEmpty()) apply = () -> instruct(pm, rec, type, ccy, amt, tenor);
        } else if (c == null) {
            if (a == A.FREE || a == A.SETTLEMENT) {
                ts.event(K, null, ref, "UNLINKED_MESSAGE", null, null, rec, pm.getMsgType() + " for unknown collection accepted (lenient)");
                return outcome("INFO", List.of(), ref, null, null, out);
            }
            errs.add("BIZ-U01 collection reference " + Optional.ofNullable(nn(v.get("f21A"))).orElse(v.get("f21")) + " is not known to " + receiver);
        } else {
            apply = specific(a, c, v, pm, rec, errs, from, out);
        }
        if (!errs.isEmpty()) {
            String stt = receiverIsSut ? "INCONSISTENT" : "REJECTED";
            String detail = (receiverIsSut ? "message to SUT is inconsistent with the collection ledger (your system should reject it): " : "business reject by " + receiver + ": ") + String.join("; ", errs);
            ts.event(K, c == null ? null : c.getId(), ref, receiverIsSut ? "INCONSISTENT_MESSAGE" : "BUSINESS_REJECTED", st(c), st(c), rec, detail);
            return outcome(stt, errs, ref, c, from, out);
        }
        if (apply != null) apply.run();
        DocCollection after = ref == null ? null : repo.findByRef(ref).orElse(c);
        return outcome("APPLIED", List.of(), after != null ? after.getRef() : ref, after, from, out);
    }

    private void instruct(ParsedMessage pm, MessageRecord rec, String type, String ccy, BigDecimal amt, String tenor) {
        Map<String, String> v = pm.getVars();
        String f79 = v.get("f79");
        DocCollection c = new DocCollection();
        c.setRef(nn(v.get("f20")));
        c.setCollType(type);
        c.setRules(codeValue(f79, "URC522") != null ? "URC 522" : null);
        c.setCurrency(ccy);
        c.setOriginalAmount(amt);
        c.setAmount(amt);
        c.setOutstanding(amt);
        c.setPaidAmount(BigDecimal.ZERO);
        c.setAcceptedAmount(BigDecimal.ZERO);
        c.setTenor(tenor);
        c.setMaturityDate(Facts.mtDate(codeValue(f79, "MATURITY")));
        c.setDrawer(trunc(codeValue(f79, "DRAWER"), 600));
        c.setDrawee(trunc(codeValue(f79, "DRAWEE"), 600));
        c.setDocuments(trunc(codeValue(f79, "DOCS"), 1000));
        c.setRemittingBic(rec.getSenderBic());
        c.setCollectingBic(rec.getReceiverBic());
        c.setTracerPending(false);
        c.setTracerCount(0);
        c.setReturnRequested(false);
        c.setMilestone("SENT");
        c.setCreatedAt(Instant.now());
        c.setOriginMessageId(rec.getId());
        save(c, rec);
        ts.addParty(K, c.getId(), rec.getSenderBic(), "REMITTING", "sender of the collection instruction");
        ts.addParty(K, c.getId(), rec.getReceiverBic(), "COLLECTING", "receiver of the collection instruction");
        ts.movement(K, c.getId(), "LODGED", ccy, amt, amt, rec, null, type + " collection lodged");
        ts.event(K, c.getId(), c.getRef(), "SENT", null, c.getStatus(), rec, "collection instruction " + type + " " + ccy + " " + sw(amt) + (tenor != null ? " tenor " + tenor : "")
                + " drawee " + c.getDrawee() + " docs " + c.getDocuments() + " (" + rec.getSenderBic() + " -> " + rec.getReceiverBic() + ")");
    }

    record Amt(String ccy, BigDecimal amount, LocalDate date) {}

    /** field 32a (A: date+ccy+amount, B: ccy+amount, K: D/M + days + code + ccy + amount) / 33a likewise */
    static Amt amt(Map<String, String> v, String base) {
        String a = v.get("f" + base + "A");
        if (a != null) return new Amt(Facts.mtCurrency(a, true), Facts.mtAmount(a), Facts.mtDate(a.trim().substring(0, Math.min(6, a.trim().length()))));
        String b = v.get("f" + base + "B");
        if (b != null) return new Amt(Facts.mtCurrency(b, false), Facts.mtAmount(b), null);
        for (String opt : new String[]{"K", "C", "D"}) {
            String k = v.get("f" + base + opt);
            if (k == null) continue;
            String t = k.trim();
            if ("K".equals(opt) && t.length() > 9) return new Amt(t.substring(6, 9), Facts.mtAmount(t.substring(9)), null);
            if (!"K".equals(opt)) return t.matches("^\\d{6}[A-Z]{3}.*") ? new Amt(t.substring(6, 9), Facts.mtAmount(t), Facts.mtDate(t.substring(0, 6))) : new Amt(Facts.mtCurrency(t, false), Facts.mtAmount(t), null);
        }
        return null;
    }

    private void currency(DocCollection c, Amt x, List<String> errs) {
        if (x != null && x.ccy() != null && c.getCurrency() != null && !x.ccy().equals(c.getCurrency()))
            errs.add("BIZ-C01 currency mismatch: message uses " + x.ccy() + " but collection " + c.getRef() + " is in " + c.getCurrency());
    }

    private void open(DocCollection c, A a, List<String> errs) {
        if (TERMINAL.contains(c.getMilestone())) errs.add("BIZ-S03 " + a + " not allowed: collection " + c.getRef() + " is " + c.getMilestone());
    }

    private Runnable specific(A a, DocCollection c, Map<String, String> v, ParsedMessage pm, MessageRecord rec, List<String> errs, String from, Map<String, String> out) {
        String sender = rec.getSenderBic(), receiver = rec.getReceiverBic();
        Set<String> sr = ts.roles(K, c.getId(), sender), rr = ts.roles(K, c.getId(), receiver);
        boolean fromCollecting = sr.contains("COLLECTING") && rr.contains("REMITTING");
        boolean fromRemitting = sr.contains("REMITTING") && rr.contains("COLLECTING");
        Set<A> collectingMsgs = Set.of(A.ACK, A.ACCEPTANCE, A.NON_PAYMENT, A.FATE, A.PAYMENT, A.CASH_CREDIT, A.CASH_ADJUST, A.DISHONOUR);
        if (a != A.FREE && a != A.SETTLEMENT) {
            if (sr.isEmpty()) errs.add("BIZ-R01 sender " + sender + " is not a party of collection " + c.getRef() + " (parties: " + ts.partyList(K, c.getId()) + ")");
            else if (rr.isEmpty()) errs.add("BIZ-R02 receiver " + receiver + " is not a party of collection " + c.getRef() + " (parties: " + ts.partyList(K, c.getId()) + ")");
            else if (collectingMsgs.contains(a) && !fromCollecting) errs.add("BIZ-R03 " + pm.getShortType() + " is sent by the collecting bank to the remitting bank (sender role " + sr + ")");
            else if (!collectingMsgs.contains(a) && !fromRemitting) errs.add("BIZ-R03 " + pm.getShortType() + " is sent by the remitting bank to the collecting bank (sender role " + sr + ")");
            if (!errs.isEmpty()) return null;
        }
        Amt x32 = amt(v, "32"), x33 = amt(v, "33");
        switch (a) {
            case FREE -> {
                return () -> { save(c, rec); ts.event(K, c.getId(), c.getRef(), "ANCILLARY_MESSAGE", from, c.getStatus(), rec, pm.getMsgType() + " linked" + (v.get("f79") != null ? ": " + trunc(flat(v.get("f79")), 300) : "")); };
            }
            case ACK -> {
                open(c, a, errs);
                currency(c, x32, errs);
                if (x32 != null && x32.amount() != null && x32.amount().compareTo(z(c.getAmount())) != 0)
                    errs.add("BIZ-K05 acknowledged amount " + sw(x32.amount()) + " does not match the collection amount " + c.getCurrency() + " " + sw(c.getAmount()));
                if (!errs.isEmpty()) return null;
                return () -> {
                    if (c.getCollectingRef() == null) c.setCollectingRef(nn(v.get("f20")));
                    if ("SENT".equals(c.getMilestone())) c.setMilestone("ACKNOWLEDGED");
                    save(c, rec);
                    ts.event(K, c.getId(), c.getRef(), "ACKNOWLEDGED", from, c.getStatus(), rec, "collection acknowledged by " + sender + " (collecting bank ref " + v.get("f20") + ")");
                };
            }
            case ACCEPTANCE -> {
                open(c, a, errs);
                currency(c, x32, errs);
                if (!"DA".equals(c.getCollType())) errs.add("BIZ-K06 advice of acceptance for a " + c.getCollType() + " collection (only documents against acceptance are accepted)");
                if ("ACCEPTED".equals(c.getMilestone()) || z(c.getAcceptedAmount()).signum() > 0) errs.add("BIZ-K09 the draft under " + c.getRef() + " has already been accepted");
                if (x32 != null && x32.amount() != null && x32.amount().compareTo(z(c.getOutstanding())) > 0)
                    errs.add("BIZ-K01 accepted amount " + sw(x32.amount()) + " exceeds the outstanding amount " + sw(c.getOutstanding()));
                if (!errs.isEmpty()) return null;
                return () -> {
                    if (c.getCollectingRef() == null) c.setCollectingRef(nn(v.get("f20")));
                    c.setAcceptedAmount(x32 == null ? c.getOutstanding() : x32.amount());
                    if (x32 != null && x32.date() != null) c.setMaturityDate(x32.date());
                    c.setMilestone("ACCEPTED");
                    c.setTracerPending(false);
                    save(c, rec);
                    ts.event(K, c.getId(), c.getRef(), "ACCEPTED", from, c.getStatus(), rec, "drawee accepted the draft " + c.getCurrency() + " " + sw(c.getAcceptedAmount()) + " maturing " + c.getMaturityDate());
                };
            }
            case NON_PAYMENT -> {
                open(c, a, errs);
                currency(c, x32, errs);
                if (!errs.isEmpty()) return null;
                String code = Optional.ofNullable(v.get("f23E")).orElse("");
                boolean nonAcc = code.toUpperCase().contains("ACC") || "DA".equals(c.getCollType()) && !"ACCEPTED".equals(c.getMilestone()) && !code.toUpperCase().contains("PAY");
                return () -> {
                    c.setMilestone(nonAcc ? "NON_ACCEPTED" : "NON_PAID");
                    c.setTracerPending(false);
                    save(c, rec);
                    ts.event(K, c.getId(), c.getRef(), c.getMilestone(), from, c.getStatus(), rec, "advice of " + (nonAcc ? "non-acceptance" : "non-payment") + (v.get("f77A") != null ? ": " + flat(v.get("f77A")) : ""));
                };
            }
            case TRACER -> {
                open(c, a, errs);
                currency(c, x32, errs);
                if (!errs.isEmpty()) return null;
                return () -> {
                    c.setTracerPending(true);
                    c.setTracerCount((c.getTracerCount() == null ? 0 : c.getTracerCount()) + 1);
                    save(c, rec);
                    ts.event(K, c.getId(), c.getRef(), "TRACER", from, c.getStatus(), rec, "tracer no. " + c.getTracerCount() + " sent by the remitting bank");
                };
            }
            case FATE -> {
                if (!errs.isEmpty()) return null;
                boolean returned = hasCode(v, "RETURNED", "72", "75", "76");
                if (returned && "PAID".equals(c.getMilestone())) errs.add("BIZ-K07 collection " + c.getRef() + " is already paid - documents cannot be returned");
                else if (!returned) open(c, a, errs);
                if (!errs.isEmpty()) return null;
                return () -> {
                    c.setTracerPending(false);
                    if (returned) c.setMilestone("RETURNED");
                    save(c, rec);
                    String txt = Optional.ofNullable(v.get("f76")).or(() -> Optional.ofNullable(v.get("f72"))).map(TradeSupport::flat).orElse("");
                    ts.event(K, c.getId(), c.getRef(), returned ? "RETURNED" : "FATE_ADVISED", from, c.getStatus(), rec, (returned ? "documents returned unpaid: " : "advice of fate: ") + txt);
                    out.put("coll_fateAdvised", "true");
                };
            }
            case AMEND -> {
                open(c, a, errs);
                currency(c, x32, errs);
                currency(c, x33, errs);
                if (x32 != null && x32.amount() != null && x32.amount().compareTo(z(c.getOutstanding())) != 0)
                    errs.add("BIZ-K08 existing amount " + sw(x32.amount()) + " (32a) does not match the outstanding collection amount " + c.getCurrency() + " " + sw(c.getOutstanding()));
                if (x33 != null && x33.amount() != null && x33.amount().signum() <= 0) errs.add("BIZ-V03 amended amount must be positive");
                if (!errs.isEmpty()) return null;
                boolean ret = hasCode(v, "RETURN", "72", "74");
                return () -> {
                    if (x33 != null && x33.amount() != null) {
                        BigDecimal delta = x33.amount().subtract(z(c.getOutstanding()));
                        c.setAmount(z(c.getAmount()).add(delta));
                        c.setOutstanding(x33.amount());
                        ts.movement(K, c.getId(), "AMENDMENT", c.getCurrency(), delta, c.getOutstanding(), rec, null, "amended amount " + sw(x33.amount()));
                    }
                    if (x33 != null && x33.date() != null) c.setMaturityDate(x33.date());
                    if (ret) c.setReturnRequested(true);
                    if (!"ACCEPTED".equals(c.getMilestone())) c.setMilestone("AMENDED");
                    save(c, rec);
                    ts.event(K, c.getId(), c.getRef(), ret ? "RETURN_REQUESTED" : "AMENDED", from, c.getStatus(), rec, "amendment of the collection instruction"
                            + (x33 != null ? ": new amount " + sw(x33.amount()) : "") + (ret ? "; remitting bank requests return of documents" : "")
                            + (v.get("f74") != null ? " - " + flat(v.get("f74")) : ""));
                };
            }
            case PAYMENT, CASH_CREDIT -> {
                if ("PAID".equals(c.getMilestone())) errs.add("BIZ-K07 collection " + c.getRef() + " is already fully paid");
                else if ("RETURNED".equals(c.getMilestone())) open(c, a, errs);
                currency(c, x32, errs);
                if (x32 == null || x32.amount() == null) errs.add("BIZ-V03 amount collected (32a) missing");
                else if (x32.amount().compareTo(z(c.getOutstanding())) > 0)
                    errs.add("BIZ-K01 over-payment: " + sw(x32.amount()) + " exceeds the outstanding amount " + c.getCurrency() + " " + sw(c.getOutstanding()));
                if (!errs.isEmpty()) return null;
                return () -> {
                    BigDecimal paid = x32.amount();
                    c.setPaidAmount(z(c.getPaidAmount()).add(paid));
                    c.setOutstanding(z(c.getOutstanding()).subtract(paid));
                    c.setMilestone(c.getOutstanding().signum() <= 0 ? "PAID" : "PARTIALLY_PAID");
                    c.setTracerPending(false);
                    save(c, rec);
                    ts.movement(K, c.getId(), "PAYMENT", c.getCurrency(), paid, c.getOutstanding(), rec, v.get("f20"), pm.getShortType() + (x33 != null ? " proceeds " + sw(x33.amount()) : ""));
                    ts.event(K, c.getId(), c.getRef(), c.getMilestone(), from, c.getStatus(), rec, (a == A.CASH_CREDIT ? "cash letter credited " : "proceeds collected ") + c.getCurrency() + " " + sw(paid)
                            + (x33 != null ? " (remitted " + sw(x33.amount()) + ")" : "") + "; outstanding " + sw(c.getOutstanding()));
                };
            }
            case CASH_ADJUST -> {
                currency(c, x32, errs);
                currency(c, x33, errs);
                if (z(c.getPaidAmount()).signum() <= 0) errs.add("BIZ-K12 no credit has been advised for " + c.getRef() + " - nothing to adjust");
                if (x33 == null || x33.amount() == null) errs.add("BIZ-V03 adjustment amount (33C/33D) missing");
                if (!errs.isEmpty()) return null;
                boolean credit = v.containsKey("f33C");
                BigDecimal adj = credit ? x33.amount() : x33.amount().negate();
                if (z(c.getPaidAmount()).add(adj).signum() < 0 || z(c.getOutstanding()).subtract(adj).signum() < 0) {
                    errs.add("BIZ-K01 adjustment " + sw(adj) + " inconsistent with paid " + sw(c.getPaidAmount()) + " / outstanding " + sw(c.getOutstanding()));
                    return null;
                }
                return () -> {
                    c.setPaidAmount(z(c.getPaidAmount()).add(adj));
                    c.setOutstanding(z(c.getOutstanding()).subtract(adj));
                    c.setMilestone(c.getOutstanding().signum() <= 0 ? "PAID" : c.getPaidAmount().signum() > 0 ? "PARTIALLY_PAID" : "NON_PAID");
                    save(c, rec);
                    ts.movement(K, c.getId(), "ADJUSTMENT", c.getCurrency(), adj, c.getOutstanding(), rec, v.get("f20"), flat(v.get("f77A")));
                    ts.event(K, c.getId(), c.getRef(), "ADJUSTED", from, c.getStatus(), rec, "cash letter credit adjusted by " + sw(adj) + ": " + flat(v.get("f77A")));
                };
            }
            case DISHONOUR -> {
                BigDecimal deb = x33 == null ? null : x33.amount();
                currency(c, x32, errs);
                if (deb == null) errs.add("BIZ-V03 amount debited (33D) missing");
                else if (deb.compareTo(z(c.getPaidAmount())) > 0) errs.add("BIZ-K10 amount debited " + sw(deb) + " exceeds the amount credited so far " + sw(c.getPaidAmount()));
                if (!errs.isEmpty()) return null;
                return () -> {
                    c.setPaidAmount(z(c.getPaidAmount()).subtract(deb));
                    c.setOutstanding(z(c.getOutstanding()).add(deb));
                    c.setMilestone("NON_PAID");
                    save(c, rec);
                    ts.movement(K, c.getId(), "DISHONOUR", c.getCurrency(), deb.negate(), c.getOutstanding(), rec, v.get("f20"), flat(v.get("f77D")));
                    ts.event(K, c.getId(), c.getRef(), "DISHONOURED", from, c.getStatus(), rec, "item dishonoured, " + sw(deb) + " debited: " + flat(v.get("f77A")) + " / " + flat(v.get("f77D")));
                };
            }
            case SETTLEMENT -> {
                BigDecimal amt = Facts.mtAmount(v.get("f32A"));
                currency(c, new Amt(Facts.mtCurrency(v.get("f32A"), true), amt, null), errs);
                if (amt != null && amt.compareTo(z(c.getPaidAmount())) > 0)
                    errs.add("BIZ-K10 cover amount " + sw(amt) + " exceeds the proceeds advised so far " + c.getCurrency() + " " + sw(c.getPaidAmount()));
                if (!errs.isEmpty()) return null;
                return () -> { save(c, rec); ts.event(K, c.getId(), c.getRef(), "COVER_RECEIVED", from, c.getStatus(), rec, pm.getShortType() + " cover " + c.getCurrency() + " " + sw(amt) + " for collection proceeds"); };
            }
            default -> { return null; }
        }
    }

    // --------------------------------------------------------------------------------------------- persistence / status

    private void save(DocCollection c, MessageRecord rec) {
        if (c.getId() == null) {
            c.setStatus(c.getMilestone());
            c.setUpdatedAt(Instant.now());
            repo.save(c);
        }
        c.setStatus(!TERMINAL.contains(c.getMilestone()) && Boolean.TRUE.equals(c.getTracerPending()) ? "TRACER" : c.getMilestone());
        c.setUpdatedAt(Instant.now());
        if (rec != null) c.setLastMessageId(rec.getId());
        repo.save(c);
    }

    @Override public int sweep() { return 0; }

    private LedgerService.Outcome outcome(String status, List<String> errs, String ref, DocCollection c, String from, Map<String, String> out) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (c != null) vars.putAll(context(c));
        vars.putAll(out);
        vars.put("ldg_outcome", status);
        return new LedgerService.Outcome(status, errs, ref, from, c == null ? null : c.getStatus(), vars);
    }

    @Override
    public boolean exists(String ref) {
        return byAnyRef(ref) != null;
    }

    @Override
    public Optional<Map<String, String>> context(String ref) {
        return Optional.ofNullable(byAnyRef(ref)).map(this::context);
    }

    Map<String, String> context(DocCollection c) {
        Map<String, String> v = new LinkedHashMap<>();
        LocalDate today = ts.clock.today();
        v.put("ref", c.getRef());
        v.put("collRef", c.getRef());
        v.put("ldg_family", K);
        v.put("ldg_status", c.getStatus());
        v.put("coll_milestone", c.getMilestone());
        v.put("coll_type", c.getCollType());
        v.put("currency", c.getCurrency());
        v.put("ldg_amount", sw(c.getAmount()));
        v.put("coll_amount", sw(c.getAmount()));
        v.put("coll_amountIso", iso(c.getAmount()));
        v.put("outstanding", sw(z(c.getOutstanding())));
        v.put("outstandingIso", iso(z(c.getOutstanding())));
        v.put("coll_paid", sw(z(c.getPaidAmount())));
        v.put("coll_paidIso", iso(z(c.getPaidAmount())));
        BigDecimal half = z(c.getOutstanding()).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        v.put("coll_halfOutstanding", sw(half));
        v.put("coll_halfOutstandingIso", iso(half));
        v.put("coll_overAmount", sw(z(c.getOutstanding()).add(new BigDecimal("1000"))));
        v.put("kickoffAmount", sw(z(c.getOutstanding()).signum() > 0 ? c.getOutstanding() : c.getPaidAmount()));
        if (c.getTenor() != null) v.put("coll_tenor", c.getTenor());
        LocalDate mat = c.getMaturityDate() != null ? c.getMaturityDate() : today.plusDays(tenorDays(c.getTenor()));
        v.put("coll_maturity6", mat.format(YYMMDD));
        v.put("coll_maturityIso", mat.toString());
        v.put("coll_collectingRef", c.getCollectingRef() == null ? "NONREF" : c.getCollectingRef());
        if (c.getDrawee() != null) v.put("coll_drawee", c.getDrawee());
        if (c.getDrawer() != null) v.put("coll_drawer", c.getDrawer());
        v.put("coll_remittingBic", c.getRemittingBic());
        v.put("coll_collectingBic", c.getCollectingBic());
        v.put("coll_tracerCount", String.valueOf(c.getTracerCount() == null ? 0 : c.getTracerCount()));
        v.put("coll_tracerPending", String.valueOf(Boolean.TRUE.equals(c.getTracerPending())));
        v.put("coll_returnRequested", String.valueOf(Boolean.TRUE.equals(c.getReturnRequested())));
        return v;
    }

    static int tenorDays(String tenor) {
        if (tenor == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})\\s*DAYS").matcher(tenor.toUpperCase());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    @Override
    public Optional<Map<String, String>> suggest(String key, String from, String to) {
        String k = key == null ? "" : key;
        for (DocCollection c : repo.findAllByOrderByUpdatedAtDesc()) {
            Set<String> fr = ts.roles(K, c.getId(), from), tr = ts.roles(K, c.getId(), to);
            boolean coll = fr.contains("COLLECTING") && tr.contains("REMITTING");
            boolean rem = fr.contains("REMITTING") && tr.contains("COLLECTING");
            boolean live = !TERMINAL.contains(c.getMilestone());
            boolean ok = switch (k) {
                case "MT410", "COLL_ACK" -> coll && "SENT".equals(c.getMilestone());
                case "MT412", "COLL_ACCEPT" -> coll && live && "DA".equals(c.getCollType()) && z(c.getAcceptedAmount()).signum() == 0;
                case "MT416", "COLL_DISHONOUR" -> coll && live && !"NON_PAID".equals(c.getMilestone()) && !"NON_ACCEPTED".equals(c.getMilestone());
                case "MT400", "COLL_PAYABLE" -> coll && live && z(c.getOutstanding()).signum() > 0;
                case "MT422", "COLL_FATE" -> coll && live;
                case "MT420", "COLL_TRACE" -> rem && live;
                case "MT430", "COLL_AMEND" -> rem && live;
                case "MT450", "COLL_CASH" -> coll && live && "CASH".equals(c.getCollType());
                case "MT455", "MT456", "MT202", "MT103", "COLL_PAID" -> coll && z(c.getPaidAmount()).signum() > 0;
                case "MT499", "MT799", "MT759", "COLL_ANY" -> (coll || rem) && live;
                default -> false;
            };
            if (ok) return Optional.of(context(c));
        }
        return Optional.empty();
    }

    private Map<String, Object> summary(DocCollection c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", c.getRef());
        m.put("family", K);
        m.put("status", c.getStatus());
        m.put("milestone", c.getMilestone());
        m.put("form", c.getCollType());
        m.put("collType", c.getCollType());
        m.put("rules", c.getRules());
        m.put("format", "MT");
        m.put("collectingRef", c.getCollectingRef());
        m.put("currency", c.getCurrency());
        m.put("originalAmount", c.getOriginalAmount());
        m.put("amount", c.getAmount());
        m.put("outstanding", c.getOutstanding());
        m.put("paidAmount", c.getPaidAmount());
        m.put("acceptedAmount", c.getAcceptedAmount());
        m.put("tenor", c.getTenor());
        m.put("maturityDate", c.getMaturityDate());
        m.put("expiryDate", c.getMaturityDate());
        m.put("drawer", c.getDrawer());
        m.put("drawee", c.getDrawee());
        m.put("documents", c.getDocuments());
        m.put("issuerBic", c.getRemittingBic());
        m.put("remittingBic", c.getRemittingBic());
        m.put("collectingBic", c.getCollectingBic());
        m.put("tracerPending", c.getTracerPending());
        m.put("tracerCount", c.getTracerCount());
        m.put("returnRequested", c.getReturnRequested());
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    @Override
    public List<Map<String, Object>> list(String status, String party) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DocCollection c : repo.findAllByOrderByUpdatedAtDesc()) {
            if (status != null && !status.equalsIgnoreCase(c.getStatus())) continue;
            List<TradeParty> ps = ts.parties.findByKindAndLedgerIdOrderByIdAsc(K, c.getId());
            if (party != null && ps.stream().noneMatch(p -> party.equalsIgnoreCase(p.getBic()) || party.equalsIgnoreCase(p.getParticipantId()))) continue;
            Map<String, Object> m = summary(c);
            m.put("parties", ps.stream().map(p -> p.getRole() + "=" + p.getBic() + (p.getParticipantId() != null ? "(" + p.getParticipantId() + ")" : "")).toList());
            out.add(m);
        }
        return out;
    }

    @Override
    public Optional<Map<String, Object>> detail(String ref, boolean includeRaw) {
        DocCollection c = byAnyRef(ref);
        if (c == null) return Optional.empty();
        Map<String, Object> m = summary(c);
        m.put("parties", ts.parties.findByKindAndLedgerIdOrderByIdAsc(K, c.getId()));
        m.put("movements", ts.movements.findByKindAndLedgerIdOrderByIdAsc(K, c.getId()));
        List<TradeEvent> hist = new ArrayList<>(ts.events.findByKindAndRefAndLedgerIdIsNullOrderByIdAsc(K, c.getRef()));
        hist.addAll(ts.events.findByKindAndLedgerIdOrderByIdAsc(K, c.getId()));
        hist.sort(Comparator.comparing(TradeEvent::getId));
        m.put("history", hist);
        List<MessageRecord> msgs = new ArrayList<>(messages.findForRef(c.getRef()));
        if (c.getCollectingRef() != null) {
            Set<Long> seen = new HashSet<>();
            msgs.forEach(x -> seen.add(x.getId()));
            for (MessageRecord x : messages.findForRef(c.getCollectingRef())) if (seen.add(x.getId())) msgs.add(x);
            msgs.sort(Comparator.comparing(MessageRecord::getId));
        }
        if (!includeRaw) msgs.forEach(x -> { x.setRaw(null); x.setDeliveredRaw(null); });
        m.put("messages", msgs);
        m.put("context", context(c));
        return Optional.of(m);
    }

    @Override
    public Object addParty(String ref, String bic, String role) {
        DocCollection c = repo.findByRef(ref).orElseThrow(() -> new NoSuchElementException("unknown collection " + ref));
        if (!ROLES.contains(role)) throw new IllegalArgumentException("role must be one of " + ROLES);
        TradeParty p = ts.addParty(K, c.getId(), bic, role, "admin API (out-of-band relationship)");
        ts.event(K, c.getId(), c.getRef(), "PARTY_ADDED", c.getStatus(), c.getStatus(), null, role + "=" + bic + " added via admin API");
        return p;
    }

    @Override
    public long clear() {
        long n = repo.count();
        ts.events.deleteAll(ts.events.findAll().stream().filter(e -> K.equals(e.getKind())).toList());
        ts.movements.deleteAll(ts.movements.findAll().stream().filter(e -> K.equals(e.getKind())).toList());
        ts.parties.deleteAll(ts.parties.findAll().stream().filter(e -> K.equals(e.getKind())).toList());
        repo.deleteAllInBatch();
        return n;
    }
}
