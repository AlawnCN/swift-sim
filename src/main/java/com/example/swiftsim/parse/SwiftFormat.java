package com.example.swiftsim.parse;

import com.prowidesoftware.swift.model.field.Field;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Field format / length / character-set check driven by Prowide's {@code Field.validatorPattern()} (e.g. "16x",
 * "4!c[/35x]", "&lt;CUR&gt;&lt;AMOUNT&gt;15", "65z[$65z]0-149"). The pattern is translated into a Java regex.
 * This is an approximation of SWIFT network validation (no NVR / usage rules, no code-word checks).
 */
public final class SwiftFormat {
    private SwiftFormat() {}

    /** SWIFT "x" character set (without CrLf). */
    public static final String X = "A-Za-z0-9/\\-?:().,'+ ";
    /** SWIFT "z" character set (without CrLf). */
    public static final String Z = X + "=!\"%&*;<>{@#_";
    private static final Pattern Z_ALL = Pattern.compile("[" + Z + "\\n]*");

    private static final Map<String, Optional<Pattern>> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> PATTERN_TEXT = new ConcurrentHashMap<>();

    public static String patternText(String tag) {
        compiled(tag);
        return PATTERN_TEXT.getOrDefault(tag, "?");
    }

    static Optional<Pattern> compiled(String tag) {
        return CACHE.computeIfAbsent(tag, t -> {
            try {
                Field f = Field.getField(t, "");
                if (f == null) return Optional.empty();
                String vp = f.validatorPattern();
                PATTERN_TEXT.put(t, vp);
                if (vp == null || vp.contains("CUSTOM")) return Optional.empty();
                if (vp.equals("$")) return Optional.of(Pattern.compile("^$"));
                String rx = toRegex(vp);
                return rx == null ? Optional.empty() : Optional.of(Pattern.compile("^(?:" + rx + ")$"));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    /** @return null if ok / not checkable, otherwise an error text */
    public static String check(String tag, String value) {
        String v = value == null ? "" : value.replace("\r\n", "\n");
        if (!Z_ALL.matcher(v).matches()) {
            for (char c : v.toCharArray()) {
                if (c != '\n' && Z.indexOf(c) < 0 && !(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9'))
                    return "SIM-C01 field :" + tag + ": contains character '" + c + "' (U+" + String.format("%04X", (int) c)
                            + ") outside the SWIFT x/z character set";
            }
        }
        Optional<Pattern> p = compiled(tag);
        if (p.isEmpty()) return null;
        if (!p.get().matcher(v).matches())
            return "SIM-F10 field :" + tag + ": value does not match SWIFT format " + PATTERN_TEXT.get(tag)
                    + " (length / charset / lines; Prowide validatorPattern)";
        return null;
    }

    /** Translate a Prowide validator pattern to a regex; returns null for unsupported constructs. */
    static String toRegex(String p) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '[') {
                int depth = 0, j = i;
                for (; j < p.length(); j++) {
                    if (p.charAt(j) == '[') depth++;
                    else if (p.charAt(j) == ']' && --depth == 0) break;
                }
                if (j >= p.length()) return null;
                String inner = toRegex(p.substring(i + 1, j));
                if (inner == null) return null;
                i = j + 1;
                var m = java.util.regex.Pattern.compile("^(\\d+)-(\\d+)").matcher(p.substring(i));
                if (m.find()) {
                    sb.append("(?:").append(inner).append("){").append(m.group(1)).append(',').append(m.group(2)).append('}');
                    i += m.end();
                } else {
                    sb.append("(?:").append(inner).append(")?");
                }
            } else if (c == '$') {
                sb.append("\\n");
                i++;
            } else if (c == '(') {
                int j = p.indexOf(')', i);
                if (j < 0) return null;
                i = j + 1; // annotations such as (**) / (***)
            } else if (c == '<') {
                int j = p.indexOf('>', i);
                if (j < 0) return null;
                String tok = p.substring(i + 1, j);
                i = j + 1;
                switch (tok) {
                    case "DATE2" -> sb.append("[0-9]{6}");
                    case "DATE4" -> sb.append("[0-9]{8}");
                    case "TIME2" -> sb.append("[0-9]{4}");
                    case "CUR" -> sb.append("[A-Z]{3}");
                    case "CC" -> sb.append("[A-Z]{2}");
                    case "DC" -> sb.append("[DC]");
                    case "BIC" -> sb.append("[A-Z]{6}[A-Z0-9]{2}(?:[A-Z0-9]{3})?");
                    case "AMOUNT" -> {
                        int k = i;
                        while (k < p.length() && Character.isDigit(p.charAt(k))) k++;
                        int n = k > i ? Integer.parseInt(p.substring(i, k)) : 15;
                        i = k;
                        sb.append(amount(n));
                    }
                    default -> { return null; }
                }
            } else if (Character.isDigit(c)) {
                int k = i;
                while (k < p.length() && Character.isDigit(p.charAt(k))) k++;
                int n = Integer.parseInt(p.substring(i, k));
                boolean exact = k < p.length() && p.charAt(k) == '!';
                if (exact) k++;
                if (k >= p.length()) return null;
                char t = p.charAt(k);
                i = k + 1;
                String cls = switch (t) {
                    case 'n' -> "[0-9]";
                    case 'a' -> "[A-Z]";
                    case 'c' -> "[A-Z0-9]";
                    case 'x' -> "[" + X + "]";
                    case 'z' -> "[" + Z + "]";
                    case 'd' -> null;
                    default -> "?";
                };
                if ("?".equals(cls)) return null;
                if (t == 'd') sb.append(amount(n));
                else sb.append(cls).append(exact ? "{" + n + "}" : "{1," + n + "}");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return sb.toString();
    }

    private static String amount(int n) {
        return "(?=[0-9,]{2," + n + "}(?![0-9,]))[0-9]+,[0-9]*";
    }
}
