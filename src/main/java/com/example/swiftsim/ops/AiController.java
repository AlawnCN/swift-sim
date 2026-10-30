package com.example.swiftsim.ops;

import com.example.swiftsim.config.ConfigService;
import com.example.swiftsim.config.Participant;
import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.gateway.SessionService;
import com.example.swiftsim.parse.MxHandler;
import com.example.swiftsim.review.ReviewItem;
import com.example.swiftsim.review.ReviewModeService;
import com.example.swiftsim.review.ReviewService;
import com.example.swiftsim.scenario.ScenarioEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * AI / automation API (port 8082, /ai/v1, Bearer key - see {@link PortGuardFilter}). Compact, stable JSON for agents that verify
 * business flows and message accuracy. Read scope: everything except trigger / respond. MCP endpoint: {@link McpController}.
 */
@RestController
@RequestMapping("/ai/v1")
@RequiredArgsConstructor
public class AiController {
    public static final String VERSION = "1.0";
    private final OpsService ops;
    private final OpsProps props;
    private final ConfigService cfg;
    private final SessionService sessions;
    private final ReviewService review;
    private final ReviewModeService reviewMode;
    private final ScenarioEngine engine;
    private final AuditService audit;
    private final ObjectMapper json;

    @GetMapping({"", "/"})
    public Map<String, Object> index(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", "swift-sim AI API");
        m.put("version", VERSION);
        m.put("key", whoami(req));
        m.put("openapi", "/ai/v1/openapi.json");
        m.put("mcp", "/ai/v1/mcp (JSON-RPC 2.0 over HTTP POST: initialize, tools/list, tools/call)");
        m.put("endpoints", List.of("GET /messages?sinceId=&limit=&ref=&type=&bank=&direction=&status=&business=", "GET /messages/{id}?includeRaw=true",
                "GET /business?family=UNDERTAKING|LC|COLL&status=&party=", "GET /business/{ref}", "GET /business/{ref}/reconcile",
                "POST /validate {raw, from?, to?, mtType?}", "GET /scenarios", "POST /scenarios/{id}/trigger?format=&ref=&from=&to= (operator)",
                "GET /review-queue?status=", "GET /review-queue/{id}", "POST /review-queue/{id}/preview", "POST /review-queue/{id}/respond (operator)",
                "GET /reference/rule-codes", "GET /reference/message-types", "GET /reference/state-machines", "GET /reference/participants",
                "GET /sessions", "GET /pending-responses"));
        m.put("notes", "Times are ISO-8601 UTC. Amounts are decimal strings/numbers in the record currency. Findings: severity ERROR|WARN|INFO, code, message, evidence=[messageIds].");
        m.put("notes_zh", "时间为 UTC ISO-8601；金额为记录币种的小数；findings 含严重级别、代码、说明及证据报文 id。");
        return m;
    }

    @GetMapping("/whoami")
    public Map<String, Object> whoami(HttpServletRequest req) {
        SecretsService.ApiKey k = (SecretsService.ApiKey) req.getAttribute(PortGuardFilter.AI_KEY_ATTR);
        return k == null ? Map.of() : Map.of("name", k.name(), "scope", k.scope());
    }

    @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String openapi() throws IOException {
        return new String(new ClassPathResource("ai/openapi.json").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------ messages

    @GetMapping("/messages")
    public Map<String, Object> messages(@RequestParam Map<String, String> p) {
        return listMessages(p);
    }

    public Map<String, Object> listMessages(Map<String, String> p) {
        int limit = Math.min(Integer.parseInt(p.getOrDefault("limit", "100")), 500);
        Long since = lng(p.get("sinceId"));
        boolean raw = "true".equals(p.get("includeRaw"));
        List<MessageRecord> l = ops.search(new OpsService.MessageQuery(p.get("bank"), p.get("from"), p.get("to"), p.get("direction"), p.get("type"), p.get("format"),
                p.get("ref"), p.get("status"), p.get("business"), since, lng(p.get("beforeId")),
                OpsService.parseDate(p.get("dateFrom"), false), OpsService.parseDate(p.get("dateTo"), true), "true".equals(p.get("includeSys")), limit + 1));
        boolean more = l.size() > limit;
        if (more) l = l.subList(0, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (MessageRecord m : l) {
            Map<String, Object> s = ops.summary(m);
            if (raw) s.put("raw", m.getRaw());
            items.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("order", since != null ? "ascending (oldest first, cursor mode)" : "descending (newest first)");
        long maxId = l.stream().mapToLong(MessageRecord::getId).max().orElse(since == null ? 0 : since);
        out.put("cursor", since != null ? maxId : l.stream().mapToLong(MessageRecord::getId).max().orElse(0));
        out.put("hasMore", more);
        out.put("hint", since != null ? (more ? "call again with sinceId=" + maxId : "up to date; poll again later with sinceId=" + maxId)
                : "for incremental polling use sinceId=<cursor>");
        return out;
    }

    @GetMapping("/messages/{id}")
    public ResponseEntity<?> message(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean includeRaw) {
        return ops.analyse(id, includeRaw).<ResponseEntity<?>>map(ResponseEntity::ok).orElse(notFound("message " + id));
    }

    // ------------------------------------------------------------------------------------------------ business

    @GetMapping("/business")
    public Map<String, Object> business(@RequestParam(required = false) String family, @RequestParam(required = false) String status, @RequestParam(required = false) String party) {
        List<Map<String, Object>> l = ops.businessList(family, status, party);
        return Map.of("count", l.size(), "items", l);
    }

    @GetMapping("/business/{ref}")
    public ResponseEntity<?> businessDetail(@PathVariable String ref, @RequestParam(defaultValue = "false") boolean includeRaw) {
        return ops.business(ref, includeRaw).<ResponseEntity<?>>map(ResponseEntity::ok).orElse(notFound("business reference " + ref));
    }

    @GetMapping("/business/{ref}/reconcile")
    public ResponseEntity<?> reconcile(@PathVariable String ref) {
        return ops.reconcile(ref).<ResponseEntity<?>>map(ResponseEntity::ok).orElse(notFound("business reference " + ref));
    }

    // ------------------------------------------------------------------------------------------------ validate

    @PostMapping(value = "/validate", consumes = MediaType.ALL_VALUE)
    public Map<String, Object> validate(@RequestBody String body, @RequestParam Map<String, String> p, HttpServletRequest req) throws IOException {
        Map<String, String> b = new HashMap<>(p);
        String ct = req.getContentType() == null ? "" : req.getContentType();
        if (ct.contains("json")) b.putAll(json.readValue(body, new TypeReference<Map<String, String>>() { }));
        else b.put("raw", body);
        return ops.validate(b.get("raw"), b.get("from"), b.get("to"), b.get("mtType"));
    }

    // ------------------------------------------------------------------------------------------------ scenarios

    @GetMapping("/scenarios")
    public Map<String, Object> scenarios() {
        return Map.of("active", cfg.activeMap(), "scenarios", ops.scenarios());
    }

    @PostMapping("/scenarios/{id}/trigger")
    public Map<String, Object> trigger(@PathVariable String id, @RequestParam(defaultValue = "MT") String format, @RequestParam(required = false) String ref,
                                       @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                       @RequestBody(required = false) Map<String, String> vars) {
        return doTrigger(id, format, ref, from, to, vars);
    }

    public Map<String, Object> doTrigger(String id, String format, String ref, String from, String to, Map<String, String> vars) {
        Map<String, String> v = vars == null ? new HashMap<>() : new HashMap<>(vars);
        if (ref != null && !ref.isBlank()) v.put("ref", ref.trim());
        cfg.scenario(id).orElseThrow(() -> new NoSuchElementException("unknown scenario " + id));
        return Map.of("scenario", id, "format", format == null ? "MT" : format, "scheduled", engine.trigger(id, format == null ? "MT" : format, v, blank(from), blank(to)));
    }

    // ------------------------------------------------------------------------------------------------ review queue

    @GetMapping("/review-queue")
    public Map<String, Object> queue(@RequestParam(defaultValue = "PENDING_REVIEW") String status, @RequestParam(required = false) String participant) {
        List<ReviewItem> l = review.list(status, participant);
        return Map.of("count", l.size(), "items", l);
    }

    @GetMapping("/review-queue/{id}")
    public Map<String, Object> reviewItem(@PathVariable Long id) {
        return review.detail(id);
    }

    @PostMapping("/review-queue/{id}/preview")
    public Map<String, Object> preview(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return review.preview(id, body);
    }

    @PostMapping("/review-queue/{id}/respond")
    public Map<String, Object> respond(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return review.respond(id, body);
    }

    // ------------------------------------------------------------------------------------------------ reference / state

    @GetMapping("/reference/rule-codes")
    public Map<String, Object> ruleCodes() {
        return ops.resource("ai/rule-codes.json");
    }

    @GetMapping("/reference/state-machines")
    public Map<String, Object> stateMachines() {
        return ops.stateMachines();
    }

    @GetMapping("/reference/message-types")
    @SuppressWarnings("unchecked")
    public Map<String, Object> messageTypes() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> prods = (Map<String, Object>) ops.stateMachines().get("products");
        prods.forEach((k, v) -> m.put(k, ((Map<String, Object>) v).get("messages")));
        m.put("MX_SUPPORTED", MxHandler.SUPPORTED);
        m.put("GENERAL", List.of("MT103", "MT202", "MT199", "MT299", "MT499", "MT759", "MT799", "MT999"));
        m.put("note", "MT is validated with Prowide Core SRU2025 plus simulator network rules (SIM-*); MX against the XSD of the listed message definitions.");
        return m;
    }

    @GetMapping("/reference/participants")
    public List<Map<String, Object>> participants() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Participant p : cfg.getConfig().getParticipants()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("bic", p.bic11());
            m.put("role", p.getRole());
            m.put("sut", p.isSut());
            m.put("reviewMode", reviewMode.isReviewMode(p));
            l.add(m);
        }
        return l;
    }

    @GetMapping("/sessions")
    public Object sessions() {
        return sessions.all();
    }

    @GetMapping("/pending-responses")
    public Object pending() {
        return engine.listPending(false);
    }

    private static ResponseEntity<?> notFound(String what) {
        return ResponseEntity.status(404).body(Map.of("error", "not found: " + what, "error_zh", "未找到：" + what));
    }

    static Long lng(String s) {
        return s == null || s.isBlank() ? null : Long.valueOf(s);
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
