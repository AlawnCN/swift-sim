package com.example.swiftsim.ops;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory admin console sessions (cookie SIMADMIN, HttpOnly, SameSite=Strict, 12 h sliding). A restart logs everybody out. */
@Component
public class AdminSessions {
    public static final String COOKIE = "SIMADMIN";
    private record S(String user, Instant expires) { }
    private final Map<String, S> sessions = new ConcurrentHashMap<>();
    private final Map<String, int[]> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockedUntil = new ConcurrentHashMap<>();

    public String create(String user) {
        String t = SecretsService.token(40);
        sessions.put(t, new S(user, Instant.now().plusSeconds(12 * 3600)));
        return t;
    }

    public Optional<String> user(HttpServletRequest req) {
        if (req.getCookies() == null) return Optional.empty();
        for (Cookie c : req.getCookies()) {
            if (!COOKIE.equals(c.getName())) continue;
            S s = sessions.get(c.getValue());
            if (s == null) continue;
            if (s.expires().isBefore(Instant.now())) { sessions.remove(c.getValue()); continue; }
            sessions.put(c.getValue(), new S(s.user(), Instant.now().plusSeconds(12 * 3600)));
            return Optional.of(s.user());
        }
        return Optional.empty();
    }

    public void destroy(HttpServletRequest req) {
        if (req.getCookies() != null) for (Cookie c : req.getCookies()) if (COOKIE.equals(c.getName())) sessions.remove(c.getValue());
    }

    public boolean locked(String ip) {
        Instant u = lockedUntil.get(ip);
        return u != null && u.isAfter(Instant.now());
    }

    public void failed(String ip) {
        int[] n = failures.computeIfAbsent(ip, k -> new int[1]);
        if (++n[0] >= 10) { lockedUntil.put(ip, Instant.now().plusSeconds(300)); n[0] = 0; }
    }

    public void succeeded(String ip) {
        failures.remove(ip);
    }

    public static String cookieHeader(String token, int maxAge) {
        return COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge;
    }
}
