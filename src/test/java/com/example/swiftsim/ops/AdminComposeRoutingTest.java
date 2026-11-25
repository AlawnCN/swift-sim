package com.example.swiftsim.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Admin compose on an isolated port and data dir. SUT is BIC8 FAUMDEFF, the applicant bank is BIC11 BOSHCNSHXXX.
 * The public gateway still reports SIM-H12 when ?from/?to disagree with an AppHdr.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminComposeRoutingTest {
    private static final Path HOME = tempHome();
    private static final int MAIN = free();
    private static final int ADMIN = free();
    private static final int AI = free();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private String cookie;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("server.port", () -> MAIN);
        r.add("server.address", () -> "127.0.0.1");
        r.add("sim.admin.port", () -> ADMIN);
        r.add("sim.admin.address", () -> "127.0.0.1");
        r.add("sim.ai.port", () -> AI);
        r.add("sim.ai.address", () -> "127.0.0.1");
        r.add("sim.data-dir", () -> HOME.resolve("data").toString());
        r.add("sim.config-dir", () -> HOME.resolve("config").toString());
        r.add("sim.secrets-dir", () -> HOME.resolve("secrets").toString());
        r.add("sim.samples-dir", () -> Path.of("samples").toAbsolutePath().toString());
        r.add("spring.datasource.url", () -> "jdbc:h2:file:" + HOME.resolve("data/db/swiftsim") + ";AUTO_SERVER=FALSE");
        r.add("logging.level.root", () -> "WARN");
    }

    @BeforeAll
    void login() throws Exception {
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(HOME.resolve("secrets/admin-console.properties"))) { p.load(in); }
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(admin("/admin/api/login")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + p.getProperty("username") + "\",\"password\":\"" + p.getProperty("password") + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        String set = res.headers().firstValue("set-cookie").orElse("");
        Matcher m = Pattern.compile("SIMADMIN=([^;]+)").matcher(set);
        assertTrue(m.find(), set);
        cookie = "SIMADMIN=" + m.group(1);
        JsonNode parts = get(main("/api/participants"));
        assertEquals("FAUMDEFF", bic(parts, "SUT_BANK"));
        assertEquals("BOSHCNSHXXX", bic(parts, "APPLICANT_BANK"));
    }

    @Test
    void loadThenChangeSelectionUpdatesTheEnvelopeAndTheMtType() throws Exception {
        String sample = Files.readString(Path.of("samples/mt456-coll-dishonour.fin"));
        JsonNode loaded = post("/admin/api/envelope", json(sample, "APPLICANT_BANK", "SUT_BANK", null));
        assertEquals("456", loaded.path("mtType").asText());
        String raw = loaded.path("raw").asText();
        assertTrue(raw.startsWith("{1:F01BOSHCNSHAXXX0000000000}{2:I456FAUMDEFFXXXXN}"), raw.substring(0, Math.min(80, raw.length())));
        assertTrue(raw.contains(":20:KECODSH0101"));
        assertTrue(raw.contains("{108:SAMPLE456001}"));
        assertFalse(raw.contains("TSTAKEN0"));
        assertFalse(raw.contains("TSTJIN20"));
        String edited = raw.replace(":77A:ITEM RETURNED UNPAID", ":77A:ITEM RETURNED UNPAID\nMANUAL EDIT");
        JsonNode changed = post("/admin/api/envelope", json(edited, "ISSUING_BANK", "SUT_BANK", null));
        String next = changed.path("raw").asText();
        assertTrue(next.startsWith("{1:F01TSTBGB20AXXX0000000000}{2:I456FAUMDEFFXXXXN}"), next.substring(0, 70));
        assertTrue(next.contains("MANUAL EDIT"));
        assertTrue(next.contains(":20:KECODSH0101"));
        assertFalse(next.contains("BOSHCNSH"));
    }

    @Test
    void validateOnlyUsesTheSelectedPartiesAndDoesNotSend() throws Exception {
        String before = get(main("/api/gateway/sessions")).toString();
        int messages = get(main("/api/messages?limit=2000")).size();
        String sample = Files.readString(Path.of("samples/mt456-coll-dishonour.fin"));
        JsonNode v = post("/admin/api/validate", json(sample, "APPLICANT_BANK", "SUT_BANK", "760"));
        assertEquals("MT", v.path("format").asText());
        assertEquals("MT456", v.path("msgType").asText());
        assertEquals("BOSHCNSHXXX", v.path("sender").asText());
        assertEquals("FAUMDEFFXXX", v.path("receiver").asText());
        assertEquals("OUT", v.path("direction").asText());
        assertNotEquals("WOULD_NAK", v.path("verdict").asText(), v.path("findings").toString());
        assertTrue(v.path("raw").asText().contains("{1:F01BOSHCNSHAXXX0000000000}{2:I456FAUMDEFFXXXXN}"));
        assertTrue(v.path("raw").asText().contains(":77A:ITEM RETURNED UNPAID"));
        assertEquals(before, get(main("/api/gateway/sessions")).toString());
        assertEquals(messages, get(main("/api/messages?limit=2000")).size());

        JsonNode back = post("/admin/api/validate", json(sample, "SUT_BANK", "APPLICANT_BANK", ""));
        assertEquals("IN", back.path("direction").asText());
        assertEquals("FAUMDEFFXXX", back.path("sender").asText());
        assertEquals("BOSHCNSHXXX", back.path("receiver").asText());
        assertTrue(back.path("raw").asText().contains("{1:F01FAUMDEFFAXXX0000000000}{2:I456BOSHCNSHXXXXN}"));

        JsonNode internal = post("/admin/api/validate", json(sample, "APPLICANT_BANK", "ISSUING_BANK", ""));
        assertEquals("INTERNAL", internal.path("direction").asText());
        assertEquals("TSTBGB20XXX", internal.path("receiver").asText());
    }

    @Test
    void sendRoutesTheRewrittenMtAndTheDeliveredOutput() throws Exception {
        String sample = Files.readString(Path.of("samples/mt760-issue-to-advising.fin"));
        HttpResponse<String> res = send(json(sample, "APPLICANT_BANK", "SUT_BANK", "760"));
        assertEquals(202, res.statusCode(), res.body());
        JsonNode ack = JSON.readTree(res.body());
        assertEquals("ACK", ack.path("status").asText(), res.body());
        assertEquals("BOSHCNSHXXX", ack.path("sender").asText());
        assertEquals("FAUMDEFFXXX", ack.path("receiver").asText());
        assertEquals("MT760", ack.path("msgType").asText());
        JsonNode stored = awaitDelivered(ack.path("id").asLong());
        assertEquals("OUT", stored.path("direction").asText());
        assertEquals("ADMIN", stored.path("channel").asText());
        String raw = stored.path("raw").asText();
        assertTrue(raw.startsWith("{1:F01BOSHCNSHAXXX0000000000}{2:I760FAUMDEFFXXXXN}"), raw.substring(0, 60));
        assertTrue(raw.contains(":52A:TSTAKEN0XXX"));
        assertTrue(raw.contains(":56A:TSTCKEN0XXX"));
        assertTrue(raw.contains(":20:KEGTEE2026000001"));
        assertFalse(raw.contains("TSTAKEN0AXXX"));
        assertFalse(raw.contains("TSTCKEN0XXXX"));
        String delivered = stored.path("deliveredRaw").asText();
        assertTrue(delivered.contains("{1:F01FAUMDEFFAXXX"), delivered.substring(0, 80));
        assertTrue(delivered.contains("{2:O760"));
        assertTrue(delivered.contains("BOSHCNSHAXXX"));
        assertTrue(delivered.contains(":52A:TSTAKEN0XXX"));
        assertTrue(delivered.contains(":56A:TSTCKEN0XXX"));

        HttpResponse<String> inbound = send(json(sample, "SUT_BANK", "APPLICANT_BANK", ""));
        assertEquals(202, inbound.statusCode(), inbound.body());
        JsonNode in = JSON.readTree(inbound.body());
        JsonNode inStored = awaitDelivered(in.path("id").asLong());
        assertEquals("IN", inStored.path("direction").asText());
        assertEquals("FAUMDEFFXXX", inStored.path("senderBic").asText());
        assertEquals("BOSHCNSHXXX", inStored.path("receiverBic").asText());
        assertTrue(inStored.path("deliveredRaw").asText().contains("{1:F01BOSHCNSHAXXX"));
        assertTrue(inStored.path("deliveredRaw").asText().contains("FAUMDEFFAXXX"));
    }

    @Test
    void bareBlock4AndMalformedSamplesStayUsable() throws Exception {
        String bare = ":20:BARE499ROUTE\n:79:KEEP BODY\n";
        HttpResponse<String> res = send(json(bare, "APPLICANT_BANK", "SUT_BANK", "499"));
        assertEquals(202, res.statusCode(), res.body());
        JsonNode ack = JSON.readTree(res.body());
        JsonNode stored = get(main("/api/messages/" + ack.path("id").asLong()));
        String raw = stored.path("raw").asText();
        assertTrue(raw.contains("{1:F01BOSHCNSHAXXX"), raw.substring(0, 40));
        assertTrue(raw.contains("{2:I499FAUMDEFFXXXX"));
        assertTrue(raw.contains(":79:KEEP BODY"));
        assertEquals("OUT", stored.path("direction").asText());

        String bad = Files.readString(Path.of("samples/invalid-mt760-missing-fields.fin"));
        HttpResponse<String> nak = send(json(bad, "APPLICANT_BANK", "SUT_BANK", "760"));
        assertEquals(422, nak.statusCode(), nak.body());
        JsonNode body = JSON.readTree(nak.body());
        assertEquals("NAK", body.path("status").asText());
        assertTrue(body.path("errors").toString().contains("SIM-"));
        JsonNode storedNak = get(main("/api/messages/" + body.path("id").asLong()));
        String nakRaw = storedNak.path("raw").asText();
        assertTrue(nakRaw.contains("{1:F01BOSHCNSHAXXX0000000000}{2:I760FAUMDEFFXXXXN}"));
        assertTrue(nakRaw.contains(":30:261399"));
        assertTrue(nakRaw.contains(":20:/BAD//REF/"));
        assertTrue(nakRaw.contains(":32B:USD50000.00"));
        assertFalse(nakRaw.contains(":40C:"));
        assertFalse(nakRaw.contains(":77U:"));
    }

    @Test
    void mxAppHdrIsRewrittenForAdminAndStillConflictsOnThePublicApi() throws Exception {
        String mx = Files.readString(Path.of("samples/tsrv.001-issuance.xml"));
        JsonNode v = post("/admin/api/validate", json(mx, "APPLICANT_BANK", "SUT_BANK", ""));
        assertEquals("BOSHCNSHXXX", v.path("sender").asText());
        assertEquals("FAUMDEFFXXX", v.path("receiver").asText());
        assertEquals("OUT", v.path("direction").asText());
        assertFalse(v.path("findings").toString().contains("SIM-H12"), v.path("findings").toString());
        String raw = v.path("raw").asText();
        assertTrue(raw.contains("<BICFI>BOSHCNSHXXX</BICFI>"));
        assertTrue(raw.contains("<BICFI>FAUMDEFFXXX</BICFI>"));
        assertTrue(raw.contains("<AnyBIC>TSTAKEN0XXX</AnyBIC>"));
        assertTrue(raw.contains("<AnyBIC>TSTCKEN0XXX</AnyBIC>"));

        String noHdr = mx.replaceAll("(?s)<AppHdr\\b.*?</AppHdr>\\s*", "");
        assertFalse(noHdr.contains("AppHdr"));
        JsonNode open = post("/admin/api/validate", json(noHdr, "SUT_BANK", "APPLICANT_BANK", ""));
        assertEquals("FAUMDEFFXXX", open.path("sender").asText());
        assertEquals("BOSHCNSHXXX", open.path("receiver").asText());
        assertEquals("IN", open.path("direction").asText());
        assertFalse(open.path("raw").asText().contains("AppHdr"));

        HttpResponse<String> pub = HTTP.send(HttpRequest.newBuilder(URI.create(main("/api/messages?from=APPLICANT_BANK&to=SUT_BANK")))
                .header("Content-Type", "text/plain;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(mx))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(422, pub.statusCode(), pub.body());
        JsonNode nak = JSON.readTree(pub.body());
        assertTrue(nak.path("errors").toString().contains("SIM-H12"), pub.body());
        String stored = get(main("/api/messages/" + nak.path("id").asLong())).path("raw").asText();
        assertTrue(stored.contains("<BICFI>TSTAKEN0XXX</BICFI>"));
        assertTrue(stored.contains("<BICFI>TSTCKEN0XXX</BICFI>"));
        assertFalse(stored.contains("BOSHCNSH"));
        assertFalse(stored.contains("FAUMDEFF"));
    }

    private JsonNode awaitDelivered(long id) throws Exception {
        JsonNode msg = null;
        for (int i = 0; i < 40; i++) {
            msg = get(main("/api/messages/" + id));
            if ("DELIVERED".equals(msg.path("status").asText()) && msg.path("deliveredRaw").asText().contains("{2:O")) return msg;
            Thread.sleep(100);
        }
        fail("message " + id + " was not delivered: " + msg);
        return msg;
    }

    private HttpResponse<String> send(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(admin("/admin/api/send")))
                .header("Content-Type", "application/json")
                .header("X-Sim-Admin", "1")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(admin(path)))
                .header("Content-Type", "application/json")
                .header("X-Sim-Admin", "1")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), path + " " + res.body());
        return JSON.readTree(res.body());
    }

    private static JsonNode get(String url) throws Exception {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), url + " " + res.body());
        return JSON.readTree(res.body());
    }

    private static String json(String raw, String from, String to, String mtType) throws Exception {
        var m = JSON.createObjectNode();
        m.put("raw", raw);
        if (from != null) m.put("from", from);
        if (to != null) m.put("to", to);
        if (mtType != null) m.put("mtType", mtType);
        return JSON.writeValueAsString(m);
    }

    private static String bic(JsonNode parts, String id) {
        for (JsonNode p : parts) if (id.equals(p.path("id").asText())) return p.path("bic").asText();
        fail("missing " + id);
        return null;
    }

    private static String main(String path) { return "http://127.0.0.1:" + MAIN + path; }
    private static String admin(String path) { return "http://127.0.0.1:" + ADMIN + path; }

    private static int free() {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Path tempHome() {
        try {
            Path home = Files.createTempDirectory("swift-sim-compose-");
            Path cfg = home.resolve("config");
            Path src = Path.of("config");
            try (var walk = Files.walk(src)) {
                for (Path p : walk.toList()) {
                    Path rel = src.relativize(p);
                    if (rel.startsWith("secrets")) continue;
                    Path to = cfg.resolve(rel.toString());
                    if (Files.isDirectory(p)) Files.createDirectories(to);
                    else Files.copy(p, to);
                }
            }
            Path yml = cfg.resolve("participants.yml");
            String text = Files.readString(yml)
                    .replace("deliveryDelayMs: 800", "deliveryDelayMs: 0")
                    .replace("bic: TSTAKEN0XXX", "bic: FAUMDEFF")
                    .replace("bic: TSTECN20XXX", "bic: BOSHCNSHXXX");
            Files.writeString(yml, text);
            return home;
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
