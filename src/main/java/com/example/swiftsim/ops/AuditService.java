package com.example.swiftsim.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Audit trail of admin-console and AI-operator actions: JSON lines in {data-dir}/audit.log. */
@Service
@RequiredArgsConstructor
public class AuditService {
    private final OpsProps props;
    private final ObjectMapper json = new ObjectMapper();

    public Path file() { return Paths.get(props.getDataDir(), "audit.log"); }

    public synchronized void record(String actor, String channel, String method, String path, String detail, int status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", Instant.now().toString());
        m.put("actor", actor);
        m.put("channel", channel);
        m.put("method", method);
        m.put("path", path);
        if (detail != null && !detail.isBlank()) m.put("detail", detail.length() > 500 ? detail.substring(0, 500) + "…" : detail);
        m.put("status", status);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), json.writeValueAsString(m) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> tail(int n, String q) {
        try {
            if (!Files.exists(file())) return List.of();
            List<String> lines = Files.readAllLines(file());
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0 && out.size() < n; i--) {
                if (q != null && !q.isBlank() && !lines.get(i).toLowerCase().contains(q.toLowerCase())) continue;
                try { out.add(json.readValue(lines.get(i), Map.class)); } catch (Exception ignored) { }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }
}
