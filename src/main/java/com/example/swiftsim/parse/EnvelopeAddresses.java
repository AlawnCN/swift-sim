package com.example.swiftsim.parse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites only the network envelope sender and receiver of a message the compose screen is about to check or send.
 * MT input: block 1 LT is the sender ({@code A}), block 2 destination LT is the receiver ({@code X}).
 * MT output: block 1 LT is the receiver ({@code A}), the MIR inside block 2 carries the sender LT ({@code A}).
 * MX: the AppHdr {@code Fr}/{@code To} identifier only (namespace and prefix safe). Block 4, Document parties,
 * intermediary and beneficiary fields are left byte-for-byte. A slot that is not already a BIC8/BIC11/LT12 token,
 * a bare block 4, and a block 2 that is not a known input or output header are left unchanged.
 */
public final class EnvelopeAddresses {
    private EnvelopeAddresses() {}

    private static final Pattern INPUT_B1 = Pattern.compile("F01[A-Z0-9]{12}[0-9]{10}");
    /** Same shape as {@code MtHandler} block 2 input: I + MT + LT12 + optional priority, monitoring, obsolescence. */
    private static final Pattern INPUT_B2 = Pattern.compile("I[0-9]{3}[A-Z0-9]{12}(?:[SUN])?(?:[123])?(?:[0-9]{3})?");
    /** Shape produced by {@code GatewayService#toOutput}: O + MT + HHMM + MIR(date+LT+session+ISN) + YYMMDD + HHMM + priority. */
    private static final Pattern OUTPUT_B2 = Pattern.compile("O[0-9]{3}[0-9]{4}[0-9]{6}[A-Z0-9]{12}[0-9]{4}[0-9]{6}[0-9]{6}[0-9]{4}[SUN]");
    private static final Pattern MT_TYPE = Pattern.compile("\\{2:[IO]([0-9]{3})");
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z_][\\w.-]*:)?([A-Za-z_][\\w.-]*)([^>]*?)(/?)>");
    private static final Pattern BIC_TOKEN = Pattern.compile("\\s*([A-Za-z0-9]{8}|[A-Za-z0-9]{11}|[A-Za-z0-9]{12})\\s*");

    /** BIC8, BIC11, or a 12-character logical terminal (9th character is the LT code). Null when it is none of those. */
    public static String canonicalBic11(String address) {
        if (address == null) return null;
        String b = address.trim().toUpperCase();
        if (b.matches("[A-Z0-9]{12}")) return b.substring(0, 8) + b.substring(9);
        if (b.matches("[A-Z0-9]{8}")) return b + "XXX";
        if (b.matches("[A-Z0-9]{11}")) return b;
        return null;
    }

    /** 3-digit MT from block 2 of a full message, before block 4. Null for MX, bare block 4, and headers without one. */
    public static String mtType(String raw) {
        if (raw == null) return null;
        int cut = raw.indexOf("{4:");
        String head = cut < 0 ? raw : raw.substring(0, cut);
        Matcher m = MT_TYPE.matcher(head);
        return m.find() ? m.group(1) : null;
    }

    /**
     * @param sender   participant BIC8, BIC11, or LT12; null leaves the sender slot as it is
     * @param receiver participant BIC8, BIC11, or LT12; null leaves the receiver slot as it is
     */
    public static String apply(String raw, String sender, String receiver) {
        if (raw == null || raw.isEmpty()) return raw;
        String senderBic = canonicalBic11(sender);
        String receiverBic = canonicalBic11(receiver);
        if (senderBic == null && receiverBic == null) return raw;
        int i = 0;
        while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
        if (i < raw.length() && raw.charAt(i) == '<') return rewriteMx(raw, senderBic, receiverBic);
        if (raw.contains("{1:") || raw.contains("{2:")) return rewriteMt(raw, senderBic, receiverBic);
        return raw;
    }

    private static String lt(String bic11, char code) {
        return bic11.substring(0, 8) + code + bic11.substring(8);
    }

    private static String rewriteMt(String raw, String senderBic, String receiverBic) {
        int b4 = raw.indexOf("{4:");
        String head = b4 < 0 ? raw : raw.substring(0, b4);
        Block b2 = findBlock(head, '2');
        if (b2 == null) return raw;
        boolean input = b2.inner.startsWith("I");
        boolean output = b2.inner.startsWith("O");
        if (input) {
            if (!INPUT_B2.matcher(b2.inner).matches()) return raw;
        } else if (output) {
            if (!OUTPUT_B2.matcher(b2.inner).matches()) return raw;
        } else return raw;
        Block b1 = findBlock(head, '1');
        String next = head;
        if (input) {
            if (senderBic != null && b1 != null && INPUT_B1.matcher(b1.inner).matches()) next = putLt(next, b1, 3, senderBic, 'A');
            if (receiverBic != null) next = putLt(next, b2, 4, receiverBic, 'X');
        } else {
            if (receiverBic != null && b1 != null && INPUT_B1.matcher(b1.inner).matches()) next = putLt(next, b1, 3, receiverBic, 'A');
            if (senderBic != null) next = putLt(next, b2, 14, senderBic, 'A');
        }
        return next.equals(head) ? raw : next + raw.substring(head.length());
    }

    /** LT12 written over an existing 12-character address. Same length, so other block indexes stay valid. */
    private static String putLt(String head, Block block, int ltOffset, String bic11, char code) {
        int at = block.innerStart + ltOffset;
        return head.substring(0, at) + lt(bic11, code) + head.substring(at + 12);
    }

    private static Block findBlock(String head, char n) {
        String key = "{" + n + ":";
        int i = head.indexOf(key);
        if (i < 0) return null;
        int j = head.indexOf('}', i);
        if (j < 0) return null;
        return new Block(i + key.length(), head.substring(i + key.length(), j));
    }

    private record Block(int innerStart, String inner) {}

    private static String rewriteMx(String raw, String senderBic, String receiverBic) {
        Span app = firstElement(raw, 0, raw.length(), "AppHdr");
        if (app == null) return raw;
        List<Edit> edits = new ArrayList<>();
        if (senderBic != null) bicEdit(raw, directChild(raw, app, "Fr"), senderBic, edits);
        if (receiverBic != null) bicEdit(raw, directChild(raw, app, "To"), receiverBic, edits);
        if (edits.isEmpty()) return raw;
        edits.sort(Comparator.comparingInt((Edit e) -> e.start).reversed());
        String out = raw;
        for (Edit e : edits) out = out.substring(0, e.start) + e.text + out.substring(e.end);
        return out;
    }

    private static void bicEdit(String raw, Span party, String bic11, List<Edit> edits) {
        Span id = partyId(raw, party);
        if (id == null) return;
        String inner = raw.substring(id.innerStart, id.innerEnd);
        Matcher m = BIC_TOKEN.matcher(inner);
        if (!m.matches()) return;
        edits.add(new Edit(id.innerStart + m.start(1), id.innerStart + m.end(1), bic11));
    }

    /** Identifier the parser reads (BICFI), then a legacy BIC, then AnyBIC when that is the only one. */
    private static Span partyId(String raw, Span party) {
        if (party == null) return null;
        Span id = firstElement(raw, party.innerStart, party.innerEnd, "BICFI");
        if (id == null) id = firstElement(raw, party.innerStart, party.innerEnd, "BIC");
        if (id == null) id = firstElement(raw, party.innerStart, party.innerEnd, "AnyBIC");
        return id;
    }

    private static Span directChild(String s, Span parent, String local) {
        int depth = 0;
        int i = parent.innerStart;
        int to = parent.innerEnd;
        while (i < to) {
            int lt = s.indexOf('<', i);
            if (lt < 0 || lt >= to) return null;
            if (s.startsWith("<!--", lt)) {
                int c = s.indexOf("-->", lt + 4);
                if (c < 0 || c >= to) return null;
                i = c + 3;
                continue;
            }
            if (s.startsWith("<![CDATA[", lt)) {
                int c = s.indexOf("]]>", lt + 9);
                if (c < 0 || c >= to) return null;
                i = c + 3;
                continue;
            }
            Matcher m = TAG.matcher(s);
            m.region(lt, to);
            if (!m.lookingAt()) return null;
            boolean close = "/".equals(m.group(1));
            boolean self = "/".equals(m.group(5));
            if (self) { i = m.end(); continue; }
            if (close) {
                if (depth == 0) return null;
                depth--;
            } else {
                if (depth == 0 && local.equals(m.group(3))) return spanOf(s, lt, to);
                depth++;
            }
            i = m.end();
        }
        return null;
    }

    private static Span firstElement(String s, int from, int to, String local) {
        int i = from;
        while (i < to) {
            int lt = s.indexOf('<', i);
            if (lt < 0 || lt >= to) return null;
            if (s.startsWith("<!--", lt)) {
                int c = s.indexOf("-->", lt + 4);
                if (c < 0 || c >= to) return null;
                i = c + 3;
                continue;
            }
            if (s.startsWith("<![CDATA[", lt)) {
                int c = s.indexOf("]]>", lt + 9);
                if (c < 0 || c >= to) return null;
                i = c + 3;
                continue;
            }
            Matcher m = TAG.matcher(s);
            m.region(lt, to);
            if (!m.lookingAt()) { i = lt + 1; continue; }
            boolean close = "/".equals(m.group(1));
            boolean self = "/".equals(m.group(5));
            if (!close && !self && local.equals(m.group(3))) return spanOf(s, lt, to);
            i = m.end();
        }
        return null;
    }

    private static Span spanOf(String s, int openLt, int limit) {
        Matcher m = TAG.matcher(s);
        m.region(openLt, limit);
        if (!m.lookingAt()) return null;
        if ("/".equals(m.group(1)) || "/".equals(m.group(5))) return null;
        String prefix = m.group(2) == null ? "" : m.group(2);
        String local = m.group(3);
        int innerStart = m.end();
        int depth = 1;
        int i = innerStart;
        while (i < limit) {
            int lt = s.indexOf('<', i);
            if (lt < 0 || lt >= limit) return null;
            if (s.startsWith("<!--", lt)) {
                int c = s.indexOf("-->", lt + 4);
                if (c < 0 || c >= limit) return null;
                i = c + 3;
                continue;
            }
            if (s.startsWith("<![CDATA[", lt)) {
                int c = s.indexOf("]]>", lt + 9);
                if (c < 0 || c >= limit) return null;
                i = c + 3;
                continue;
            }
            m.region(lt, limit);
            if (!m.lookingAt()) return null;
            boolean close = "/".equals(m.group(1));
            boolean self = "/".equals(m.group(5));
            if (self) { i = m.end(); continue; }
            if (close) {
                depth--;
                if (depth == 0) {
                    String p = m.group(2) == null ? "" : m.group(2);
                    if (!prefix.equals(p) || !local.equals(m.group(3))) return null;
                    return new Span(innerStart, lt);
                }
            } else depth++;
            i = m.end();
        }
        return null;
    }

    private record Span(int innerStart, int innerEnd) {}

    private record Edit(int start, int end, String text) {}
}
