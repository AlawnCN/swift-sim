package com.example.swiftsim.ops;

import com.example.swiftsim.files.FilePublicationRepository;
import com.example.swiftsim.files.FileTransportService;
import com.example.swiftsim.gateway.GatewayService;
import com.example.swiftsim.gateway.SubmitOptions;
import com.example.swiftsim.gateway.SubmitResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Served admin console, not the envelope helper alone. SUT is FAUMKENAXXX and CONFIRMING_BANK is BARCKENXXXX.
 * File assertions use a private kenex root: incoming is simulator output, outgoing is input, data/outbox is the
 * simulator's own copy, and temp/incoming is never created.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminComposeRuntimeTest {
    private static final Path HOME = tempHome();
    private static final int MAIN = free();
    private static final int ADMIN = free();
    private static final int AI = free();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static int seq = 1;

    @Autowired GatewayService gateway;
    @Autowired FileTransportService transports;
    @Autowired FilePublicationRepository publications;

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
        Matcher m = Pattern.compile("SIMADMIN=([^;]+)").matcher(res.headers().firstValue("set-cookie").orElse(""));
        assertTrue(m.find());
        cookie = "SIMADMIN=" + m.group(1);
    }

    @Test
    void servedPageRewritesTheLoadedSampleAndFollowsALaterSelection() throws Exception {
        HttpResponse<String> anon = HTTP.send(HttpRequest.newBuilder(URI.create(admin("/admin/app.js"))).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, anon.statusCode(), anon.body());
        assertTrue(anon.headers().firstValue("location").orElse("").endsWith("/admin/login.html"));

        HttpResponse<String> js = HTTP.send(HttpRequest.newBuilder(URI.create(admin("/admin/app.js"))).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, js.statusCode());
        assertEquals("no-cache", js.headers().firstValue("cache-control").orElse(""), js.headers().toString());
        assertTrue(js.body().contains("admin-ui:ui-3"), "served app.js is not this console build");
        assertTrue(js.body().contains("function detectMtType"));
        assertTrue(js.body().contains("async function applyComposeEnvelope"));
        assertTrue(js.body().contains("交给支付应用"));
        HttpResponse<String> page = HTTP.send(HttpRequest.newBuilder(URI.create(admin("/admin/"))).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertEquals("no-cache", page.headers().firstValue("cache-control").orElse(""));
        assertTrue(page.body().contains("<script src=\"app.js\"></script>"));

        String password;
        try (var in = Files.newBufferedReader(HOME.resolve("secrets/admin-console.properties"))) {
            Properties p = new Properties();
            p.load(in);
            password = p.getProperty("password");
        }
        try (Chrome chrome = Chrome.launch()) {
            chrome.drive(password);
        }
    }

    @Test
    void bothAutoStaysHttpAndExplicitFileWritesOnlyTheSutCopy() throws Exception {
        Path root = kenex("both-explicit");
        transport("BOTH", "", root);
        String ref = ref();
        JsonNode auto = send(bare(ref), "CONFIRMING_BANK", "SUT_BANK", "499", "", 202);
        assertEquals("HTTP", auto.path("file").path("transport").asText());
        assertEquals("HTTP", auto.path("file").path("reason").asText());
        assertFalse(auto.path("file").path("publishSubmittedMessage").asBoolean());
        JsonNode delivered = awaitDelivered(auto.path("id").asLong());
        assertEquals("HTTP", delivered.path("outboundTransport").asText());
        assertTrue(publications.findByMessageId(auto.path("id").asLong()).isEmpty());
        assertTrue(regularFiles(root.resolve("incoming/mt")).isEmpty());
        assertTrue(regularFiles(HOME.resolve("data/outbox/FAUMKENAXXX")).stream().anyMatch(p -> read(p).contains(ref)));
        assertFalse(Files.exists(root.resolve("temp/incoming")));

        String fileRef = ref();
        int before = regularFiles(root.resolve("incoming/mt")).size();
        JsonNode filed = send(bare(fileRef), "CONFIRMING_BANK", "SUT_BANK", "499", "FILE", 202);
        assertEquals("TO_SUT", filed.path("file").path("reason").asText());
        assertTrue(filed.path("file").path("publishSubmittedMessage").asBoolean());
        assertTrue(filed.path("file").path("incomingMt").asText().endsWith("incoming/mt"));
        awaitDelivered(filed.path("id").asLong());
        List<Path> written = awaitFiles(root.resolve("incoming/mt"), before);
        assertEquals(1, written.size() - before);
        String body = read(written.get(written.size() - 1));
        assertTrue(body.contains(fileRef), body);
        assertTrue(body.contains("FAUMKENA"));
        assertEquals(1, regularFiles(root.resolve("incoming/mt")).stream().filter(p -> read(p).contains(fileRef)).count());
        assertTrue(regularFiles(root.resolve("outgoing/mt")).isEmpty());
        assertFalse(Files.exists(root.resolve("temp/incoming")));
    }

    @Test
    void sutToVirtualPublishesTheReplyOnlyWhenTheParentWasFile() throws Exception {
        Path root = kenex("reply");
        transport("BOTH", "", root);
        String ref = ref();
        JsonNode parent = send(bare(ref), "SUT_BANK", "CONFIRMING_BANK", "499", "FILE", 202);
        assertEquals("RECEIVER_NOT_SUT", parent.path("file").path("reason").asText());
        assertFalse(parent.path("file").path("publishSubmittedMessage").asBoolean());
        awaitDelivered(parent.path("id").asLong());
        assertTrue(publications.findByMessageId(parent.path("id").asLong()).isEmpty());
        assertEquals("PUBLISHED", publications.findByMessageId(parent.path("ackId").asLong()).orElseThrow().getStatus());
        int beforeReply = regularFiles(root.resolve("incoming/mt")).size();
        assertTrue(beforeReply > 0);

        String replyRef = ref();
        SubmitResult reply = gateway.submit(GatewayService.wrapMt("499", "BARCKENXXXX", "FAUMKENAXXX", ":20:" + replyRef + "\n:79:ANSWER\n", false),
                SubmitOptions.builder().channel("SCENARIO").inReplyTo(parent.path("id").asLong()).build());
        assertEquals("ACK", reply.getStatus(), reply.getReason());
        JsonNode stored = awaitDelivered(reply.getId());
        assertEquals("FILE", stored.path("outboundTransport").asText());
        awaitFiles(root.resolve("incoming/mt"), beforeReply);
        assertTrue(regularFiles(root.resolve("incoming/mt")).stream().anyMatch(p -> read(p).contains(replyRef)));
        assertEquals("PUBLISHED", publications.findByMessageId(reply.getId()).orElseThrow().getStatus());

        JsonNode httpParent = send(bare(ref()), "SUT_BANK", "CONFIRMING_BANK", "499", "", 202);
        assertEquals("HTTP", httpParent.path("file").path("reason").asText());
        awaitDelivered(httpParent.path("id").asLong());
        int files = regularFiles(root.resolve("incoming/mt")).size();
        SubmitResult httpReply = gateway.submit(GatewayService.wrapMt("499", "BARCKENXXXX", "FAUMKENAXXX", ":20:" + ref() + "\n:79:ANSWER\n", false),
                SubmitOptions.builder().channel("SCENARIO").inReplyTo(httpParent.path("id").asLong()).build());
        assertEquals("ACK", httpReply.getStatus(), httpReply.getReason());
        JsonNode httpStored = awaitDelivered(httpReply.getId());
        assertEquals("HTTP", httpStored.path("outboundTransport").asText());
        assertTrue(publications.findByMessageId(httpReply.getId()).isEmpty());
        assertEquals(files, regularFiles(root.resolve("incoming/mt")).size());
        assertFalse(Files.exists(root.resolve("temp/incoming")));
    }

    @Test
    void fileModeDefaultsToFileAndExplicitHttpStaysHttp() throws Exception {
        Path root = kenex("file-mode");
        transport("FILE", "", root);
        JsonNode auto = send(bare(ref()), "CONFIRMING_BANK", "SUT_BANK", "499", "", 202);
        assertEquals("TO_SUT", auto.path("file").path("reason").asText());
        awaitDelivered(auto.path("id").asLong());
        assertEquals(1, awaitFiles(root.resolve("incoming/mt"), 0).size());

        int before = regularFiles(root.resolve("incoming/mt")).size();
        JsonNode http = send(bare(ref()), "CONFIRMING_BANK", "SUT_BANK", "499", "HTTP", 202);
        assertEquals("HTTP", http.path("file").path("reason").asText());
        assertFalse(http.path("file").path("publishSubmittedMessage").asBoolean());
        awaitDelivered(http.path("id").asLong());
        assertTrue(publications.findByMessageId(http.path("id").asLong()).isEmpty());
        assertEquals(before, regularFiles(root.resolve("incoming/mt")).size());
    }

    @Test
    void httpModeIgnoresAnExplicitFileChoice() throws Exception {
        Path root = kenex("http-mode");
        transport("HTTP", "", root);
        JsonNode sent = send(bare(ref()), "CONFIRMING_BANK", "SUT_BANK", "499", "FILE", 202);
        assertEquals("MODE_HTTP_IGNORES_FILE", sent.path("file").path("reason").asText());
        assertEquals("HTTP", sent.path("file").path("transport").asText());
        assertFalse(sent.path("file").path("publishSubmittedMessage").asBoolean());
        awaitDelivered(sent.path("id").asLong());
        assertTrue(publications.findByMessageId(sent.path("id").asLong()).isEmpty());
        assertTrue(regularFiles(root.resolve("incoming/mt")).isEmpty());
    }

    @Test
    void validateAndNakDoNotWriteTheUserMessage() throws Exception {
        Path root = kenex("nak");
        transport("BOTH", "", root);
        int messages = get(main("/api/messages?limit=2000")).size();
        JsonNode v = post("/admin/api/validate", json(bare(ref()), "CONFIRMING_BANK", "SUT_BANK", "499", "FILE"));
        assertNotEquals("WOULD_NAK", v.path("verdict").asText(), v.toString());
        assertEquals(messages, get(main("/api/messages?limit=2000")).size());
        String bad = Files.readString(Path.of("samples/invalid-mt760-missing-fields.fin"));
        JsonNode nak = send(bad, "CONFIRMING_BANK", "SUT_BANK", "760", "FILE", 422);
        assertEquals("NAK", nak.path("status").asText());
        assertEquals("NAK", nak.path("file").path("reason").asText());
        assertFalse(nak.path("file").path("publishSubmittedMessage").asBoolean());
        assertFalse(nak.path("file").path("publishNakNotice").asBoolean());
        assertTrue(publications.findByMessageId(nak.path("id").asLong()).isEmpty());
        Thread.sleep(400);
        assertTrue(regularFiles(root.resolve("incoming/mt")).isEmpty());
        assertFalse(Files.exists(root.resolve("temp/incoming")));
    }

    @Test
    void aRootThatIsNotADirectoryIsReportedOnTheSend() throws Exception {
        Path root = HOME.resolve("kenex").resolve("not-a-directory");
        Files.createDirectories(root.getParent());
        Files.writeString(root, "x");
        transport("BOTH", "", root);
        JsonNode sent = send(bare(ref()), "CONFIRMING_BANK", "SUT_BANK", "499", "FILE", 202);
        assertEquals("LAYOUT", sent.path("file").path("reason").asText(), sent.toString());
        assertFalse(sent.path("file").path("publishSubmittedMessage").asBoolean());
        assertTrue(sent.path("file").path("error").asText().contains("not a real directory"));
        awaitDelivered(sent.path("id").asLong());
        var pub = publications.findByMessageId(sent.path("id").asLong());
        assertTrue(pub.isEmpty() || "ERROR".equals(pub.get().getStatus()), pub.map(p -> p.getStatus() + " " + p.getErrorReason()).orElse("none"));
    }

    @Test
    void savedSettingsAreReloadedOverStartupFlags() throws Exception {
        Path root = kenex("restart");
        JsonNode saved = transport("BOTH", "", root);
        assertEquals("BOTH", saved.path("mode").asText());
        assertEquals("HTTP", saved.path("originate").asText());
        assertEquals("", saved.path("originateConfigured").asText());
        Properties file = new Properties();
        Path props = HOME.resolve("data/file-transport.properties");
        try (var in = Files.newInputStream(props)) { file.load(in); }
        assertEquals("BOTH", file.getProperty("mode"));
        assertEquals("", file.getProperty("originate"));
        assertEquals(root.toString(), file.getProperty("root"));
        file.setProperty("mode", "FILE");
        try (var out = Files.newOutputStream(props)) { file.store(out, "restart probe"); }
        var reload = FileTransportService.class.getDeclaredMethod("loadSavedSettings");
        reload.setAccessible(true);
        reload.invoke(transports);
        JsonNode again = adminGet("/admin/api/transport");
        assertEquals("FILE", again.path("mode").asText());
        assertEquals("FILE", again.path("originate").asText());
        assertEquals("", again.path("originateConfigured").asText());
        assertEquals(root.toString(), again.path("root").asText());
    }

    @Test
    void kickoffFilePublishesAndABlankChoiceStaysHttp() throws Exception {
        Path root = kenex("kickoff");
        transport("BOTH", "", root);
        long since = maxId();
        postMain("/api/scenarios/happy-issuance/trigger?format=MT&transport=FILE");
        JsonNode filed = awaitOutgoingToSut(since, "FILE");
        assertEquals("FILE", filed.path("outboundTransport").asText());
        awaitDelivered(filed.path("id").asLong());
        assertFalse(awaitFiles(root.resolve("incoming/mt"), 0).isEmpty());
        assertEquals("PUBLISHED", publications.findByMessageId(filed.path("id").asLong()).orElseThrow().getStatus());

        long after = maxId();
        int files = regularFiles(root.resolve("incoming/mt")).size();
        postMain("/api/scenarios/happy-issuance/trigger?format=MT");
        JsonNode http = awaitOutgoingToSut(after, "HTTP");
        awaitDelivered(http.path("id").asLong());
        assertEquals("HTTP", http.path("outboundTransport").asText());
        assertTrue(publications.findByMessageId(http.path("id").asLong()).isEmpty());
        assertEquals(files, regularFiles(root.resolve("incoming/mt")).size());
        assertFalse(Files.exists(root.resolve("temp/incoming")));
    }

    private JsonNode awaitOutgoingToSut(long since, String transport) throws Exception {
        JsonNode found = null;
        for (int i = 0; i < 50; i++) {
            for (JsonNode m : get(main("/api/messages?to=SUT_BANK&sinceId=" + since + "&limit=20&includeRaw=false"))) {
                if ("MT760".equals(m.path("msgType").asText()) && transport.equals(m.path("outboundTransport").asText())) found = m;
            }
            if (found != null) return found;
            Thread.sleep(100);
        }
        fail("no MT760 to the SUT with transport " + transport + " after " + since);
        return null;
    }

    private long maxId() throws Exception {
        long max = 0;
        for (JsonNode m : get(main("/api/messages?limit=1&includeRaw=false"))) max = Math.max(max, m.path("id").asLong());
        return max;
    }

    private JsonNode transport(String mode, String originate, Path root) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("mode", mode);
        body.put("originate", originate);
        body.put("root", root.toString());
        JsonNode saved = post("/admin/api/transport", JSON.writeValueAsString(body));
        assertEquals(mode, saved.path("mode").asText(), saved.toString());
        return saved;
    }

    private JsonNode send(String raw, String from, String to, String mt, String transport, int status) throws Exception {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(admin("/admin/api/send")))
                .header("Content-Type", "application/json").header("X-Sim-Admin", "1").header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(json(raw, from, to, mt, transport)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, res.statusCode(), res.body());
        return JSON.readTree(res.body());
    }

    private JsonNode awaitDelivered(long id) throws Exception {
        JsonNode msg = null;
        for (int i = 0; i < 50; i++) {
            msg = get(main("/api/messages/" + id));
            if ("DELIVERED".equals(msg.path("status").asText())) {
                Thread.sleep(200);
                return msg;
            }
            Thread.sleep(100);
        }
        fail("message " + id + " was not delivered: " + msg);
        return msg;
    }

    private JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(admin(path)))
                .header("Content-Type", "application/json").header("X-Sim-Admin", "1").header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), path + " " + res.body());
        return JSON.readTree(res.body());
    }

    private JsonNode postMain(String path) throws Exception {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(main(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), path + " " + res.body());
        return JSON.readTree(res.body());
    }

    private JsonNode adminGet(String path) throws Exception {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(admin(path))).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), path + " " + res.body());
        return JSON.readTree(res.body());
    }

    private static JsonNode get(String url) throws Exception {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), url + " " + res.body());
        return JSON.readTree(res.body());
    }

    private static String json(String raw, String from, String to, String mt, String transport) throws Exception {
        ObjectNode m = JSON.createObjectNode();
        m.put("raw", raw);
        m.put("from", from);
        m.put("to", to);
        m.put("mtType", mt);
        m.put("transport", transport);
        m.put("scenario", "trade-no-reply");
        return JSON.writeValueAsString(m);
    }

    private static String bare(String ref) { return ":20:" + ref + "\n:79:KEEP BODY\n"; }
    private static String ref() { return "F" + (seq++); }
    private static String read(Path p) { try { return Files.readString(p); } catch (Exception e) { return ""; } }

    private static List<Path> regularFiles(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) { return s.filter(Files::isRegularFile).sorted().toList(); }
    }

    private static List<Path> awaitFiles(Path dir, int moreThan) throws Exception {
        List<Path> found = List.of();
        for (int i = 0; i < 50; i++) {
            found = regularFiles(dir);
            if (found.size() > moreThan) return found;
            Thread.sleep(100);
        }
        fail("expected a file in " + dir + " but saw " + found);
        return found;
    }

    private static Path kenex(String name) throws Exception {
        Path p = HOME.resolve("kenex").resolve(name);
        Files.createDirectories(p);
        return p;
    }

    private static String main(String path) { return "http://127.0.0.1:" + MAIN + path; }
    private static String admin(String path) { return "http://127.0.0.1:" + ADMIN + path; }

    private static int free() {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Path tempHome() {
        try {
            Path home = Files.createTempDirectory("swift-sim-runtime-");
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
                    .replace("  - id: SUT_BANK\n    name: Your System Kenya Test Bank (SUT)\n    bic: TSTAKEN0XXX",
                            "  - id: SUT_BANK\n    name: Your System Kenya Test Bank (SUT)\n    bic: FAUMKENAXXX")
                    .replace("  - id: CONFIRMING_BANK\n    name: Virtual Confirming Bank Nairobi\n    bic: TSTGKEN0XXX",
                            "  - id: CONFIRMING_BANK\n    name: Virtual Confirming Bank Nairobi\n    bic: BARCKENXXXX");
            Files.writeString(yml, text);
            return home;
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Headless Chrome against the admin origin. Confirms the script that the page actually executes. */
    private static final class Chrome implements AutoCloseable {
        private final Process process;
        private final int port;
        private final Path profile;

        private Chrome(Process process, int port, Path profile) {
            this.process = process;
            this.port = port;
            this.profile = profile;
        }

        static Chrome launch() throws Exception {
            Path bin = Path.of("/usr/bin/google-chrome");
            if (!Files.isExecutable(bin)) bin = Path.of("/usr/bin/chromium");
            assertTrue(Files.isExecutable(bin), "chrome is required to exercise the served admin page");
            int port = free();
            Path profile = Files.createTempDirectory("swift-sim-chrome-");
            Process process = new ProcessBuilder(bin.toString(), "--headless=new", "--remote-debugging-port=" + port,
                    "--user-data-dir=" + profile, "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage", "--no-first-run", "about:blank")
                    .redirectErrorStream(true).redirectOutput(profile.resolve("chrome.log").toFile()).start();
            for (int i = 0; i < 50; i++) {
                try {
                    HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/version")).GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 200) return new Chrome(process, port, profile);
                } catch (Exception ignored) {
                    Thread.sleep(100);
                }
            }
            process.destroyForcibly();
            fail("chrome did not open a debugging port; " + Files.readString(profile.resolve("chrome.log")));
            return null;
        }

        void drive(String password) throws Exception {
            JsonNode ver = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/version")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            Cdp cdp = new Cdp(URI.create(ver.path("webSocketDebuggerUrl").asText()));
            try {
                String target = cdp.call("Target.createTarget", Map.of("url", "about:blank"), false).path("targetId").asText();
                cdp.sessionId = cdp.call("Target.attachToTarget", Map.of("targetId", target, "flatten", true), false).path("sessionId").asText();
                cdp.call("Page.enable", Map.of(), true);
                cdp.call("Runtime.enable", Map.of(), true);
                int mark = cdp.mark();
                cdp.call("Page.navigate", Map.of("url", admin("/admin/login.html")), true);
                cdp.await("Page.loadEventFired", mark);
                int submitted = cdp.mark();
                cdp.eval("document.getElementById('u').value='admin';document.getElementById('p').value=" + JSON.writeValueAsString(password) + ";document.getElementById('f').requestSubmit();");
                cdp.await("Page.loadEventFired", submitted);
                cdp.eval("location.hash='#compose'");
                boolean ready = false;
                for (int i = 0; i < 50; i++) {
                    ready = cdp.eval("!!document.querySelector('#c_s') && document.querySelector('#c_from').options.length > 2").asBoolean();
                    if (ready) break;
                    Thread.sleep(100);
                }
                assertTrue(ready, "compose controls did not render");
                String src = cdp.eval("[...document.scripts].map(s => s.src).join(',')").asText();
                assertTrue(src.contains("/admin/app.js"), src);
                cdp.eval("document.querySelector('#c_from').value='CONFIRMING_BANK';document.querySelector('#c_from').dispatchEvent(new Event('change'));"
                        + "document.querySelector('#c_to').value='SUT_BANK';document.querySelector('#c_to').dispatchEvent(new Event('change'));"
                        + "document.querySelector('#c_s').value='mt707-lc-cancellation.fin';document.querySelector('button[onclick=\"loadSample()\"]').click();");
                JsonNode loaded = null;
                for (int i = 0; i < 50; i++) {
                    loaded = cdp.eval("({mt:document.querySelector('#c_mt').value, head:document.querySelector('#c_raw').value.slice(0,80), ui:document.body.innerText.includes('界面 ui-3'), label:document.body.innerText.includes('交给支付应用')})");
                    if (loaded.path("head").asText().startsWith("{1:F01BARCKENX")) break;
                    Thread.sleep(100);
                }
                assertEquals("707", loaded.path("mt").asText(), loaded.toString());
                assertTrue(loaded.path("ui").asBoolean(), loaded.toString());
                assertTrue(loaded.path("label").asBoolean(), loaded.toString());
                assertTrue(loaded.path("head").asText().startsWith("{1:F01BARCKENXAXXX0000000000}{2:I707FAUMKENAXXXXN}"), loaded.toString());
                String body = cdp.eval("document.querySelector('#c_raw').value").asText();
                assertTrue(body.contains(":52A:TSTAKEN0XXX"), body);
                assertFalse(body.substring(0, body.indexOf("{4:")).contains("TSTAKEN0AXXX"));
                cdp.eval("document.querySelector('#c_to').value='ADVISING_BANK';document.querySelector('#c_to').dispatchEvent(new Event('change'));");
                String changed = "";
                for (int i = 0; i < 40; i++) {
                    changed = cdp.eval("document.querySelector('#c_raw').value.slice(0,70)").asText();
                    if (changed.contains("TSTCKEN0XXXX")) break;
                    Thread.sleep(100);
                }
                assertTrue(changed.startsWith("{1:F01BARCKENXAXXX0000000000}{2:I707TSTCKEN0XXXXN}"), changed);
                assertEquals("707", cdp.eval("document.querySelector('#c_mt').value").asText());
                assertTrue(cdp.eval("document.querySelector('#c_raw').value.includes(':52A:TSTAKEN0XXX')").asBoolean());
                cdp.eval("document.querySelector('#c_from').value='SUT_BANK';document.querySelector('#c_from').dispatchEvent(new Event('change'));");
                String swapped = "";
                for (int i = 0; i < 40; i++) {
                    swapped = cdp.eval("document.querySelector('#c_raw').value.slice(0,70)").asText();
                    if (swapped.contains("FAUMKENAAXXX")) break;
                    Thread.sleep(100);
                }
                assertTrue(swapped.startsWith("{1:F01FAUMKENAAXXX0000000000}{2:I707TSTCKEN0XXXXN}"), swapped);
                assertTrue(cdp.exceptions.isEmpty(), cdp.exceptions.toString());
                assertTrue(cdp.console.stream().noneMatch(l -> l.startsWith("error")), cdp.console.toString());
            } finally {
                cdp.close();
            }
        }

        public void close() {
            process.destroyForcibly();
            try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static final class Cdp implements WebSocket.Listener, AutoCloseable {
        private final WebSocket ws;
        private final StringBuilder buf = new StringBuilder();
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final List<JsonNode> events = new ArrayList<>();
        private final AtomicInteger ids = new AtomicInteger();
        final List<String> exceptions = new ArrayList<>();
        final List<String> console = new ArrayList<>();
        String sessionId;

        Cdp(URI uri) {
            ws = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, this).join();
        }

        public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            webSocket.request(1);
            if (!last) return null;
            String raw = buf.toString();
            buf.setLength(0);
            try {
                JsonNode msg = JSON.readTree(raw);
                if (msg.has("id")) {
                    CompletableFuture<JsonNode> f = pending.remove(msg.get("id").asInt());
                    if (f != null) f.complete(msg);
                } else if (msg.has("method")) {
                    if ("Runtime.exceptionThrown".equals(msg.path("method").asText()))
                        exceptions.add(msg.path("params").path("exceptionDetails").path("exception").path("description").asText(msg.toString()));
                    if ("Runtime.consoleAPICalled".equals(msg.path("method").asText()))
                        console.add(msg.path("params").path("type").asText());
                    synchronized (events) { events.add(msg); events.notifyAll(); }
                }
            } catch (Exception e) {
                exceptions.add(e.toString());
            }
            return null;
        }

        JsonNode call(String method, Map<String, ?> params, boolean session) throws Exception {
            int id = ids.incrementAndGet();
            ObjectNode n = JSON.createObjectNode();
            n.put("id", id);
            n.put("method", method);
            n.set("params", JSON.valueToTree(params));
            if (session && sessionId != null) n.put("sessionId", sessionId);
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            pending.put(id, f);
            ws.sendText(JSON.writeValueAsString(n), true).join();
            JsonNode msg = f.get(20, TimeUnit.SECONDS);
            assertTrue(msg.path("error").isMissingNode(), msg.toString());
            return msg.path("result");
        }

        JsonNode eval(String expression) throws Exception {
            JsonNode result = call("Runtime.evaluate", Map.of("expression", expression, "returnByValue", true), true).path("result");
            if (!result.path("exceptionDetails").isMissingNode() || "error".equals(result.path("subtype").asText())) fail(result.toString());
            return result.path("value");
        }

        int mark() { synchronized (events) { return events.size(); } }

        void await(String method, int from) throws InterruptedException {
            long end = System.currentTimeMillis() + 20000;
            synchronized (events) {
                while (true) {
                    for (int i = from; i < events.size(); i++) {
                        JsonNode e = events.get(i);
                        if (!method.equals(e.path("method").asText())) continue;
                        if (sessionId != null && e.has("sessionId") && !sessionId.equals(e.path("sessionId").asText())) continue;
                        return;
                    }
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) fail("timeout waiting for " + method);
                    events.wait(left);
                }
            }
        }

        public void close() { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }
}
