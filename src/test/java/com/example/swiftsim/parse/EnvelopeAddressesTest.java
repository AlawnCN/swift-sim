package com.example.swiftsim.parse;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Envelope sender/receiver only. Body BICs, bare block 4, and broken headers stay as written. */
class EnvelopeAddressesTest {
    private static final String MT456 = "{1:F01TSTAKEN0AXXX0000000000}{2:I456TSTJIN20XXXXN}{3:{108:SAMPLE456001}}{4:\n"
            + ":20:KECODSH0101\n:21:KECO2026000101\n:52A:TSTAKEN0XXX\n:56A:TSTCKEN0XXX\n:57A:TSTDKEN0XXX\n"
            + ":32B:USD80000,00\n-}{5:{PDE:}}";

    @Test
    void bic8Bic11AndLt12ResolveToTheSameBic11() {
        assertEquals("BOSHCNSHXXX", EnvelopeAddresses.canonicalBic11("  boshcnsh "));
        assertEquals("BOSHCNSHXXX", EnvelopeAddresses.canonicalBic11("BOSHCNSHXXX"));
        assertEquals("BOSHCNSH123", EnvelopeAddresses.canonicalBic11("boshcnshq123"));
        assertEquals("FAUMDEFFABC", EnvelopeAddresses.canonicalBic11("FAUMDEFFXABC"));
        assertNull(EnvelopeAddresses.canonicalBic11("BOSH"));
        assertNull(EnvelopeAddresses.canonicalBic11(null));
        assertNull(EnvelopeAddresses.canonicalBic11(""));
    }

    @Test
    void inputRoundTripKeepsBic8Bic11AndLt12OnTheSampleHeader() throws Exception {
        String sample = Files.readString(Path.of("samples/mt456-coll-dishonour.fin"));
        assertEquals("456", EnvelopeAddresses.mtType(sample));
        assertEquals(sample, EnvelopeAddresses.apply(sample, "TSTAKEN0XXX", "TSTJIN20XXX"));
        assertEquals(sample, EnvelopeAddresses.apply(sample, "TSTAKEN0", "TSTJIN20"));
        assertEquals(sample, EnvelopeAddresses.apply(sample, "TSTAKEN0AXXX", "TSTJIN20XXXX"));
    }

    @Test
    void inputCustomBicsReplaceHeaderOnly() {
        String out = EnvelopeAddresses.apply(MT456, "BOSHCNSH", "FAUMDEFFXABC");
        assertTrue(out.startsWith("{1:F01BOSHCNSHAXXX0000000000}{2:I456FAUMDEFFXABCN}{3:{108:SAMPLE456001}}"));
        assertTrue(out.contains(":52A:TSTAKEN0XXX\n:56A:TSTCKEN0XXX\n:57A:TSTDKEN0XXX\n"));
        assertTrue(out.endsWith("{5:{PDE:}}"));
        assertFalse(out.contains("TSTAKEN0AXXX"));
        assertFalse(out.contains("TSTJIN20"));
        assertEquals(out, EnvelopeAddresses.apply(out, "BOSHCNSHXXX", "FAUMDEFFABC"));
    }

    @Test
    void laterSelectionKeepsAManualBodyEdit() {
        String once = EnvelopeAddresses.apply(MT456, "BOSHCNSHXXX", "FAUMDEFFXXX");
        String edited = once.replace(":32B:USD80000,00", ":32B:USD80000,00\n:72:MANUAL EDIT");
        String twice = EnvelopeAddresses.apply(edited, "TSTBGB20", "FAUMDEFF");
        assertTrue(twice.startsWith("{1:F01TSTBGB20AXXX0000000000}{2:I456FAUMDEFFXXXXN}"));
        assertTrue(twice.contains(":72:MANUAL EDIT"));
        assertTrue(twice.contains(":52A:TSTAKEN0XXX"));
        assertTrue(twice.contains(":57A:TSTDKEN0XXX"));
        assertFalse(twice.contains("BOSHCNSH"));
    }

    @Test
    void oneSideCanChangeWithoutTheOther() {
        String senderOnly = EnvelopeAddresses.apply(MT456, "BOSHCNSH123", null);
        assertTrue(senderOnly.startsWith("{1:F01BOSHCNSHA1230000000000}{2:I456TSTJIN20XXXXN}"));
        String receiverOnly = EnvelopeAddresses.apply(MT456, null, "FAUMDEFF");
        assertTrue(receiverOnly.startsWith("{1:F01TSTAKEN0AXXX0000000000}{2:I456FAUMDEFFXXXXN}"));
        assertEquals(MT456, EnvelopeAddresses.apply(MT456, null, null));
        assertEquals(MT456, EnvelopeAddresses.apply(MT456, "NOT-A-BIC", "!!!"));
    }

    @Test
    void outputHeaderSwapsDirectionAndKeepsTheBody() {
        String raw = "{1:F01TSTJIN20AXXX0001000007}{2:O4561530261125TSTAKEN0AXXX00010000022611251531N}{3:{108:KEEP}}{4:\n"
                + ":20:BODY\n:52A:TSTAKEN0XXX\n:56A:INTERBANK0X\n-}{5:{CHK:ABCDEF012345}}";
        assertEquals("456", EnvelopeAddresses.mtType(raw));
        assertEquals(raw, EnvelopeAddresses.apply(raw, "TSTAKEN0AXXX", "TSTJIN20"));
        String out = EnvelopeAddresses.apply(raw, "BOSHCNSHQ123", "faumdeff");
        assertTrue(out.startsWith("{1:F01FAUMDEFFAXXX0001000007}{2:O4561530261125BOSHCNSHA12300010000022611251531N}"));
        assertTrue(out.contains("{3:{108:KEEP}}"));
        assertTrue(out.contains(":20:BODY\n:52A:TSTAKEN0XXX\n:56A:INTERBANK0X\n"));
        assertTrue(out.endsWith("{5:{CHK:ABCDEF012345}}"));
        ParsedMessage pm = new MtHandler().parse(out);
        assertEquals("BOSHCNSH123", pm.getSender());
        assertEquals("FAUMDEFFXXX", pm.getReceiver());
        assertTrue(pm.getErrors().stream().anyMatch(e -> e.contains("SIM-H03")));
    }

    @Test
    void bareBlock4AndBrokenHeadersAreNotHealed() {
        String bare = ":20:REF1\n:79:{1:F01SHOULDNOTCHANGE0000000000}\n";
        assertSame(bare, EnvelopeAddresses.apply(bare, "BOSHCNSHXXX", "FAUMDEFFXXX"));
        assertNull(EnvelopeAddresses.mtType(bare));
        String bad2 = "{1:F01TSTAKEN0AXXX0000000000}{2:I456SHORT}{4:\n:20:X\n:30:261399\n-}";
        assertSame(bad2, EnvelopeAddresses.apply(bad2, "BOSHCNSHXXX", "FAUMDEFFXXX"));
        String spaced = "{1:F01TSTAKEN0AXXX0000000000}{2:I456HAS SPACEXXXXN}{4:\n:20:X\n-}";
        assertSame(spaced, EnvelopeAddresses.apply(spaced, "BOSHCNSHXXX", "FAUMDEFFXXX"));
        String bad1 = "{1:F01SHORT}{2:I456TSTJIN20XXXXN}{4:\n:20:X\n-}";
        String fixedReceiver = EnvelopeAddresses.apply(bad1, "BOSHCNSHXXX", "FAUMDEFFXXX");
        assertTrue(fixedReceiver.startsWith("{1:F01SHORT}{2:I456FAUMDEFFXXXXN}"));
        String headOnly = "{1:F01TSTAKEN0AXXX0000000000}{2:I456TSTJIN20XXXXN}";
        assertEquals("{1:F01BOSHCNSHAXXX0000000000}{2:I456FAUMDEFFXXXXN}",
                EnvelopeAddresses.apply(headOnly, "BOSHCNSH", "FAUMDEFFXXX"));
        assertEquals("456", EnvelopeAddresses.mtType(headOnly));
    }

    @Test
    void loadedInvalidSampleKeepsTheBadBody() throws Exception {
        String bad = Files.readString(Path.of("samples/invalid-mt760-missing-fields.fin"));
        String out = EnvelopeAddresses.apply(bad, "BOSHCNSHXXX", "FAUMDEFFXXX");
        assertEquals("760", EnvelopeAddresses.mtType(out));
        assertTrue(out.contains("{1:F01BOSHCNSHAXXX0000000000}{2:I760FAUMDEFFXXXXN}"));
        assertTrue(out.contains(":15A:\n:27:1/1\n:22A:ISSU\n:15B:\n:20:/BAD//REF/\n:30:261399\n:22D:DGAR\n:32B:USD50000.00\n-}"));
        assertFalse(out.contains(":40C:"));
        assertFalse(out.contains(":59:"));
        assertFalse(out.contains(":77U:"));
        assertFalse(out.contains("TSTAKEN0"));
        assertFalse(out.contains("TSTCKEN0"));
    }

    @Test
    void block4TextThatLooksLikeAHeaderIsLeftAlone() {
        String raw = "{1:F01TSTAKEN0AXXX0000000000}{2:I456TSTJIN20XXXXN}{4:\n:79:SEE {1:F01NOTAREALHEADER0000000000}\n-}";
        String out = EnvelopeAddresses.apply(raw, "BOSHCNSHXXX", "FAUMDEFFXXX");
        assertTrue(out.contains(":79:SEE {1:F01NOTAREALHEADER0000000000}"));
        assertTrue(out.startsWith("{1:F01BOSHCNSHAXXX0000000000}{2:I456FAUMDEFFXXXXN}"));
    }

    @Test
    void mxAppHdrFrToFollowsTheSelectionAndTheDocumentDoesNot() throws Exception {
        String sample = Files.readString(Path.of("samples/tsrv.001-issuance.xml"));
        String out = EnvelopeAddresses.apply(sample, "BOSHCNSH", "FAUMDEFFAXXX");
        assertTrue(out.contains("<Fr><FIId><FinInstnId><BICFI>BOSHCNSHXXX</BICFI>"));
        assertTrue(out.contains("<To><FIId><FinInstnId><BICFI>FAUMDEFFXXX</BICFI>"));
        assertTrue(out.contains("<Issr><Nm>Your System Kenya Test Bank</Nm><Id><OrgId><AnyBIC>TSTAKEN0XXX</AnyBIC>"));
        assertTrue(out.contains("<AdvsgPty><Nm>Virtual Advising Bank Nairobi</Nm><Id><OrgId><AnyBIC>TSTCKEN0XXX</AnyBIC>"));
        assertTrue(out.contains("<BizMsgIdr>KEMX0001</BizMsgIdr>"));
        assertNull(EnvelopeAddresses.mtType(sample));
        assertEquals(out, EnvelopeAddresses.apply(out, "BOSHCNSHXXX", "FAUMDEFFXXX"));

        String pacs = Files.readString(Path.of("samples/pacs.008-customer-payment.xml"));
        String paid = EnvelopeAddresses.apply(pacs, "BOSHCNSHXXX", "FAUMDEFFXXX");
        assertEquals(2, count(paid, "TSTBGB20XXX"));
        assertTrue(paid.contains("<CdtrAgt><FinInstnId><BICFI>TSTJIN20XXX</BICFI>"));
        assertTrue(paid.contains("<Fr><FIId><FinInstnId><BICFI>BOSHCNSHXXX</BICFI>"));
        assertTrue(paid.contains("<To><FIId><FinInstnId><BICFI>FAUMDEFFXXX</BICFI>"));
    }

    @Test
    void mxPrefixRelatedAndAnyBicAreNamespaceSafe() {
        String raw = """
                <?xml version="1.0"?>
                <h:AppHdr xmlns:h="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                  <h:Fr><h:FIId><h:FinInstnId><h:BICFI>OLD1SENDER01</h:BICFI></h:FinInstnId></h:FIId></h:Fr>
                  <!-- <h:To><h:BICFI>NOPEXXXXXXX</h:BICFI></h:To> -->
                  <h:To><h:FIId><h:FinInstnId><h:BICFI>
                      OLD2RECVR001
                    </h:BICFI></h:FinInstnId></h:FIId></h:To>
                  <h:Rltd>
                    <h:Fr><h:FIId><h:FinInstnId><h:BICFI>KEEP1XXXXXX</h:BICFI></h:FinInstnId></h:FIId></h:Fr>
                    <h:To><h:FIId><h:FinInstnId><h:BICFI>KEEP2XXXXXX</h:BICFI></h:FinInstnId></h:FIId></h:To>
                  </h:Rltd>
                </h:AppHdr>
                <Document><InstgAgt><FinInstnId><BICFI>KEEP3XXXXXX</BICFI></FinInstnId></InstgAgt></Document>
                """;
        String out = EnvelopeAddresses.apply(raw, "BOSHCNSHXXX", "FAUMDEFF");
        assertTrue(out.contains("<h:BICFI>BOSHCNSHXXX</h:BICFI>"));
        assertTrue(Pattern.compile("<h:BICFI>\\s+FAUMDEFFXXX\\s+</h:BICFI>").matcher(out).find(), out);
        assertTrue(out.contains("<h:BICFI>KEEP1XXXXXX</h:BICFI>"));
        assertTrue(out.contains("<h:BICFI>KEEP2XXXXXX</h:BICFI>"));
        assertTrue(out.contains("<BICFI>KEEP3XXXXXX</BICFI>"));
        assertTrue(out.contains("NOPEXXXXXXX"));
        assertTrue(out.startsWith("<?xml version=\"1.0\"?>"));

        String any = "<AppHdr><Fr><OrgId><AnyBIC>OLD1XXXXXXX</AnyBIC></OrgId></Fr><To><OrgId><BIC>OLD2XXXX</BIC></OrgId></To></AppHdr>";
        String anyOut = EnvelopeAddresses.apply(any, "BOSHCNSH", "FAUMDEFFXXX");
        assertEquals("<AppHdr><Fr><OrgId><AnyBIC>BOSHCNSHXXX</AnyBIC></OrgId></Fr><To><OrgId><BIC>FAUMDEFFXXX</BIC></OrgId></To></AppHdr>", anyOut);

        String broken = "<AppHdr><Fr><FIId><FinInstnId><BICFI>???</BICFI><AnyBIC>OLD1XXXXXXX</AnyBIC></FinInstnId></FIId></Fr></AppHdr>";
        assertSame(broken, EnvelopeAddresses.apply(broken, "BOSHCNSHXXX", null));
        String noHdr = "<Document><BICFI>KEEP3XXXXXX</BICFI></Document>";
        assertSame(noHdr, EnvelopeAddresses.apply(noHdr, "BOSHCNSHXXX", "FAUMDEFFXXX"));
    }

    private static int count(String s, String token) {
        int n = 0, i = 0;
        while ((i = s.indexOf(token, i)) >= 0) { n++; i += token.length(); }
        return n;
    }
}
