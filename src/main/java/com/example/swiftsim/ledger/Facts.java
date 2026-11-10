package com.example.swiftsim.ledger;

import com.example.swiftsim.parse.ParsedMessage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Map;

/** Business facts extracted from an MT or MX message, normalised for the ledger. */
class Facts {
    String ref, currency, form, rules, expiryType, beneficiary, applicant;
    String issuerBic, advisingBic, secondAdvisingBic, beneficiaryBic, applicantBic;
    BigDecimal amount, increase, decrease, demandAmount;
    LocalDate issueDate, expiryDate, newExpiry, requestedExpiry;
    Integer seq;
    String demandRef, demandType, response, status, statusCategory, code23H;
    Boolean consentRequired;
    /** MT759 45D narrative */
    String narrative;
    /** MT769 34B amount outstanding after the reduction */
    BigDecimal outstandingAfter;
    /** MT769 32B amount of charges (when 33B carries the reduction) */
    BigDecimal charges;

    /** STRICT so 31 Feb is rejected rather than rolled to 28 Feb (ResolverStyle.SMART). */
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("uuMMdd").withResolverStyle(ResolverStyle.STRICT);

    static BigDecimal mtAmount(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("^[A-Z]{3}", "");
        if (t.matches("^\\d{6}[A-Z]{3}.*")) t = t.substring(9);
        try {
            t = t.replace(',', '.');
            if (t.endsWith(".")) t = t + "0";
            return new BigDecimal(t);
        } catch (Exception e) {
            return null;
        }
    }

    static String mtCurrency(String s, boolean with32aDate) {
        if (s == null) return null;
        String t = s.trim();
        if (with32aDate && t.length() >= 9) return t.substring(6, 9);
        return t.length() >= 3 ? t.substring(0, 3) : null;
    }

    static LocalDate mtDate(String s) {
        try {
            return s == null ? null : LocalDate.parse(s.trim(), YYMMDD);
        } catch (Exception e) {
            return null;
        }
    }

    static LocalDate isoDate(String s) {
        try {
            return s == null ? null : LocalDate.parse(s.trim().substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    static BigDecimal dec(String s) {
        try {
            return s == null ? null : new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    static Integer integer(String s) {
        try {
            return s == null ? null : Integer.valueOf(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** last line of an option-A party field = BIC */
    static String bicOf(String f) {
        if (f == null) return null;
        String[] l = f.trim().split("\\R");
        String b = l[l.length - 1].trim();
        return b.matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?") ? (b.length() == 8 ? b + "XXX" : b) : null;
    }

    static Facts of(ParsedMessage pm) {
        Facts f = new Facts();
        Map<String, String> v = pm.getVars();
        f.ref = pm.getTxRef();
        String t = pm.getMsgType() == null ? "" : pm.getMsgType();
        if ("MT".equals(pm.getFormat())) {
            switch (t) {
                case "MT760" -> {
                    f.currency = mtCurrency(v.get("f32B"), false);
                    f.amount = mtAmount(v.get("f32B"));
                    f.issueDate = mtDate(v.get("f30"));
                    f.expiryDate = mtDate(v.get("f31E"));
                    f.expiryType = trim4(v.get("f23B"));
                    f.form = trim4(v.get("f22D"));
                    f.rules = trim4(v.get("f40C"));
                    f.beneficiary = v.getOrDefault("f59", v.get("f59A"));
                    f.beneficiaryBic = bicOf(v.get("f59A"));
                    f.applicant = v.get("f50");
                    f.issuerBic = bicOf(v.get("f52A"));
                    f.advisingBic = bicOf(v.get("f56A"));
                    f.secondAdvisingBic = bicOf(v.get("f57A"));
                }
                case "MT767" -> {
                    f.seq = integer(v.get("f26E"));
                    f.increase = mtAmount(v.get("f32B"));
                    f.decrease = mtAmount(v.get("f33B"));
                    f.currency = mtCurrency(v.containsKey("f32B") ? v.get("f32B") : v.get("f33B"), false);
                    f.newExpiry = mtDate(v.get("f31E"));
                }
                case "MT787" -> {
                    f.seq = integer(v.get("f26E"));
                    f.response = trim4(v.get("f23R"));
                }
                case "MT765" -> {
                    f.demandRef = v.get("f21");
                    f.demandType = trim4(v.get("f22G"));
                    f.demandAmount = mtAmount(v.get("f32B"));
                    f.currency = mtCurrency(v.get("f32B"), false);
                    f.requestedExpiry = mtDate(v.get("f31E"));
                }
                case "MT786" -> {
                    f.demandRef = v.get("f21");
                    f.demandAmount = mtAmount(v.get("f32B"));
                    f.currency = mtCurrency(v.get("f32B"), false);
                }
                case "MT202" -> {
                    f.amount = mtAmount(v.get("f32A"));
                    f.currency = mtCurrency(v.get("f32A"), true);
                }
                case "MT769" -> {
                    // SR2021+: 33B = amount reduced or released, 34B = amount outstanding, 32B = amount of charges.
                    // Legacy simulator templates carried the reduction in 32B -> used only when 33B is absent.
                    String red = v.containsKey("f33B") ? v.get("f33B") : v.get("f32B");
                    f.amount = mtAmount(red);
                    f.currency = mtCurrency(red, false);
                    f.outstandingAfter = mtAmount(v.get("f34B"));
                    if (v.containsKey("f33B")) f.charges = mtAmount(v.get("f32B"));
                }
                case "MT759" -> {
                    f.code23H = v.get("f23H") == null ? null : v.get("f23H").trim();
                    f.narrative = v.get("f45D");
                    f.demandRef = v.get("f21");
                }
                default -> { }
            }
        } else {
            f.currency = v.get("mx_currency");
            f.amount = dec(v.get("mx_amount"));
            f.issueDate = isoDate(v.get("mx_issueDate"));
            f.expiryDate = isoDate(v.get("mx_expiryDate"));
            f.expiryType = v.get("mx_expiryType");
            f.form = v.get("formOfUndertaking");
            f.rules = v.get("mx_rules");
            f.beneficiary = v.getOrDefault("mx_beneficiaryName", v.get("mx_beneficiaryBic"));
            f.beneficiaryBic = v.get("mx_beneficiaryBic");
            f.applicant = v.getOrDefault("mx_applicantName", v.get("mx_applicantBic"));
            f.applicantBic = v.get("mx_applicantBic");
            f.issuerBic = v.get("mx_issuerBic");
            f.advisingBic = v.get("mx_advisingBic");
            f.secondAdvisingBic = v.get("mx_secondAdvisingBic");
            f.increase = dec(v.get("mx_increase"));
            f.decrease = dec(v.get("mx_decrease"));
            f.newExpiry = isoDate(v.get("mx_newExpiryDate"));
            f.seq = integer(v.get("mx_seqNb"));
            f.demandRef = v.get("mx_demandId");
            f.demandType = v.get("mx_demandType");
            f.demandAmount = dec(v.get("mx_demandAmount"));
            f.requestedExpiry = isoDate(v.get("mx_requestedExpiry"));
            String as = v.get("mx_amendmentStatus");
            if (as != null) f.response = as;
            f.status = v.get("mx_status");
            f.statusCategory = v.get("mx_statusCategory");
            if (v.get("mx_consentRequired") != null) f.consentRequired = Boolean.valueOf(v.get("mx_consentRequired"));
        }
        if (f.issuerBic != null && f.issuerBic.length() == 8) f.issuerBic += "XXX";
        return f;
    }

    private static String trim4(String s) {
        if (s == null) return null;
        String t = s.trim().split("[\\n/]", 2)[0].trim();
        return t.length() > 4 ? t.substring(0, 4) : t;
    }
}
