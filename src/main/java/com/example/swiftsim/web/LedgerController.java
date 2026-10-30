package com.example.swiftsim.web;

import com.example.swiftsim.config.ConfigService;
import com.example.swiftsim.config.Participant;
import com.example.swiftsim.gateway.GatewayService;
import com.example.swiftsim.gateway.SessionService;
import com.example.swiftsim.ledger.BusinessClock;
import com.example.swiftsim.ledger.LedgerService;
import com.example.swiftsim.review.ReviewItem;
import com.example.swiftsim.review.ReviewModeService;
import com.example.swiftsim.review.ReviewService;
import com.example.swiftsim.scenario.ScenarioEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/** Ledger, review queue, pending responses, gateway sessions and business-date admin APIs. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LedgerController {
    private final LedgerService ledger;
    private final ReviewService review;
    private final ReviewModeService reviewMode;
    private final ScenarioEngine engine;
    private final ConfigService cfg;
    private final BusinessClock clock;
    private final SessionService sessions;
    private final GatewayService gateway;

    @GetMapping("/undertakings")
    public List<Map<String, Object>> undertakings(@RequestParam(required = false) String status, @RequestParam(required = false) String party) {
        return ledger.list(blank(status), blank(party) == null ? null : gateway.bicOf(party));
    }

    @GetMapping("/undertakings/{ref}")
    public ResponseEntity<?> undertaking(@PathVariable String ref, @RequestParam(defaultValue = "false") boolean includeRaw) {
        return ledger.detail(ref, includeRaw).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", "unknown undertaking " + ref);
            m.put("orphanEvents", ledger.orphanEvents(ref));
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(m);
        });
    }

    /** Register an out-of-band relationship (e.g. the applicant's bank / counter-guarantor) for role validation. */
    @PostMapping("/undertakings/{ref}/parties")
    public Object addParty(@PathVariable String ref, @RequestBody Map<String, String> body) {
        String who = body.getOrDefault("participant", body.get("bic"));
        if (who == null) throw new IllegalArgumentException("participant (id) or bic required");
        return ledger.adminAddParty(ref, gateway.bicOf(who), String.valueOf(body.get("role")).toUpperCase());
    }

    @DeleteMapping("/undertakings")
    public Map<String, Object> clear() {
        return Map.of("deleted", ledger.clear());
    }

    @GetMapping("/review-queue")
    public List<ReviewItem> queue(@RequestParam(defaultValue = "PENDING_REVIEW") String status, @RequestParam(required = false) String participant) {
        return review.list(status, participant);
    }

    @GetMapping("/review-queue/{id}")
    public Map<String, Object> reviewItem(@PathVariable Long id) {
        return review.detail(id);
    }

    @PostMapping("/review-queue/{id}/respond")
    public Map<String, Object> respond(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return review.respond(id, body);
    }

    @GetMapping("/participants/{id}/review-mode")
    public Map<String, Object> getReview(@PathVariable String id) {
        Participant p = cfg.resolve(id).orElseThrow(() -> new NoSuchElementException("unknown participant " + id));
        return Map.of("participant", p.getId(), "reviewMode", reviewMode.isReviewMode(p));
    }

    @PostMapping("/participants/{id}/review-mode")
    public Map<String, Object> setReview(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body,
                                         @RequestParam(required = false) Boolean enabled) {
        Participant p = cfg.resolve(id).orElseThrow(() -> new NoSuchElementException("unknown participant " + id));
        boolean on = enabled != null ? enabled : body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "true")));
        reviewMode.set(p, on);
        return Map.of("participant", p.getId(), "reviewMode", reviewMode.isReviewMode(p));
    }

    @GetMapping("/pending-responses")
    public Object pendingResponses(@RequestParam(defaultValue = "false") boolean all) {
        return engine.listPending(all);
    }

    @GetMapping("/gateway/sessions")
    public Object sessions() {
        return sessions.all();
    }

    @GetMapping("/admin/business-date")
    public Map<String, Object> businessDate() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessDate", clock.today().toString());
        m.put("override", clock.getOverride() == null ? null : clock.getOverride().toString());
        return m;
    }

    @PostMapping("/admin/business-date")
    public Map<String, Object> setBusinessDate(@RequestBody Map<String, String> body) {
        clock.set(LocalDate.parse(body.get("date")));
        int expired = ledger.sweep();
        Map<String, Object> m = businessDate();
        m.put("expiredNow", expired);
        return m;
    }

    @DeleteMapping("/admin/business-date")
    public Map<String, Object> resetBusinessDate() {
        clock.set(null);
        return businessDate();
    }

    @PostMapping("/admin/expiry-sweep")
    public Map<String, Object> sweep() {
        return Map.of("expired", ledger.sweep(), "businessDate", clock.today().toString());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
