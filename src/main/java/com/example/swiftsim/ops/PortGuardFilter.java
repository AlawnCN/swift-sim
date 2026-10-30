package com.example.swiftsim.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Routing + authentication per listening port:
 * <ul>
 *   <li>gateway port (8080): unchanged; /admin/** and /ai/** are not reachable (404) (no authentication on this port).</li>
 *   <li>admin port (8081): Chinese admin console /admin/** plus the existing /api/** - login required (cookie SIMADMIN);
 *       state-changing requests must carry header X-Sim-Admin: 1 (CSRF guard) and are written to the audit log.</li>
 *   <li>AI port (8082): only /ai/v1/** - Authorization: Bearer &lt;key&gt; from ai-api-keys; read scope may only GET (+ POST validate / mcp).</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class PortGuardFilter extends OncePerRequestFilter {
    public static final String AI_KEY_ATTR = "sim.aiKey";
    public static final String ADMIN_USER_ATTR = "sim.adminUser";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private final OpsProps props;
    private final AdminSessions sessions;
    private final SecretsService secrets;
    private final AuditService audit;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        int port = req.getLocalPort();
        String path = req.getRequestURI();
        if (port == props.getAdminPort() && props.isAdminEnabled()) { admin(req, res, chain, path); return; }
        if (port == props.getAiPort() && props.isAiEnabled()) { ai(req, res, chain, path); return; }
        if (path.startsWith("/admin") || path.startsWith("/ai/") || path.equals("/ai")) { res.sendError(404); return; }
        chain.doFilter(req, res);
    }

    private void admin(HttpServletRequest req, HttpServletResponse res, FilterChain chain, String path) throws IOException, ServletException {
        if (path.equals("/") || path.equals("/admin")) { res.sendRedirect("/admin/"); return; }
        boolean pub = path.equals("/admin/login.html") || path.equals("/admin/api/login") || path.startsWith("/admin/assets/") || path.equals("/favicon.ico");
        if (!path.startsWith("/admin/") && !path.startsWith("/api/") && !pub) { res.sendError(404); return; }
        Optional<String> user = sessions.user(req);
        if (!pub && user.isEmpty()) {
            if (path.startsWith("/admin/api/") || path.startsWith("/api/")) json(res, 401, "{\"error\":\"login required\",\"login\":\"/admin/login.html\"}");
            else res.sendRedirect("/admin/login.html");
            return;
        }
        boolean mutating = !SAFE.contains(req.getMethod());
        if (mutating && !path.equals("/admin/api/login") && !"1".equals(req.getHeader("X-Sim-Admin"))) {
            json(res, 403, "{\"error\":\"missing header X-Sim-Admin: 1 (CSRF guard)\"}");
            return;
        }
        user.ifPresent(u -> req.setAttribute(ADMIN_USER_ATTR, u));
        if (path.equals("/admin/")) { req.getRequestDispatcher("/admin/index.html").forward(req, res); return; }
        try {
            chain.doFilter(req, res);
        } finally {
            if (mutating && !path.equals("/admin/api/login"))
                audit.record("admin:" + user.orElse("?"), "admin", req.getMethod(), path, req.getQueryString(), res.getStatus());
        }
    }

    private void ai(HttpServletRequest req, HttpServletResponse res, FilterChain chain, String path) throws IOException, ServletException {
        if (!path.startsWith("/ai/v1")) { json(res, 404, "{\"error\":\"not found - the AI API lives under /ai/v1 (see /ai/v1/openapi.json)\"}"); return; }
        if (path.equals("/ai/v1/openapi.json")) { chain.doFilter(req, res); return; }
        String h = req.getHeader("Authorization");
        String presented = h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).trim() : req.getHeader("X-Api-Key");
        Optional<SecretsService.ApiKey> key = secrets.apiKey(presented);
        if (key.isEmpty()) {
            res.setHeader("WWW-Authenticate", "Bearer");
            json(res, 401, "{\"error\":\"unauthorized\",\"hint\":\"Authorization: Bearer <key from config/secrets/ai-api-keys>\",\"hint_zh\":\"需要 Bearer API key（见 ai-api-keys 文件）\"}");
            return;
        }
        req.setAttribute(AI_KEY_ATTR, key.get());
        boolean mutating = !SAFE.contains(req.getMethod()) && !path.equals("/ai/v1/validate") && !path.equals("/ai/v1/mcp")
                && !path.matches("/ai/v1/review-queue/\\d+/preview");
        if (mutating && !key.get().operator()) {
            audit.record("ai:" + key.get().name(), "ai", req.getMethod(), path, "DENIED read-only scope", 403);
            json(res, 403, "{\"error\":\"forbidden\",\"code\":\"AI-SCOPE\",\"message\":\"key '" + key.get().name() + "' has read scope; this call needs operator scope\",\"message_zh\":\"只读 key 不允许修改操作\"}");
            return;
        }
        try {
            chain.doFilter(req, res);
        } finally {
            if (mutating) audit.record("ai:" + key.get().name(), "ai", req.getMethod(), path, req.getQueryString(), res.getStatus());
        }
    }

    private static void json(HttpServletResponse res, int status, String body) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(body);
    }
}
