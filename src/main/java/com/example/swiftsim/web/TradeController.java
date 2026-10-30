package com.example.swiftsim.web;

import com.example.swiftsim.gateway.GatewayService;
import com.example.swiftsim.ledger.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Documentary credit (Cat 7) and collection (Cat 4) ledgers - same shape as /api/undertakings. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TradeController {
    private final LedgerService ledger;
    private final GatewayService gateway;

    private static String fam(String path) {
        return "lcs".equals(path) ? "LC" : "COLL";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @GetMapping("/{kind:lcs|collections}")
    public List<Map<String, Object>> list(@PathVariable String kind, @RequestParam(required = false) String status, @RequestParam(required = false) String party) {
        return ledger.tradeList(fam(kind), blank(status), blank(party) == null ? null : gateway.bicOf(party));
    }

    @GetMapping("/{kind:lcs|collections}/{ref}")
    public ResponseEntity<?> detail(@PathVariable String kind, @PathVariable String ref, @RequestParam(defaultValue = "false") boolean includeRaw) {
        return ledger.tradeDetail(fam(kind), ref, includeRaw).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown " + ("LC".equals(fam(kind)) ? "documentary credit" : "collection") + " " + ref)));
    }

    @PostMapping("/{kind:lcs|collections}/{ref}/parties")
    public Object addParty(@PathVariable String kind, @PathVariable String ref, @RequestBody Map<String, String> body) {
        String who = body.getOrDefault("participant", body.get("bic"));
        if (who == null) throw new IllegalArgumentException("participant (id) or bic required");
        return ledger.tradeAddParty(fam(kind), ref, gateway.bicOf(who), String.valueOf(body.get("role")).toUpperCase());
    }

    @DeleteMapping("/{kind:lcs|collections}")
    public Map<String, Object> clear(@PathVariable String kind) {
        return Map.of("deleted", ledger.tradeClear(fam(kind)));
    }
}
