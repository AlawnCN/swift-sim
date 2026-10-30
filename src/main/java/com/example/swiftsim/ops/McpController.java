package com.example.swiftsim.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Minimal MCP server (Streamable HTTP transport, JSON responses only, stateless): POST /ai/v1/mcp with JSON-RPC 2.0
 * (initialize, notifications/initialized, ping, tools/list, tools/call). Same Bearer keys and scopes as the REST API.
 */
@RestController
@RequestMapping("/ai/v1/mcp")
@RequiredArgsConstructor
public class McpController {
    private static final String PROTOCOL = "2025-06-18";
    private final AiController ai;
    private final OpsService ops;
    private final AuditService audit;
    private final ObjectMapper json;

    private record Tool(String name, String description, boolean operator, Map<String, Object> schema) { }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> req = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 3) {
            props.put((String) kv[i], Map.of("type", ((String) kv[i + 1]).replace("!", ""), "description", kv[i + 2]));
            if (((String) kv[i + 1]).endsWith("!")) req.add((String) kv[i]);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        if (!req.isEmpty()) s.put("required", req);
        return s;
    }

    private static final List<Tool> TOOLS = List.of(
            new Tool("list_messages", "Search / poll simulator messages. Use sinceId=<cursor> for incremental polling (ascending). Filters: ref, type (e.g. MT760, tsrv.001), bank (BIC or participant id), direction (IN=from SUT, OUT=to SUT, INTERNAL), status (ACK,NAK,DELIVERED), business (APPLIED,REJECTED,INCONSISTENT or BIZREJECT).", false,
                    obj("sinceId", "integer", "cursor: only messages with id > sinceId (ascending)", "ref", "string", "reference (20/21/business ref, substring)", "type", "string", "message type",
                            "bank", "string", "BIC or participant id (sender or receiver)", "direction", "string", "IN|OUT|INTERNAL|SYS", "status", "string", "ACK|NAK|DELIVERED",
                            "business", "string", "business status filter", "limit", "integer", "max items (<=500, default 50)", "includeRaw", "boolean", "include raw text")),
            new Tool("get_message", "One message with raw text, parsed fields, validation findings (syntax / network / business), linked business reference and replies.", false,
                    obj("id", "integer!", "message id", "includeRaw", "boolean", "default true")),
            new Tool("list_business", "List business records: guarantees/standbys (UNDERTAKING), documentary credits (LC), collections (COLL).", false,
                    obj("family", "string", "UNDERTAKING|LC|COLL (default all)", "status", "string", "status filter", "party", "string", "BIC or participant id")),
            new Tool("get_business", "Full state of a business record by reference: amounts, parties, amendments/demands/presentations, movements, event history, linked messages, expected next steps.", false,
                    obj("ref", "string!", "business reference (undertaking no., LC no., collection ref, or presentation/claim ref)")),
            new Tool("reconcile_business", "Check a business flow end-to-end: NAKs, business rejects, field 21 chaining, balances (outstanding/utilised/paid), dates/expiry, amendment sequence, missing responses, pending items. Returns summary + findings[severity, code, message, evidence].", false,
                    obj("ref", "string!", "business reference")),
            new Tool("validate_message", "Validate an MT or MX message WITHOUT sending it: Prowide syntax + simulator network rules + addressing + business rules against the current ledger (dry run, rolled back). Bare MT block 4 (starting with ':') needs from, to, mtType.", false,
                    obj("raw", "string!", "full MT (FIN) or MX (XML with AppHdr) text, or MT block 4", "from", "string", "sender BIC/participant id (optional)", "to", "string", "receiver BIC/participant id (optional)", "mtType", "string", "e.g. 760 when raw is a bare block 4")),
            new Tool("list_scenarios", "Scenarios (virtual bank behaviours) with group, active flag, reactions and whether they can be triggered (kickoff).", false, obj()),
            new Tool("list_review_queue", "Review queue: inbound messages waiting for a manual/AI decision of a virtual bank in review mode (with ledger verdict and suggested actions). Give id to get one item with its ledger context.", false,
                    obj("id", "integer", "review item id (optional; returns one item with context)", "status", "string", "PENDING_REVIEW (default), RESPONDED, DISMISSED, ALL", "participant", "string", "participant id or BIC")),
            new Tool("preview_review_response", "Render the message(s) a review decision would send, without sending (draft). Same body as respond_review.", false,
                    obj("id", "integer!", "review item id", "action", "string!", "ACK|ACCEPT|REFUSE|PAY|EXTEND|CONFIRM|BUSINESS_REJECT", "reasons", "array", "refusal / reject reasons", "amount", "string", "payment amount", "newExpiry", "string", "YYYY-MM-DD")),
            new Tool("get_reference", "Reference data: rule-codes (SIM-*/BIZ-* code catalogue with meaning), state-machines (statuses, expected next, invariants per product), message-types, participants, sessions (LT session / ISN / OSN), pending-responses.", false,
                    obj("kind", "string!", "rule-codes | state-machines | message-types | participants | sessions | pending-responses")),
            new Tool("respond_review", "OPERATOR SCOPE. Decide a review item: the virtual bank sends the resulting SWIFT message(s). Actions: ACK, ACCEPT, REFUSE, PAY, EXTEND, CONFIRM, BUSINESS_REJECT, SCENARIO (scenario), CUSTOM (raw), DISMISS.", true,
                    obj("id", "integer!", "review item id", "action", "string!", "decision", "reasons", "array", "reasons", "amount", "string", "amount", "newExpiry", "string", "YYYY-MM-DD", "scenario", "string", "for SCENARIO", "raw", "string", "for CUSTOM", "mtType", "string", "for CUSTOM block 4", "note", "string", "operator note")),
            new Tool("trigger_scenario", "OPERATOR SCOPE. Kick off a scenario: a virtual bank sends the first message(s) (e.g. issue a guarantee to your system).", true,
                    obj("id", "string!", "scenario id", "format", "string", "MT (default) or MX", "ref", "string", "use an existing record / reference", "from", "string", "override sender", "to", "string", "override receiver")));

    @GetMapping
    public ResponseEntity<?> get() {
        return ResponseEntity.status(405).header("Allow", "POST").body(Map.of("error", "MCP streamable HTTP: POST JSON-RPC requests to this URL (no SSE stream offered)"));
    }

    @DeleteMapping
    public ResponseEntity<?> delete() {
        return ResponseEntity.status(405).build();
    }

    @PostMapping(consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> post(@RequestBody String body, HttpServletRequest req) {
        JsonNode in;
        try {
            in = json.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.ok(error(null, -32700, "parse error"));
        }
        SecretsService.ApiKey key = (SecretsService.ApiKey) req.getAttribute(PortGuardFilter.AI_KEY_ATTR);
        if (in.isArray()) {
            ArrayNode out = json.createArrayNode();
            for (JsonNode r : in) { ObjectNode x = handle(r, key); if (x != null) out.add(x); }
            return out.isEmpty() ? ResponseEntity.accepted().build() : ResponseEntity.ok(out);
        }
        ObjectNode r = handle(in, key);
        return r == null ? ResponseEntity.accepted().build() : ResponseEntity.ok(r);
    }

    private ObjectNode handle(JsonNode r, SecretsService.ApiKey key) {
        JsonNode id = r.get("id");
        String method = r.path("method").asText("");
        if (id == null || id.isNull()) return null; // notification (e.g. notifications/initialized)
        try {
            return switch (method) {
                case "initialize" -> {
                    ObjectNode res = json.createObjectNode();
                    String pv = r.path("params").path("protocolVersion").asText(PROTOCOL);
                    res.put("protocolVersion", Set.of("2024-11-05", "2025-03-26", "2025-06-18").contains(pv) ? pv : PROTOCOL);
                    res.set("capabilities", json.valueToTree(Map.of("tools", Map.of("listChanged", false))));
                    res.set("serverInfo", json.valueToTree(Map.of("name", "swift-sim", "title", "SWIFT trade finance simulator", "version", AiController.VERSION)));
                    res.put("instructions", "SWIFT MT/MX trade-finance simulator (guarantees, LCs, collections). Use reconcile_business to verify a flow, "
                            + "validate_message to check a draft, list_messages with sinceId to poll. Key scope: " + key.scope()
                            + ". Never purge data or reset sequence numbers. 中文：用 reconcile_business 核对业务，validate_message 校验报文，list_messages+sinceId 增量拉取。");
                    yield ok(id, res);
                }
                case "ping" -> ok(id, json.createObjectNode());
                case "tools/list" -> {
                    ArrayNode tools = json.createArrayNode();
                    for (Tool t : TOOLS) {
                        ObjectNode n = json.createObjectNode();
                        n.put("name", t.name());
                        n.put("description", t.description());
                        n.set("inputSchema", json.valueToTree(t.schema()));
                        n.set("annotations", json.valueToTree(Map.of("readOnlyHint", !t.operator(), "destructiveHint", false)));
                        tools.add(n);
                    }
                    ObjectNode res = json.createObjectNode();
                    res.set("tools", tools);
                    yield ok(id, res);
                }
                case "tools/call" -> ok(id, call(r.path("params").path("name").asText(), r.path("params").path("arguments"), key));
                default -> error(id, -32601, "method not found: " + method);
            };
        } catch (Exception e) {
            return error(id, -32603, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ObjectNode call(String name, JsonNode args, SecretsService.ApiKey key) {
        Tool tool = TOOLS.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) return toolResult(Map.of("error", "unknown tool " + name), true);
        if (tool.operator() && !key.operator()) {
            audit.record("ai:" + key.name(), "ai-mcp", "CALL", "mcp:" + name, "DENIED read-only scope", 403);
            return toolResult(Map.of("error", "forbidden: tool " + name + " needs operator scope; key '" + key.name() + "' is read-only", "error_zh", "只读 key 不能调用该工具"), true);
        }
        Map<String, Object> a = args == null || args.isNull() || args.isMissingNode() ? new HashMap<>() : json.convertValue(args, Map.class);
        try {
            Object res = switch (name) {
                case "list_messages" -> {
                    Map<String, String> p = new HashMap<>();
                    a.forEach((k, v) -> { if (v != null) p.put(k, String.valueOf(v)); });
                    p.putIfAbsent("limit", "50");
                    yield ai.listMessages(p);
                }
                case "get_message" -> ops.analyse(num(a, "id"), !Boolean.FALSE.equals(a.get("includeRaw"))).orElseThrow(() -> new NoSuchElementException("unknown message " + a.get("id")));
                case "list_business" -> ai.business(str(a, "family"), str(a, "status"), str(a, "party"));
                case "get_business" -> ops.business(req(a, "ref"), false).orElseThrow(() -> new NoSuchElementException("unknown reference " + a.get("ref")));
                case "reconcile_business" -> ops.reconcile(req(a, "ref")).orElseThrow(() -> new NoSuchElementException("unknown reference " + a.get("ref")));
                case "validate_message" -> ops.validate(req(a, "raw"), str(a, "from"), str(a, "to"), str(a, "mtType"));
                case "list_scenarios" -> ai.scenarios();
                case "list_review_queue" -> a.get("id") != null ? ai.reviewItem(num(a, "id")) : ai.queue(a.get("status") == null ? "PENDING_REVIEW" : str(a, "status"), str(a, "participant"));
                case "preview_review_response" -> ai.preview(num(a, "id"), a);
                case "get_reference" -> switch (req(a, "kind")) {
                    case "rule-codes" -> ai.ruleCodes();
                    case "state-machines" -> ai.stateMachines();
                    case "message-types" -> ai.messageTypes();
                    case "participants" -> ai.participants();
                    case "sessions" -> ai.sessions();
                    case "pending-responses" -> ai.pending();
                    default -> throw new IllegalArgumentException("kind must be rule-codes, state-machines, message-types, participants, sessions or pending-responses");
                };
                case "respond_review" -> {
                    Object r = ai.respond(num(a, "id"), a);
                    audit.record("ai:" + key.name(), "ai-mcp", "CALL", "mcp:respond_review", "item " + a.get("id") + " " + a.get("action"), 200);
                    yield r;
                }
                case "trigger_scenario" -> {
                    Object r = ai.doTrigger(req(a, "id"), str(a, "format"), str(a, "ref"), str(a, "from"), str(a, "to"), null);
                    audit.record("ai:" + key.name(), "ai-mcp", "CALL", "mcp:trigger_scenario", String.valueOf(a.get("id")), 200);
                    yield r;
                }
                default -> throw new IllegalArgumentException("unknown tool " + name);
            };
            return toolResult(res, false);
        } catch (Exception e) {
            return toolResult(Map.of("error", String.valueOf(e.getMessage())), true);
        }
    }

    private ObjectNode toolResult(Object res, boolean isError) {
        ObjectNode n = json.createObjectNode();
        ArrayNode content = json.createArrayNode();
        ObjectNode t = json.createObjectNode();
        t.put("type", "text");
        try { t.put("text", json.writeValueAsString(res)); } catch (Exception e) { t.put("text", String.valueOf(res)); }
        content.add(t);
        n.set("content", content);
        n.put("isError", isError);
        return n;
    }

    private ObjectNode ok(JsonNode id, JsonNode result) {
        ObjectNode n = json.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id);
        n.set("result", result);
        return n;
    }

    private ObjectNode error(JsonNode id, int code, String msg) {
        ObjectNode n = json.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id);
        n.set("error", json.valueToTree(Map.of("code", code, "message", String.valueOf(msg))));
        return n;
    }

    private static String str(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static String req(Map<String, Object> a, String k) {
        String v = str(a, k);
        if (v == null) throw new IllegalArgumentException("argument '" + k + "' required");
        return v;
    }

    private static Long num(Map<String, Object> a, String k) {
        return Long.valueOf(req(a, k));
    }
}
