package com.example.swiftsim.parse;

import java.util.HashMap;
import java.util.Map;

/**
 * ISO 4217 currency codes and minor-unit exponents used for SWIFT T40 (unknown currency) and T43
 * (decimal digits must equal the currency exponent). Not every historic code is listed; an unknown
 * alphabetic code is rejected.
 */
public final class Iso4217 {
    private static final Map<String, Integer> EXP = new HashMap<>();

    static {
        put(0, "BIF CLP DJF GNF ISK JPY KMF KRW PYG RWF UGX UYI VND VUV XAF XAG XAU XOF XPD XPF XPT");
        put(3, "BHD IQD JOD KWD LYD OMR TND");
        put(4, "CLF UYW");
        put(2, "AED AFN ALL AMD ANG AOA ARS AUD AWG AZN BAM BBD BDT BGN BMD BOB BRL BSD BTN BWP CAD CDF CHF CNY COP CRC CUC CUP CVE CZK "
                + "DKK DOP DZD EGP ERN ETB EUR FJD FKP GBP GEL GHS GIP GMD GTQ GYD HKD HNL HRK HTG HUF IDR ILS INR IRR JMD KES KGS KHR KPW KZT KYD "
                + "LAK LBP LKR LRD LSL MAD MDL MGA MKD MMK MNT MOP MRO MRU MUR MVR MWK MXN MYR MZN NAD NGN NIO NOK NPR NZD PAB PEN PGK PHP PKR PLN "
                + "QAR RON RSD RUB SAR SBD SCR SDG SEK SGD SHP SLE SLL SOS SRD SSP STD STN SVC SYP SZL THB TJS TMT TOP TRY TTD TWD TZS UAH UYU "
                + "USD UZS VES VEF WST XCD YER ZAR ZMW ZWL");
    }

    private Iso4217() {}

    private static void put(int exp, String codes) {
        for (String c : codes.trim().split("\\s+")) {
            if (c.isEmpty()) continue;
            if (EXP.put(c, exp) != null) throw new IllegalStateException("duplicate ISO 4217 code " + c);
        }
    }

    public static Integer exponent(String ccy) {
        return ccy == null ? null : EXP.get(ccy);
    }

    public static boolean known(String ccy) {
        return exponent(ccy) != null;
    }

    /** SWIFT amount after the currency code, decimal comma. Null if ok. */
    public static String checkSwift(String ccy, String amountWithComma) {
        return check(ccy, amountWithComma, ',');
    }

    /** ISO 20022 decimal with a dot (or an integer). Null if ok. */
    public static String checkIso(String ccy, String amount) {
        if (amount == null) return "amount missing";
        String t = amount.trim();
        if (t.indexOf(',') >= 0) return "ISO amount must use a decimal point, not a comma";
        return check(ccy, t, '.');
    }

    private static String check(String ccy, String amount, char sep) {
        if (ccy == null || !ccy.matches("[A-Z]{3}")) return "currency code must be 3 letters";
        Integer exp = exponent(ccy);
        if (exp == null) return "currency " + ccy + " is not a known ISO 4217 code (SWIFT T40)";
        int frac = 0;
        int i = amount.lastIndexOf(sep);
        if (i >= 0) {
            String f = amount.substring(i + 1);
            if (!f.chars().allMatch(Character::isDigit)) return "amount has a non-numeric fraction";
            frac = f.length();
        } else if (sep == ',') {
            return "SWIFT amount must contain a decimal comma";
        }
        if (frac != exp) return "currency " + ccy + " requires " + exp + " decimal digit(s), got " + frac + " (SWIFT T43)";
        return null;
    }
}
