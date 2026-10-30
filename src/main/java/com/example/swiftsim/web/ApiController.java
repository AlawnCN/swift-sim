package com.example.swiftsim.web;

import com.example.swiftsim.config.ConfigService;
import com.example.swiftsim.config.Participant;
import com.example.swiftsim.config.Scenario;
import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.domain.MessageRepository;
import com.example.swiftsim.gateway.GatewayService;
import com.example.swiftsim.gateway.SubmitOptions;
import com.example.swiftsim.gateway.SubmitResult;
import com.example.swiftsim.scenario.ScenarioEngine;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {
    private final GatewayService gateway;
    private final ScenarioEngine engine;
    private final ConfigService cfg;
    private final MessageRepository repo;
    private final com.example.swiftsim.review.ReviewModeService reviewMode;

    @Value("${sim.samples-dir:./samples}")
    private String samplesDir;

    /** Submit a raw MT (FIN) or MX (XML) message. Format is auto-detected. */
    @PostMapping(value = "/messages", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<SubmitResult> submit(@RequestBody String raw,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @RequestParam(required = false) String mtType,
                                               @RequestParam(required = false) String scenario,
                                               @RequestParam(defaultValue = "REST") String channel,
                                               @RequestHeader(value = "X-Sim-Scenario", required = false) String scenarioHeader) {
        SubmitResult r = gateway.submit(raw, SubmitOptions.builder().channel(channel).from(blank(from)).to(blank(to))
                .mtType(blank(mtType)).scenario(blank(scenario) != null ? scenario : blank(scenarioHeader)).build());
        return ResponseEntity.status("ACK".equals(r.getStatus()) ? HttpStatus.ACCEPTED : HttpStatus.UNPROCESSABLE_ENTITY).body(r);
    }

    @GetMapping("/messages")
    public List<MessageRecord> list(@RequestParam(required = false) String to,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String ref,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) String direction,
                                    @RequestParam(required = false) Long sinceId,
                                    @RequestParam(defaultValue = "true") boolean includeRaw,
                                    @RequestParam(defaultValue = "200") int limit) {
        Specification<MessageRecord> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (blank(to) != null) ps.add(cb.equal(root.get("receiverBic"), gateway.bicOf(to)));
            if (blank(from) != null) ps.add(cb.equal(root.get("senderBic"), gateway.bicOf(from)));
            if (blank(ref) != null) ps.add(cb.or(cb.like(root.get("txRef"), "%" + ref + "%"), cb.like(root.get("relatedRef"), "%" + ref + "%")));
            if (blank(type) != null) ps.add(cb.like(cb.lower(root.get("msgType")), "%" + type.toLowerCase() + "%"));
            if (blank(direction) != null) ps.add(cb.equal(root.get("direction"), direction.toUpperCase()));
            if (sinceId != null) ps.add(cb.greaterThan(root.get("id"), sinceId));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        List<MessageRecord> list = repo.findAll(spec, PageRequest.of(0, Math.min(limit, 2000), Sort.by(Sort.Direction.DESC, "id"))).getContent();
        if (!includeRaw) list.forEach(m -> m.setRaw(null));
        return list;
    }

    @GetMapping("/messages/{id}")
    public ResponseEntity<MessageRecord> one(@PathVariable Long id) {
        return repo.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/messages/{id}/raw", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> raw(@PathVariable Long id) {
        return repo.findById(id).map(m -> ResponseEntity.ok(m.getRaw())).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/messages")
    public Map<String, Object> clear() {
        long n = repo.count();
        repo.deleteAll();
        return Map.of("deleted", n);
    }

    @GetMapping("/participants")
    public List<Map<String, Object>> participants() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Participant p : cfg.getConfig().getParticipants()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("bic", p.getBic());
            m.put("role", p.getRole());
            m.put("sut", p.isSut());
            m.put("webhookUrl", p.getWebhookUrl());
            m.put("deliveryNotifications", p.isDeliveryNotifications());
            m.put("reviewMode", reviewMode.isReviewMode(p));
            l.add(m);
        }
        return l;
    }

    @GetMapping("/scenarios")
    public Map<String, Object> scenarios() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Scenario s : cfg.getScenarios()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            m.put("description", s.getDescription());
            m.put("group", s.getGroup());
            m.put("active", cfg.isActive(s));
            m.put("fallback", s.isFallback());
            m.put("hasKickoff", !s.getKickoff().isEmpty());
            m.put("reactsTo", s.getReactions().stream().flatMap(r -> r.getMessageTypes().stream()).distinct().toList());
            list.add(m);
        }
        return Map.of("active", cfg.activeMap(), "scenarios", list);
    }

    @PostMapping("/scenarios/{id}/activate")
    public Map<String, Object> activate(@PathVariable String id) {
        Scenario s = cfg.scenario(id).orElseThrow(() -> new NoSuchElementException("unknown scenario " + id));
        cfg.activate(s);
        return Map.of("activated", id, "active", cfg.activeMap());
    }

    /** Kick off a scenario: a virtual bank sends the first message to your system. Body (optional JSON): vars such as ref, amount, currency. */
    @PostMapping("/scenarios/{id}/trigger")
    public Map<String, Object> trigger(@PathVariable String id, @RequestParam(defaultValue = "MT") String format,
                                       @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String ref,
                                       @RequestBody(required = false) Map<String, String> vars) {
        if (ref != null && !ref.isBlank()) { vars = vars == null ? new java.util.HashMap<>() : new java.util.HashMap<>(vars); vars.put("ref", ref.trim()); }
        return Map.of("scenario", id, "format", format, "scheduled", engine.trigger(id, format, vars, blank(from), blank(to)));
    }

    @PostMapping("/admin/reload")
    public Map<String, Object> reload() {
        cfg.reload();
        return Map.of("participants", cfg.getConfig().getParticipants().size(), "scenarios", cfg.getScenarios().size());
    }

    @GetMapping("/samples")
    public List<String> samples() throws IOException {
        Path d = Path.of(samplesDir);
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @GetMapping(value = "/samples/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> sample(@PathVariable String name) throws IOException {
        Path d = Path.of(samplesDir).toAbsolutePath().normalize();
        Path p = d.resolve(name).normalize();
        if (!p.startsWith(d) || !Files.isRegularFile(p)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Files.readString(p));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
