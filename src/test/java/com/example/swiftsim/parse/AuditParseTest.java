package com.example.swiftsim.parse;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Parser rules from the audit: strict dates, ISO 4217, MT760 conditionals, MX cardinality and pacs structure. */
class AuditParseTest {
    private final MtHandler mt = new MtHandler();
    private final MxHandler mx = new MxHandler();

    @Test
    void february31IsRejected() {
        ParsedMessage pm = mt.parse(fin("760", ":15A:\n:27:1/1\n:22A:ISSU\n:15B:\n:20:REF1\n:30:260231\n:22D:DGAR\n:40C:URDG\n:23B:FIXD\n:31E:271231\n:52A:TSTAKEN0XXX\n:59:BEN\n:32B:USD1,00\n:77U:TERMS"));
        assertTrue(pm.getErrors().stream().anyMatch(e -> e.contains("SIM-F03") && e.contains("260231")), pm.getErrors().toString());
    }

    @Test
    void leapDayInALeapYearIsAccepted() {
        ParsedMessage pm = mt.parse(fin("760", ":15A:\n:27:1/1\n:22A:ISSU\n:15B:\n:20:REF1\n:30:240229\n:22D:DGAR\n:40C:URDG\n:23B:FIXD\n:31E:241231\n:52A:TSTAKEN0XXX\n:59:BEN\n:32B:USD1,00\n:77U:TERMS"));
        assertTrue(pm.getErrors().stream().noneMatch(e -> e.contains("SIM-F03")), pm.getErrors().toString());
    }

    @Test
    void unknownCurrencyAndWrongScaleAreRejected() {
        ParsedMessage zzz = mt.parse(fin("760", ":15A:\n:27:1/1\n:22A:ISSU\n:15B:\n:20:REF1\n:30:261110\n:22D:DGAR\n:40C:URDG\n:23B:FIXD\n:31E:271231\n:52A:TSTAKEN0XXX\n:59:BEN\n:32B:ZZZ1,00\n:77U:TERMS"));
        assertTrue(zzz.getErrors().stream().anyMatch(e -> e.contains("SIM-F07") && e.contains("T40")), zzz.getErrors().toString());
        ParsedMessage jpy = mt.parse(fin("760", ":15A:\n:27:1/1\n:22A:ISSU\n:15B:\n:20:REF1\n:30:261110\n:22D:DGAR\n:40C:URDG\n:23B:FIXD\n:31E:271231\n:52A:TSTAKEN0XXX\n:59:BEN\n:32B:JPY100,\n:77U:TERMS"));
        assertTrue(jpy.getErrors().stream().noneMatch(e -> e.contains("SIM-F07")), jpy.getErrors().toString());
        ParsedMessage jpyBad = mt.parse(fin("760", ":15A:\n:27:1/1\n:22A:ISSU\n:15B:\n:20:REF1\n:30:261110\n:22D:DGAR\n:40C:URDG\n:23B:FIXD\n:31E:271231\n:52A:TSTAKEN0XXX\n:59:BEN\n:32B:JPY100,00\n:77U:TERMS"));
        assertTrue(jpyBad.getErrors().stream().anyMatch(e -> e.contains("SIM-F07") && e.contains("T43")), jpyBad.getErrors().toString());
    }

    @Test
    void standbyRequires49AndIscoRequiresSequenceC() {
        ParsedMessage stby = mt.parse(fin("760", ":15A:\n:27:1/1\n:22A:ISSU\n:15B:\n:20:REF1\n:30:261110\n:22D:STBY\n:40C:ISPR\n:23B:FIXD\n:31E:271231\n:52A:TSTAKEN0XXX\n:59:BEN\n:32B:USD1,00\n:77U:TERMS"));
        assertTrue(stby.getErrors().stream().anyMatch(e -> e.contains("SIM-N63")), stby.getErrors().toString());
        ParsedMessage ok = mt.parse(fin("760", ":15A:\n:27:1/1\n:22A:ISSU\n:15B:\n:20:REF1\n:30:261110\n:22D:STBY\n:40C:ISPR\n:23B:FIXD\n:49:WITHOUT\n:31E:271231\n:52A:TSTAKEN0XXX\n:59:BEN\n:32B:USD1,00\n:77U:TERMS"));
        assertTrue(ok.getErrors().stream().noneMatch(e -> e.startsWith("SIM-N")), ok.getErrors().toString());
        ParsedMessage isco = mt.parse(fin("760", ":15A:\n:27:1/1\n:22A:ISCO\n:15B:\n:20:REF1\n:30:261110\n:22D:DGAR\n:40C:URDG\n:23B:FIXD\n:31E:271231\n:52A:TSTAKEN0XXX\n:59:BEN\n:32B:USD1,00\n:77U:TERMS"));
        assertTrue(isco.getErrors().stream().anyMatch(e -> e.contains("SIM-N61")), isco.getErrors().toString());
    }

    @Test
    void mt456KeepsEveryDishonouredItem() {
        String body = ":20:R1\n:21:R2\n:32B:USD100,00\n:33D:261110USD60,00\n:77A:REASON ONE\n:77D:BILL 1\n:32B:USD50,00\n:33D:261110USD40,00\n:77A:REASON TWO\n:77D:BILL 2";
        ParsedMessage pm = mt.parse(fin("456", body));
        assertTrue(pm.getErrors().isEmpty(), pm.getErrors().toString());
        assertEquals("2", pm.getVars().get("f456Items"));
        assertTrue(pm.getVars().get("f33D").contains("100,00"), pm.getVars().get("f33D"));
        assertTrue(pm.getVars().get("f77D").contains("BILL 1") && pm.getVars().get("f77D").contains("BILL 2"));
    }

    @Test
    void twoDocumentsAreRejectedAndPacsAmountIsTheSettlementAmount() throws Exception {
        String two = "<RequestPayload><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:tsrv.019.001.01\"><UdrtkgStsRpt><UdrtkgStsRptDtls><StsCtgy>GENR</StsCtgy><Sts>ACCP</Sts></UdrtkgStsRptDtls></UdrtkgStsRpt></Document>"
                + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:tsrv.019.001.01\"><UdrtkgStsRpt/></Document></RequestPayload>";
        ParsedMessage bad = mx.parse(two);
        assertTrue(bad.getErrors().stream().anyMatch(e -> e.contains("SIM-X08")), bad.getErrors().toString());
        String zzzz = Files.readString(Path.of("samples/tsrv.019-status-report.xml")).replace("ISSU", "ZZZZ");
        ParsedMessage cat = mx.parse(zzzz);
        assertTrue(cat.getErrors().stream().anyMatch(e -> e.contains("SIM-X07")), cat.getErrors().toString());
        ParsedMessage pacs = mx.parse(Files.readString(Path.of("samples/pacs.009-cov-cover.xml")));
        assertTrue(pacs.valid(), pacs.getErrors().toString());
        assertEquals("COV", pacs.getVars().get("settlementKind"));
        assertEquals("40000.00", pacs.getVars().get("mx_amount"));
        assertEquals("USD", pacs.getVars().get("mx_currency"));
        assertEquals("KELC2026000001", pacs.getTxRef());
        ParsedMessage core = mx.parse(Files.readString(Path.of("samples/pacs.009-core-cover.xml")));
        assertTrue(core.valid(), core.getErrors().toString());
        assertEquals("CORE", core.getVars().get("settlementKind"));
        ParsedMessage p8 = mx.parse(Files.readString(Path.of("samples/pacs.008-customer-payment.xml")));
        assertTrue(p8.valid(), p8.getErrors().toString());
        assertEquals("CUSTOMER", p8.getVars().get("settlementKind"));
    }

    @Test
    void positiveSamplesPassTheSimulatorsOwnValidation() throws Exception {
        try (Stream<Path> files = Files.list(Path.of("samples"))) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return !n.startsWith("invalid") && (n.endsWith(".fin") || n.endsWith(".xml"));
            }).forEach(p -> {
                try {
                    String raw = Files.readString(p);
                    ParsedMessage pm = raw.stripLeading().startsWith("<") ? mx.parse(raw) : mt.parse(raw);
                    assertTrue(pm.valid(), p.getFileName() + " -> " + pm.getErrors());
                } catch (Exception e) {
                    fail(p.getFileName() + " " + e);
                }
            });
        }
    }

    private static String fin(String type, String body) {
        return "{1:F01TSTAKEN0AXXX0000000000}{2:I" + type + "TSTCKEN0XXXXN}{4:\n" + body + "\n-}";
    }
}
