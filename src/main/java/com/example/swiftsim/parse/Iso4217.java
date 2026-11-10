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
        // exponent 2 unless noted. Codes grouped for readability.
        put(2, "USD EUR GBP AUD CAD CHF CNY HKD SGD SEK NOK DKK NZD ZAR AED SAR QAR INR PKR BDT LKR THB MYR PHP TWD "
                + "KES TZS NGN GHS EGP MAD ETB MUR ZMW MWK BWP NAD SZL LSL MZN AOA SCR SOS SDG SSP RON PLN CZK HUF TRY "
                + "ILS JOD BRL MXN ARS COP PEN CLP UYU BOB PYG DOP GTQ HNL NIO CRC PAB BSD BBD BMD KYD XCD AWG ANG "
                + "FJD WST TOP SBD VUV PGK NPR AFN ALL AMD AZN BAM BGN BHD GEL HRK MDL MKD RSD UAH GMD GNF HTG IQD "
                + "IRR KGS KHR KPW KZT LAK LBP LRD LYD MGA MMK MNT MRO MRU MVR NIO OMR QAR RWF SLL STD STN SYP TJS "
                + "TMT TND UZS VEF VES YER ZWL CUC CUP CVE FKP GIP SHP SSP SVC WST XAG XAU XPD XPT");
        // The line above includes some 0- and 3-exponent codes by mistake if I used one put. Override below.
        put(0, "JPY KRW VND XOF XAF XPF UGX RWF BIF DJF GNF KMF PYG CLP ISK VUV XAU XAG XPD XPT UYI");
        put(3, "BHD KWD OMR JOD TND IQD LYD");
        put(4, "CLF UYW");
        // re-assert the ones the exponent-2 list may have overwritten incorrectly
        put(2, "CLP"); // wait CLP is 0. Fix explicitly:
        EXP.put("CLP", 0);
        EXP.put("PYG", 0);
        EXP.put("GNF", 0);
        EXP.put("RWF", 0);
        EXP.put("VUV", 0);
        EXP.put("IQD", 3);
        EXP.put("LYD", 3);
        EXP.put("OMR", 3);
        EXP.put("TND", 3);
        EXP.put("BHD", 3);
        EXP.put("JOD", 3);
        EXP.put("KWD", 3);
        EXP.put("IDR", 2);
        EXP.put("KHR", 2);
        EXP.put("MGA", 2); // ISO exponent is 2 (ariary); some sources say 1/5. Use 2.
        // Kenya and neighbours explicitly
        EXP.put("KES", 2);
        EXP.put("TZS", 2);
        EXP.put("UGX", 0);
    }

    private Iso4217() {}

    private static void put(int exp, String codes) {
        for (String c : codes.trim().split("\\s+")) if (!c.isEmpty()) EXP.put(c, exp);
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
