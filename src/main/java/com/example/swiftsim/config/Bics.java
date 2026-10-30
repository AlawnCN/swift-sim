package com.example.swiftsim.config;

public final class Bics {
    private Bics() {}

    /** Normalise BIC8 / BIC11 / 12-char logical terminal to BIC11. */
    public static String toBic11(String s) {
        if (s == null) return null;
        String b = s.trim().toUpperCase();
        if (b.length() == 12) return b.substring(0, 8) + b.substring(9);
        if (b.length() == 8) return b + "XXX";
        return b;
    }

    /** BIC11 to a 12-char logical terminal address (LT code 'A'). */
    public static String toLt(String bic, char lt) {
        String b = toBic11(bic);
        if (b == null || b.length() != 11) return b;
        return b.substring(0, 8) + lt + b.substring(8);
    }
}
