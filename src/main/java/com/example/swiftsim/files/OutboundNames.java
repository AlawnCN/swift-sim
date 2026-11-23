package com.example.swiftsim.files;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Payment-application file names. MT: {@code MT700PYHNHFCY3PUDF7S.txt}. MX: {@code MXPYHNHE327RFJ24G.xml}.
 * The suffix is random so two publishes do not share a name. Callers must still create the file
 * without replacing an existing name (see {@link AtomicFiles}).
 */
public final class OutboundNames {
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private OutboundNames() {}

    public static boolean xmlPayload(String format, String payload) {
        if ("MX".equalsIgnoreCase(format)) return true;
        if (payload == null) return false;
        String s = payload.stripLeading();
        return s.startsWith("<") || s.startsWith("\uFEFF<");
    }

    /** {@code MT700} / {@code MTACK} / {@code MX}. */
    public static String prefix(String format, String msgType, boolean xml) {
        if (xml) return "MX";
        String kind = "UNK";
        if (msgType != null && !msgType.isBlank()) {
            String u = msgType.toUpperCase(Locale.ROOT).trim();
            if (u.matches("MT\\d{3}")) kind = u.substring(2);
            else if (u.matches("\\d{3}")) kind = u;
            else {
                kind = u.replaceAll("[^A-Z0-9]", "");
                if (kind.startsWith("MT") && kind.length() > 2) kind = kind.substring(2);
            }
            if (kind.length() > 6) kind = kind.substring(0, 6);
            if (kind.isEmpty()) kind = "UNK";
        }
        return "MT" + kind;
    }

    public static String randomSuffix(int len) {
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        return new String(c);
    }

    public static String nextName(String format, String msgType, String payload) {
        boolean xml = xmlPayload(format, payload);
        return prefix(format, msgType, xml) + randomSuffix(16) + (xml ? ".xml" : ".txt");
    }
}
