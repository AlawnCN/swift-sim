package com.example.swiftsim.parse;

import com.example.swiftsim.config.Bics;
import com.prowidesoftware.swift.io.parser.SwiftParser;
import com.prowidesoftware.swift.model.SwiftMessage;
import com.prowidesoftware.swift.model.Tag;
import com.prowidesoftware.swift.model.field.Field;
import com.prowidesoftware.swift.model.field.Field32A;
import com.prowidesoftware.swift.model.field.Field32B;
import com.prowidesoftware.swift.model.mt.AbstractMT;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MT parsing with Prowide Core plus a light, explicitly documented set of checks.
 * Mandatory field lists are taken from the Prowide SRU2025 MT class structure javadoc (SWIFT SR2021+ guarantee redesign).
 * This is NOT full SWIFT network validation (that would need SWIFT's NVR rule set / a commercial validator).
 */
@Component
public class MtHandler {

    /** Mandatory tags per MT (from Prowide SRU2025 structure, mandatory sequences only). "52a" = any letter option. */
    static final Map<String, List<String>> MANDATORY = new LinkedHashMap<>();
    /** Code lists we use only for WARNINGS (TODO verify against SWIFT UHB / SR usage guidelines). */
    static final Map<String, Set<String>> CODE_WARN = new LinkedHashMap<>();
    /** Which tag holds the undertaking (transaction) reference, by MT. */
    static final Map<String, List<String>> TXREF_TAGS = new LinkedHashMap<>();

    /** MT -> tag -> known code words (warning only). Code is the first line / text before '/' of the field. */
    static final Map<String, Map<String, Set<String>>> CODE_WARN_MT = new LinkedHashMap<>();

    static {
        MANDATORY.put("760", List.of("15A", "27", "22A", "15B", "20", "30", "22D", "40C", "23B", "52a", "59a", "32B", "77U"));
        MANDATORY.put("767", List.of("15A", "27", "21", "22A", "15B", "20", "26E", "30", "52a"));
        MANDATORY.put("768", List.of("20", "21", "30"));
        MANDATORY.put("769", List.of("20", "21"));
        MANDATORY.put("765", List.of("20", "21", "52a", "22G", "32B"));
        MANDATORY.put("785", List.of("20", "52a", "31C", "59a"));
        MANDATORY.put("786", List.of("20", "52a", "30", "32B", "77J"));
        MANDATORY.put("787", List.of("20", "21", "52a", "26E", "23R"));
        MANDATORY.put("759", List.of("27", "20", "22D", "23H", "45D"));
        // settlement legs (Category 1/2): MT202 cover / MT103 customer payment (mandatory fields from Prowide SRU2025)
        MANDATORY.put("202", List.of("20", "21", "32A", "58a"));
        MANDATORY.put("103", List.of("20", "23B", "32A", "50a", "59a", "71A"));
        // ---- Category 7 documentary credits (mandatory fields of the mandatory sequence, Prowide SRU2025 = SR2025 structure)
        MANDATORY.put("700", List.of("27", "40A", "20", "31C", "40E", "31D", "50", "59", "32B", "41a", "49"));
        MANDATORY.put("701", List.of("27", "20"));
        MANDATORY.put("705", List.of("40A", "20", "31D", "50", "59", "32B"));
        MANDATORY.put("707", List.of("27", "20", "21", "23", "31C", "26E", "30", "22A"));
        MANDATORY.put("708", List.of("27", "20", "21", "23", "26E", "30"));
        MANDATORY.put("710", List.of("27", "40B", "20", "21", "31C", "40E", "31D", "50", "59", "32B", "41a", "49"));
        MANDATORY.put("711", List.of("27", "20", "21"));
        MANDATORY.put("720", List.of("27", "40B", "20", "21", "31C", "40E", "31D", "50", "59", "32B", "41a", "49"));
        MANDATORY.put("721", List.of("27", "20", "21"));
        MANDATORY.put("730", List.of("20", "21", "30"));
        MANDATORY.put("732", List.of("20", "21", "30", "32B"));
        MANDATORY.put("734", List.of("20", "21", "32A", "77J", "77B"));
        MANDATORY.put("740", List.of("20", "40F", "32B", "41a"));
        MANDATORY.put("742", List.of("20", "21", "52a", "32B", "34a"));
        MANDATORY.put("744", List.of("20", "21", "52a", "21A", "34a", "73R", "73S"));
        MANDATORY.put("747", List.of("20", "30"));
        MANDATORY.put("750", List.of("20", "21", "32B", "77J"));
        MANDATORY.put("752", List.of("20", "21", "23", "30"));
        MANDATORY.put("754", List.of("20", "21", "32a"));
        MANDATORY.put("756", List.of("20", "21", "32B", "33A"));
        MANDATORY.put("799", List.of("20", "79"));
        // ---- Category 4 collections (MT405 is not part of SR2025 / Prowide SRU2025 -> not supported)
        MANDATORY.put("400", List.of("20", "21", "32a", "33A"));
        MANDATORY.put("410", List.of("20", "21", "32a"));
        MANDATORY.put("412", List.of("20", "21", "32A"));
        MANDATORY.put("416", List.of("20", "21", "21A", "32a"));
        MANDATORY.put("420", List.of("20", "21", "32a"));
        MANDATORY.put("422", List.of("20", "21"));
        MANDATORY.put("430", List.of("20", "21"));
        MANDATORY.put("450", List.of("20", "21", "30", "32A"));
        MANDATORY.put("455", List.of("20", "21", "30", "32A", "33a", "77A"));
        MANDATORY.put("456", List.of("20", "21", "32a", "33D", "77A", "77D"));
        MANDATORY.put("499", List.of("20", "79"));
        // continuations of MT760 / MT767 (SR2021). At least one of 77U/77L is enforced in networkRules.
        MANDATORY.put("761", List.of("27", "20", "52a"));
        MANDATORY.put("775", List.of("27", "20", "21", "26E", "52a"));
        // request for cancellation / answer. MT792 does not cancel an irrevocable undertaking or credit.
        MANDATORY.put("792", List.of("20", "21", "11S", "79"));
        MANDATORY.put("796", List.of("20", "21", "76"));

        CODE_WARN.put("22D", Set.of("DGAR", "STBY", "DEPU", "DOCR", "UNDK"));
        CODE_WARN.put("40C", Set.of("ISPR", "NONE", "OTHR", "UCPR", "URDG"));
        CODE_WARN.put("23B", Set.of("COND", "FIXD", "OPEN"));
        CODE_WARN.put("22G", Set.of("PAYM", "PAEX"));
        CODE_WARN.put("23R", Set.of("ACPT", "REJT"));
        CODE_WARN.put("23H", Set.of("CLSVCLOS", "CLSVOPEN", "FRAUDMSG", "GENINFAD", "ISSAMEND", "ISSUANCE",
                "OTHERFNC", "REIMBURS", "REQAMEND", "REQFINAN", "REQISSUE", "TRANSFER"));

        // per-MT code words (warnings only - TODO verify against the SR2025 UHB usage guidelines)
        CODE_WARN_MT.put("700", Map.of(
                "40A", Set.of("IRREVOCABLE", "IRREVOCABLE TRANSFERABLE", "IRREVOCABLE STANDBY", "IRREVOC TRANS STANDBY"),
                "40E", Set.of("EUCP LATEST VERSION", "EUCPURR LATEST VERSION", "ISP LATEST VERSION", "OTHR", "UCP LATEST VERSION", "UCPURR LATEST VERSION"),
                "43P", Set.of("ALLOWED", "CONDITIONAL", "NOT ALLOWED"), "43T", Set.of("ALLOWED", "CONDITIONAL", "NOT ALLOWED"),
                "49", Set.of("CONFIRM", "MAY ADD", "WITHOUT")));
        CODE_WARN_MT.put("705", Map.of("40A", Set.of("IRREVOCABLE", "IRREVOCABLE TRANSFERABLE", "IRREVOCABLE STANDBY", "IRREVOC TRANS STANDBY")));
        CODE_WARN_MT.put("707", Map.of("22A", Set.of("ACNF", "ADVI", "ISCO", "ISSU"), "23S", Set.of("CANCEL"),
                "40A", Set.of("IRREVOCABLE", "IRREVOCABLE TRANSFERABLE", "IRREVOCABLE STANDBY", "IRREVOC TRANS STANDBY"),
                "49", Set.of("CONFIRM", "MAY ADD", "WITHOUT")));
        CODE_WARN_MT.put("710", Map.of("40B", Set.of("IRREVOCABLE", "IRREVOCABLE TRANSFERABLE"), "49", Set.of("CONFIRM", "MAY ADD", "WITHOUT")));
        CODE_WARN_MT.put("720", Map.of("40B", Set.of("IRREVOCABLE", "IRREVOCABLE TRANSFERABLE"), "49", Set.of("CONFIRM", "MAY ADD", "WITHOUT")));
        CODE_WARN_MT.put("740", Map.of("40F", Set.of("URR LATEST VERSION", "NOTURR"), "71A", Set.of("CLM", "OUR")));
        CODE_WARN_MT.put("752", Map.of("23", Set.of("ACCEPT", "DEBIT", "NEGOTIATE", "REIMBURSE", "REMITTED", "SEE79Z")));
        CODE_WARN_MT.put("734", Map.of("77B", Set.of("HOLD", "NOTIFY", "PREVINST", "RETURN")));
        CODE_WARN_MT.put("767", Map.of("22A", Set.of("ACNA", "ADVA", "ICCA", "ISCA", "ISUA")));

        TXREF_TAGS.put("768", List.of("21", "20"));
        TXREF_TAGS.put("769", List.of("21", "20"));
        TXREF_TAGS.put("202", List.of("21", "20"));
        TXREF_TAGS.put("759", List.of("23", "21", "20"));
        TXREF_TAGS.put("103", List.of("21", "20"));
        // documentary credits: transaction reference = documentary credit number (or presentation / claim reference, resolved by the LC ledger)
        TXREF_TAGS.put("707", List.of("23", "20"));
        TXREF_TAGS.put("708", List.of("23", "20"));
        for (String t : List.of("710", "711", "720", "721", "730", "742", "750", "754", "732", "734", "756", "744")) TXREF_TAGS.put(t, List.of("21", "20"));
        TXREF_TAGS.put("799", List.of("21", "20"));
        // collections: collection reference of the remitting bank (= :20: of the instruction / tracer / amendment, :21: of the collecting bank's answers)
        for (String t : List.of("400", "410", "412", "422", "450", "455", "456")) TXREF_TAGS.put(t, List.of("21", "20"));
        TXREF_TAGS.put("416", List.of("21A", "21", "20"));
        TXREF_TAGS.put("499", List.of("21", "20"));
        TXREF_TAGS.put("792", List.of("21", "20"));
        TXREF_TAGS.put("796", List.of("21", "20"));
        TXREF_TAGS.put("761", List.of("20"));
        TXREF_TAGS.put("775", List.of("20", "21"));
    }


    private static final Pattern REF16 = Pattern.compile("^[^\\s].{0,15}$");
    private static final Pattern AMOUNT = Pattern.compile("^[A-Z]{3}(\\d{1,14},\\d{0,14})$");
    /** STRICT: 31 Feb must NAK (SIM-F03), not roll forward under ResolverStyle.SMART. */
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("uuMMdd").withResolverStyle(ResolverStyle.STRICT);

    public static boolean looksLikeMt(String raw) {
        String t = raw.stripLeading();
        return t.startsWith("{1:") || t.startsWith(":20:") || t.startsWith(":27:") || t.startsWith(":15A:") || t.contains("{4:");
    }

    public ParsedMessage parse(String raw) {
        ParsedMessage pm = new ParsedMessage();
        pm.setFormat("MT");
        pm.setNormalizedRaw(raw.strip());
        SwiftMessage sm;
        try {
            sm = new SwiftParser(raw.strip()).message();
        } catch (Exception e) {
            pm.getErrors().add("SIM-P01 FIN parse error: " + e.getMessage());
            return pm;
        }
        if (sm == null || sm.getBlock1() == null || sm.getBlock2() == null || sm.getBlock4() == null) {
            pm.getErrors().add("SIM-P02 FIN message must contain blocks {1:}, {2:} and {4:}");
            return pm;
        }
        String type = sm.getType();
        headerChecks(raw.strip(), type, pm);
        pm.setMsgType("MT" + type);
        pm.setShortType("MT" + type);
        pm.setSender(Bics.toBic11(sm.getSender()));
        pm.setReceiver(Bics.toBic11(sm.getReceiver()));
        try {
            AbstractMT mt = AbstractMT.parse(raw.strip());
            pm.setProwideClass(mt == null ? null : mt.getClass().getName());
        } catch (Exception e) {
            pm.getWarnings().add("Prowide model class not created: " + e.getMessage());
        }
        if (type == null || !MANDATORY.containsKey(type)) {
            if ("405".equals(type)) pm.getErrors().add("SIM-T01 MT405 (clean collection) is not part of the SR2025 Category 4 message set (withdrawn; not in Prowide SRU2025) - use the collection instruction convention (MT499 /COLLINST/) instead");
            else pm.getErrors().add("SIM-T01 message type MT" + type + " not supported by this simulator (supported: " + String.join(",", new TreeSet<>(MANDATORY.keySet())) + ")");
            return pm;
        }
        List<Tag> tags = sm.getBlock4().getTags();
        if (tags == null || tags.isEmpty()) {
            pm.getErrors().add("SIM-P03 block 4 is empty");
            return pm;
        }
        Map<String, String> first = new LinkedHashMap<>();
        for (Tag t : tags) {
            first.putIfAbsent(t.getName(), t.getValue() == null ? "" : t.getValue());
            if (Field.getField(t.getName(), t.getValue() == null ? "" : t.getValue()) == null) {
                pm.getWarnings().add("Unknown/unsupported field tag :" + t.getName() + ": (Prowide could not map it)");
            }
        }
        // mandatory fields
        for (String m : MANDATORY.get(type)) {
            boolean found;
            if (m.endsWith("a")) {
                String num = m.substring(0, 2);
                found = first.keySet().stream().anyMatch(k -> k.matches(num + "[A-Z]?"));
            } else {
                found = first.containsKey(m);
            }
            if (!found) pm.getErrors().add("SIM-M01 mandatory field :" + m + ": missing for MT" + type);
        }
        // reference fields (16x, no leading/trailing slash, no '//')
        for (Tag t : tags) {
            String n = t.getName();
            String v = t.getValue() == null ? "" : t.getValue();
            if (n.equals("20") || n.equals("21") || n.equals("23")) {
                if (v.isEmpty() || v.length() > 16 || v.contains("\n") || !REF16.matcher(v).matches())
                    pm.getErrors().add("SIM-F01 field :" + n + ": must be 1-16 characters on one line");
                if (v.startsWith("/") || v.endsWith("/") || v.contains("//"))
                    pm.getErrors().add("SIM-F02 field :" + n + ": must not start/end with '/' or contain '//' (SWIFT error T26)");
            }
            if (n.equals("31D") || n.equals("44C")) {
                String d = v.trim().length() >= 6 ? v.trim().substring(0, 6) : v.trim();
                try {
                    LocalDate.parse(d, YYMMDD);
                } catch (DateTimeParseException e) {
                    pm.getErrors().add("SIM-F03 field :" + n + ": invalid date '" + d + "' (expected YYMMDD" + (n.equals("31D") ? " followed by the place of expiry" : "") + ")");
                }
            }
            if (n.matches("32[CD]|3[34][ACD]")) {
                if (!v.trim().matches("^\\d{6}[A-Z]{3}\\d{1,14},\\d{0,14}$"))
                    pm.getErrors().add("SIM-F05 field :" + n + ": must be 6!n3!a15d (date, currency, amount with decimal comma)");
                else try { LocalDate.parse(v.trim().substring(0, 6), YYMMDD); } catch (DateTimeParseException e) {
                    pm.getErrors().add("SIM-F03 field :" + n + ": invalid date '" + v.trim().substring(0, 6) + "' (expected YYMMDD)");
                }
            }
            if (n.equals("30") || n.equals("31C") || n.equals("31E") || n.equals("31L") || n.equals("31S")) {
                try {
                    LocalDate.parse(v.trim(), YYMMDD);
                } catch (DateTimeParseException e) {
                    pm.getErrors().add("SIM-F03 field :" + n + ": invalid date '" + v + "' (expected YYMMDD)");
                }
            }
            if (n.equals("32B") || n.equals("33B") || n.equals("34B")) {
                if (!AMOUNT.matcher(v.trim()).matches())
                    pm.getErrors().add("SIM-F04 field :" + n + ": must be 3!a15d with decimal comma, e.g. USD50000,00 (got '" + v + "')");
            }
            if (n.equals("32A")) {
                if (!v.trim().matches("^\\d{6}[A-Z]{3}\\d{1,14},\\d{0,14}$"))
                    pm.getErrors().add("SIM-F05 field :32A: must be 6!n3!a15d, e.g. 261029USD100,00");
            }
            if (n.equals("26E") && !v.trim().matches("^\\d{1,3}$"))
                pm.getErrors().add("SIM-F06 field :26E: must be 1-3 digits");
            currencyOfAmount(n, v, pm);
            if (fieldFormatValidation && !flagged(pm, n)) {
                String fe = SwiftFormat.check(n, v);
                if (fe != null) pm.getErrors().add(fe);
            }
            Map<String, Set<String>> mtCodes = CODE_WARN_MT.getOrDefault(type, Map.of());
            if (mtCodes.containsKey(n)) {
                String code = v.split("\\n", 2)[0].trim();
                if (n.equals("77B") || n.equals("23S") && code.startsWith("/")) code = code.replaceAll("^/([A-Z0-9]+)/?.*$", "$1");
                if (!mtCodes.get(n).contains(code))
                    pm.getWarnings().add("Field :" + n + ": of MT" + type + " code '" + code + "' not in simulator's known list " + new TreeSet<>(mtCodes.get(n)) + " (TODO verify against SWIFT UHB SR2025)");
            } else if (CODE_WARN.containsKey(n)) {
                String code = v.split("[\\n/]", 2)[0].trim();
                if (!CODE_WARN.get(n).contains(code))
                    pm.getWarnings().add("Field :" + n + ": code '" + code + "' not in simulator's known list " + CODE_WARN.get(n) + " (TODO verify against SWIFT UHB)");
            }
        }
        // two network validated rules for MT765 documented in SR2021 release material (see README)
        if (type.equals("765")) {
            if ("PAYM".equals(first.get("22G")) && first.containsKey("31E"))
                pm.getErrors().add("SIM-N01 MT765: if field 22G is PAYM, field 31E is not allowed");
            String f49 = first.get("49A");
            if (f49 != null && f49.startsWith("INCP") != first.containsKey("77"))
                pm.getErrors().add("SIM-N02 MT765: field 77 must be present if and only if field 49A is INCP");
        }

        networkRules(type, first, tags, pm);

        // variables for templates/scenario conditions
        first.forEach((k, v) -> pm.getVars().put("f" + k, v));
        if ("760".equals(type)) {
            sequenceFields(tags, "15C", null).forEach((k, v) -> pm.getVars().put("seqC_" + k, v));
            sequenceFields(tags, "15B", "15C").forEach((k, v) -> pm.getVars().putIfAbsent("seqB_" + k, v));
        }
        if ("456".equals(type)) aggregateDishonour(tags, pm);
        String txRef = null;
        String txTag = null;
        List<String> refTags = TXREF_TAGS.getOrDefault(type, List.of("20"));
        if (type.equals("499") && first.getOrDefault("79", "").contains("/COLLINST/")) refTags = List.of("20");
        for (String t : refTags) {
            if (first.containsKey(t) && !first.get(t).isBlank() && !"NONREF".equals(first.get(t).trim())) { txRef = first.get(t).trim(); txTag = t; break; }
        }
        pm.setTxRef(txRef);
        if (MANDATORY.containsKey(type) && (type.startsWith("4") || Set.of("700", "701", "705", "707", "708", "710", "711", "720", "721", "730", "732", "734", "740", "742", "744", "747", "750", "752", "754", "756", "799", "103").contains(type)))
            pm.setRelatedRef("20".equals(txTag) ? first.get("21") : first.get("20"));
        else
            pm.setRelatedRef(type.equals("768") || type.equals("769") || type.equals("202") ? first.get("20") : first.get("21"));
        pm.getVars().put("ref", txRef);
        String amt = first.getOrDefault("32B", first.get("32A"));
        if (amt != null) {
            String a = amt.trim();
            if (first.containsKey("32B")) {
                try {
                    Field32B f = new Field32B(a);
                    pm.getVars().put("currency", f.getCurrency());
                    pm.getVars().put("amount", f.getAmount());
                } catch (Exception ignored) { }
            } else {
                try {
                    Field32A f = new Field32A(a);
                    pm.getVars().put("currency", f.getCurrency());
                    pm.getVars().put("amount", f.getAmount());
                } catch (Exception ignored) { }
            }
            String am = pm.getVars().get("amount");
            if (am != null) pm.getVars().put("amountIso", am.replace(',', '.').replaceAll("\\.$", ".00"));
        }
        if (first.containsKey("22G")) pm.getVars().put("demandType", first.get("22G").trim());
        if (first.containsKey("26E")) pm.getVars().put("amendmentNo", first.get("26E").trim());
        if (first.containsKey("31E")) {
            pm.getVars().put("newExpiry6", first.get("31E").trim());
            try {
                pm.getVars().put("newExpiryIso", LocalDate.parse(first.get("31E").trim(), YYMMDD).toString());
            } catch (Exception ignored) { }
        }
        String issuer = first.getOrDefault("52A", null);
        if (issuer != null) {
            String[] lines = issuer.split("\\R");
            pm.getVars().put("issuerBic", lines[lines.length - 1].trim());
        }
        if (first.containsKey("22D")) pm.getVars().put("formOfUndertaking", first.get("22D").trim());
        return pm;
    }

    private static String ccyOf(String v, boolean withDate) {
        if (v == null) return null;
        String t = v.trim();
        if (withDate) return t.length() >= 9 ? t.substring(6, 9) : null;
        return t.length() >= 3 ? t.substring(0, 3) : null;
    }

    /** currency of an amount field by tag (letter option A = date+ccy+amount, others ccy+amount) */
    private static String ccy(Map<String, String> f, String num) {
        for (String k : f.keySet()) if (k.matches(num + "[A-Z]?")) return ccyOf(f.get(k), k.endsWith("A") || f.get(k).matches("(?s)^\\d{6}[A-Z]{3}.*"));   // option A / C / D (date + currency + amount)
        return null;
    }

    private static boolean has(Map<String, String> f, String num) {
        return f.keySet().stream().anyMatch(k -> k.matches(num + "[A-Z]?"));
    }

    /**
     * A subset of SWIFT network validated rules (C-rules) for Category 4 / 7 documentary credit and collection messages
     * that the simulator enforces (SIM-Nxx). Rule texts paraphrased from SWIFT UHB knowledge - TODO verify numbering/wording against SR2025.
     */
    static void networkRules(String type, Map<String, String> f, List<Tag> tags, ParsedMessage pm) {
        List<String> e = pm.getErrors();
        switch (type) {
            case "760" -> mt760Rules(f, tags, pm);
            case "761", "775" -> {
                if (!f.containsKey("77U") && !f.containsKey("77L"))
                    e.add("SIM-N65 MT" + type + ": at least one of field 77U or 77L (continuation narrative) must be present");
            }
            case "700", "710", "720", "740" -> {
                boolean c42 = f.containsKey("42C"), a42 = f.containsKey("42A") || f.containsKey("42D");
                if (c42 != a42) e.add("SIM-N10 MT" + type + ": fields 42C (drafts at) and 42a (drawee) must be present together");
                int combos = (c42 || a42 ? 1 : 0) + (f.containsKey("42M") ? 1 : 0) + (f.containsKey("42P") ? 1 : 0);
                if (combos > 1) e.add("SIM-N11 MT" + type + ": only one of [42C+42a], 42M (mixed payment) or 42P (deferred payment) may be present");
                if (!type.equals("740") && f.containsKey("44C") && f.containsKey("44D"))
                    e.add("SIM-N12 MT" + type + ": either field 44C (latest date of shipment) or 44D (shipment period) may be present, not both");
                if (type.equals("700") && f.containsKey("49") && f.get("49").trim().equals("WITHOUT") && has(f, "58"))
                    e.add("SIM-N13 MT700: field 58a (requested confirmation party) is not allowed when field 49 is WITHOUT");
                String by = f.containsKey("41A") ? f.get("41A") : f.get("41D");
                if (by != null) {
                    String[] l = by.split("\\R");
                    String code = l[l.length - 1].trim();
                    if (!Set.of("BY ACCEPTANCE", "BY DEF PAYMENT", "BY MIXED PYMT", "BY NEGOTIATION", "BY PAYMENT").contains(code))
                        e.add("SIM-N14 MT" + type + ": last line of field 41a must be one of BY ACCEPTANCE, BY DEF PAYMENT, BY MIXED PYMT, BY NEGOTIATION, BY PAYMENT (got '" + code + "')");
                    else {
                        if (code.equals("BY DEF PAYMENT") && !f.containsKey("42P")) pm.getWarnings().add("MT" + type + ": 41a BY DEF PAYMENT normally requires 42P (deferred payment details) - usage rule");
                        if (code.equals("BY MIXED PYMT") && !f.containsKey("42M")) pm.getWarnings().add("MT" + type + ": 41a BY MIXED PYMT normally requires 42M (mixed payment details) - usage rule");
                        if ((code.equals("BY ACCEPTANCE") || code.equals("BY NEGOTIATION")) && !f.containsKey("42C") && !type.equals("740"))
                            pm.getWarnings().add("MT" + type + ": 41a " + code + " normally comes with drafts (42C/42a) - usage rule");
                    }
                }
            }
            case "707" -> {
                if (f.containsKey("32B") && f.containsKey("33B")) e.add("SIM-N20 MT707: either field 32B (increase) or 33B (decrease) may be present, not both");
                if (f.containsKey("44C") && f.containsKey("44D")) e.add("SIM-N12 MT707: either field 44C or 44D may be present, not both");
                boolean c42 = f.containsKey("42C"), a42 = f.containsKey("42A") || f.containsKey("42D");
                if (c42 != a42) e.add("SIM-N10 MT707: fields 42C and 42a must be present together");
                if (f.containsKey("23S") && f.keySet().stream().anyMatch(k -> k.matches("3[12][BD]|33B|39A|4[0-7][A-Z]?|41[AD]") && !k.equals("40A")))
                    pm.getWarnings().add("MT707: cancellation request (23S CANCEL) should not be combined with other amendment details - usage rule");
            }
            case "747" -> {
                if (Set.of("31E", "32B", "33B", "34B", "39A", "39C", "72Z", "77").stream().noneMatch(f::containsKey))
                    e.add("SIM-N30 MT747: at least one of 31E, 32B, 33B, 34B, 39A, 39C, 72Z or 77 must be present");
                if ((f.containsKey("32B") || f.containsKey("33B")) && !f.containsKey("34B"))
                    e.add("SIM-N31 MT747: if field 32B or 33B is present, field 34B (new amount) must also be present");
                if (f.containsKey("32B") && f.containsKey("33B")) e.add("SIM-N32 MT747: either field 32B or 33B may be present, not both");
                sameCcy(type, f, e, "32", "33", "34");
            }
            case "742" -> sameCcy(type, f, e, "32", "33", "34");
            case "744" -> sameCcy(type, f, e, "34");
            case "750" -> sameCcy(type, f, e, "32", "33", "34");
            case "752" -> {
                if (f.containsKey("32B") && f.containsKey("71D") && !has(f, "33"))
                    e.add("SIM-N40 MT752: if fields 32B and 71D are both present, field 33a (net amount) must also be present");
                sameCcy(type, f, e, "32", "33");
            }
            case "754" -> {
                if (f.containsKey("72Z") && f.containsKey("77")) e.add("SIM-N41 MT754: either field 72Z or 77 may be present, not both");
                if (has(f, "53") && has(f, "57")) e.add("SIM-N42 MT754: either field 53a or 57a may be present, not both");
                sameCcy(type, f, e, "32", "33", "34");
            }
            case "756" -> sameCcy(type, f, e, "32", "33");
            case "734" -> {
                if (f.containsKey("73A") && !has(f, "33")) e.add("SIM-N43 MT734: if field 73A (charges claimed) is present, field 33a (total amount claimed) must also be present");
                sameCcy(type, f, e, "32", "33");
            }
            case "400", "455" -> sameCcy(type, f, e, "32", "33");
            case "456" -> sameCcy(type, f, e, "32", "33");
            default -> { }
        }
    }

    /** SR2021 MT760 conditional rules the simulator enforces (SIM-N60..N64). Not the full NVR set. */
    private static void mt760Rules(Map<String, String> f, List<Tag> tags, ParsedMessage pm) {
        List<String> e = pm.getErrors();
        String purpose = f.get("22A") == null ? null : f.get("22A").trim().split("[\\n/]", 2)[0].trim();
        boolean seqC = tags.stream().anyMatch(t -> "15C".equals(t.getName()));
        boolean counter = "ISCO".equals(purpose) || "ICCA".equals(purpose);
        if (purpose != null && !Set.of("ACNA", "ADVI", "ICCA", "ISCO", "ISSU").contains(purpose))
            e.add("SIM-N60 MT760: field 22A must be ACNA, ADVI, ICCA, ISCO or ISSU (got '" + purpose + "')");
        if (counter && !seqC)
            e.add("SIM-N61 MT760: sequence C (local / requested undertaking) is mandatory when field 22A is " + purpose);
        if (purpose != null && !counter && seqC)
            e.add("SIM-N62 MT760: sequence C is not allowed when field 22A is " + purpose);
        if (purpose == null && seqC)
            e.add("SIM-N62 MT760: sequence C is only allowed when field 22A is ISCO or ICCA");
        check760Form49(sequenceFields(tags, "15B", "15C"), "sequence B", e);
        if (seqC) {
            Map<String, String> c = sequenceFields(tags, "15C", null);
            check760Form49(c, "sequence C", e);
            if (counter && (!c.containsKey("22D") || !(c.containsKey("32B")) || !(c.containsKey("59") || c.containsKey("59A"))))
                e.add("SIM-N64 MT760: sequence C must contain the local undertaking fields 22D, 32B and 59a");
        }
    }

    private static void check760Form49(Map<String, String> seq, String where, List<String> e) {
        String form = seq.get("22D") == null ? null : seq.get("22D").trim().split("[\\n/]", 2)[0].trim();
        if ("STBY".equals(form) && !seq.containsKey("49"))
            e.add("SIM-N63 MT760: field 49 (confirmation instructions) is mandatory in " + where + " when field 22D is STBY");
        if (("DGAR".equals(form) || "DEPU".equals(form)) && seq.containsKey("49"))
            e.add("SIM-N63 MT760: field 49 is not allowed in " + where + " when field 22D is " + form);
    }

    /** Fields of one MT760 sequence, from its 15a marker up to (not including) the next marker. */
    static Map<String, String> sequenceFields(List<Tag> tags, String start, String end) {
        Map<String, String> m = new LinkedHashMap<>();
        boolean on = false;
        for (Tag t : tags) {
            String n = t.getName();
            if (start.equals(n)) { on = true; continue; }
            if (on && n != null && n.matches("15[A-C]")) {
                if (end == null || end.equals(n)) break;
                break;
            }
            if (on) m.putIfAbsent(n, t.getValue() == null ? "" : t.getValue());
        }
        return m;
    }

    private static void currencyOfAmount(String tag, String value, ParsedMessage pm) {
        if (tag == null || value == null || !tag.matches("3[2-9][A-D]?")) return;
        String t = value.trim();
        boolean dated = t.matches("^\\d{6}[A-Z]{3}\\d{1,14},\\d{0,14}$");
        boolean plain = t.matches("^[A-Z]{3}\\d{1,14},\\d{0,14}$");
        if (!dated && !plain) return;
        String err = dated ? Iso4217.checkSwift(t.substring(6, 9), t.substring(9)) : Iso4217.checkSwift(t.substring(0, 3), t.substring(3));
        if (err != null) pm.getErrors().add("SIM-F07 field :" + tag + ": " + err);
    }

    /** MT456 may repeat one sequence per dishonoured item. Sum the debits and keep every narrative. */
    private static void aggregateDishonour(List<Tag> tags, ParsedMessage pm) {
        List<Tag> debits = tags.stream().filter(t -> t.getName() != null && t.getName().startsWith("33")).toList();
        if (debits.size() <= 1) {
            pm.getVars().put("f456Items", "1");
            return;
        }
        BigDecimal sum = BigDecimal.ZERO;
        String prefix = null;
        for (Tag t : debits) {
            String v = t.getValue() == null ? "" : t.getValue().trim();
            int amtAt = v.matches("^\\d{6}[A-Z]{3}.*") ? 9 : 3;
            if (prefix == null) prefix = v.substring(0, amtAt);
            try {
                String num = v.substring(amtAt).replace(',', '.');
                if (num.endsWith(".")) num = num + "0";
                sum = sum.add(new BigDecimal(num));
            } catch (Exception ignored) { }
        }
        String name = debits.get(0).getName();
        int exp = prefix != null && prefix.length() >= 3 ? Optional.ofNullable(Iso4217.exponent(prefix.substring(prefix.length() - 3))).orElse(2) : 2;
        pm.getVars().put("f" + name, prefix + sum.setScale(exp, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ','));
        pm.getVars().put("f456Items", String.valueOf(debits.size()));
        joinRepeat(tags, pm, "77A");
        joinRepeat(tags, pm, "77D");
    }

    private static void joinRepeat(List<Tag> tags, ParsedMessage pm, String name) {
        List<String> parts = tags.stream().filter(t -> name.equals(t.getName()) && t.getValue() != null && !t.getValue().isBlank())
                .map(t -> t.getValue().trim()).toList();
        if (parts.size() > 1) pm.getVars().put("f" + name, String.join("\n", parts));
    }

    private static void sameCcy(String type, Map<String, String> f, List<String> e, String... nums) {
        String first = null, firstTag = null;
        for (String n : nums) {
            String c = ccy(f, n);
            if (c == null) continue;
            if (first == null) { first = c; firstTag = n; }
            else if (!first.equals(c)) {
                e.add("SIM-N50 MT" + type + ": the currency code in the amount fields must be the same (field " + firstTag + "a " + first + " vs " + n + "a " + c + ")");
                return;
            }
        }
    }

    /** network validation of the format of each field (Prowide validatorPattern), see SwiftFormat */
    private volatile boolean fieldFormatValidation = true;

    public void setFieldFormatValidation(boolean b) {
        this.fieldFormatValidation = b;
    }

    private static boolean flagged(ParsedMessage pm, String tag) {
        String k = ":" + tag + ":";
        return pm.getErrors().stream().anyMatch(e -> e.contains(k));
    }

    private static final Pattern B1 = Pattern.compile("\\{1:([^}]*)}");
    private static final Pattern B2 = Pattern.compile("\\{2:([^}]*)}");
    private static final Pattern B3 = Pattern.compile("\\{3:((?:\\{[^}]*})*)}");
    private static final Pattern B5 = Pattern.compile("\\{5:((?:\\{[^}]*})*)}\\s*$");
    private static final Pattern SUBTAG = Pattern.compile("\\{([0-9A-Z]{3}):([^}]*)}");
    private static final Pattern B1_FMT = Pattern.compile("^F01([A-Z0-9]{12})([0-9]{4})([0-9]{6})$");
    private static final Pattern B2_IN = Pattern.compile("^I([0-9]{3})([A-Z0-9]{12})([SUN])?([123])?([0-9]{3})?$");
    private static final Pattern UETR = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    /** MTs for which block 3 field 121 (UETR) is mandatory (SWIFT gpi mandate, SR2018+) - only 202 is used here. */
    private static final Set<String> UETR_MANDATORY = Set.of("103", "202", "205");

    /** Header (block 1/2/3/5) checks, FIN-like. Codes are simulator codes. */
    void headerChecks(String raw, String type, ParsedMessage pm) {
        Matcher m1 = B1.matcher(raw);
        if (!m1.find()) {
            pm.getErrors().add("SIM-H01 basic header block {1:} missing");
        } else {
            Matcher f = B1_FMT.matcher(m1.group(1));
            if (!f.matches()) pm.getErrors().add("SIM-H01 block 1 must be F01 + 12-char LT address + 4-digit session + 6-digit sequence number (got '" + m1.group(1) + "')");
            else {
                pm.getVars().put("_session", f.group(2));
                pm.getVars().put("_isn", f.group(3));
            }
        }
        Matcher m2 = B2.matcher(raw);
        if (m2.find()) {
            String b2 = m2.group(1);
            if (b2.startsWith("O")) pm.getErrors().add("SIM-H03 block 2 is in output format ({2:O...}); a message must be submitted in input format ({2:I...})");
            else {
                Matcher f = B2_IN.matcher(b2);
                if (!f.matches()) pm.getErrors().add("SIM-H02 block 2 must be I + 3-digit MT + 12-char receiver LT address + [priority S/U/N][delivery monitoring 1/2/3][obsolescence 3n] (got '" + b2 + "')");
                else {
                    String prio = f.group(3) == null ? "N" : f.group(3);
                    pm.getVars().put("_priority", prio);
                    if (f.group(4) != null) {
                        pm.getVars().put("_monitoring", f.group(4));
                        if ("N".equals(prio) && !"2".equals(f.group(4)))
                            pm.getWarnings().add("Block 2: delivery monitoring " + f.group(4) + " with priority N - non-delivery warning (1/3) is normally only used with priority U (TODO verify, simulator warns only)");
                    }
                }
            }
        }
        Matcher m3 = B3.matcher(raw);
        boolean has121 = false;
        if (m3.find()) {
            Matcher t = SUBTAG.matcher(m3.group(1));
            while (t.find()) {
                String tag = t.group(1), val = t.group(2);
                if (tag.equals("108")) {
                    pm.getVars().put("_mur", val);
                    if (val.isEmpty() || val.length() > 16 || SwiftFormat.check("20", val) != null)
                        pm.getErrors().add("SIM-H31 block 3 field 108 (MUR) must be 1-16 x characters");
                } else if (tag.equals("121")) {
                    has121 = true;
                    pm.getVars().put("_uetr", val);
                    if (!UETR.matcher(val).matches())
                        pm.getErrors().add("SIM-H32 block 3 field 121 (UETR) must be a lower-case UUID version 4 (xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx)");
                }
            }
        }
        if (type != null && UETR_MANDATORY.contains(type) && !has121)
            pm.getErrors().add("SIM-H21 MT" + type + " requires block 3 field 121 (UETR)");
        int b4 = raw.indexOf("{4:");
        if (b4 >= 0) {
            if (!raw.startsWith("{4:\n", b4)) pm.getErrors().add("SIM-H04 block 4 must start with '{4:' followed by a line break");
            if (!raw.contains("\n-}")) pm.getErrors().add("SIM-H04 block 4 must end with a line break followed by '-}'");
        }
        Matcher m5 = B5.matcher(raw);
        if (m5.find()) {
            Matcher t = SUBTAG.matcher(m5.group(1));
            while (t.find()) {
                String tag = t.group(1);
                if (tag.equals("PDE")) pm.getVars().put("_pde", "true");
                else if (Set.of("CHK", "MAC", "PAC", "TNG", "PDM", "SYS", "DLM", "MRF").contains(tag))
                    pm.getWarnings().add("Block 5 trailer {" + tag + ":} is set by the network/interface, not by the user - ignored");
            }
        }
    }
}
