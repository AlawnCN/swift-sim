package com.example.swiftsim.ops;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * Admin console credentials ({secrets-dir}/admin-console.properties) and AI API keys ({secrets-dir}/ai-api-keys).
 * Both files are generated on first start with random values and chmod 600; values are never logged (only the paths).
 * Files are re-read when they change, so keys / password can be edited without a restart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecretsService {
    public record ApiKey(String name, String scope, String key) {
        public boolean operator() { return "operator".equals(scope); }
    }

    private final OpsProps props;
    private static final SecureRandom RND = new SecureRandom();
    private long adminMtime = -1, keysMtime = -1;
    private Properties admin = new Properties();
    private List<ApiKey> keys = List.of();

    public Path adminFile() { return Paths.get(props.getSecretsDir(), "admin-console.properties").toAbsolutePath().normalize(); }

    public Path keysFile() { return Paths.get(props.getSecretsDir(), "ai-api-keys").toAbsolutePath().normalize(); }

    @PostConstruct
    void init() throws IOException {
        Path dir = adminFile().getParent();
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            try { Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------")); } catch (UnsupportedOperationException ignored) { }
        }
        if (props.isAdminEnabled() && !Files.exists(adminFile())) {
            write(adminFile(), "# swift-sim admin console login (generated " + new Date() + "). Edit to change; re-read automatically.\n"
                    + "username=admin\npassword=" + token(18) + "\n");
            log.info("admin console credentials generated: {}", adminFile());
        }
        if (props.isAiEnabled() && !Files.exists(keysFile())) {
            write(keysFile(), "# swift-sim AI API keys (generated " + new Date() + ").\n"
                    + "# one key per line: <name> <scope: read|operator> <key>   - read = GET/validate/reconcile only, operator = may also respond to the review queue / trigger scenarios\n"
                    + "ai-readonly read sk-sim-ro-" + token(32) + "\n"
                    + "ai-operator operator sk-sim-op-" + token(32) + "\n");
            log.info("AI API keys generated: {}", keysFile());
        }
    }

    private static void write(Path p, String content) throws IOException {
        Files.writeString(p, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        try { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
    }

    static String token(int n) {
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(a.charAt(RND.nextInt(a.length())));
        return sb.toString();
    }

    private synchronized void refresh() {
        try {
            if (Files.exists(adminFile()) && Files.getLastModifiedTime(adminFile()).toMillis() != adminMtime) {
                Properties p = new Properties();
                try (var r = Files.newBufferedReader(adminFile())) { p.load(r); }
                admin = p;
                adminMtime = Files.getLastModifiedTime(adminFile()).toMillis();
            }
            if (Files.exists(keysFile()) && Files.getLastModifiedTime(keysFile()).toMillis() != keysMtime) {
                List<ApiKey> l = new ArrayList<>();
                for (String line : Files.readAllLines(keysFile())) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    String[] x = t.split("\\s+");
                    if (x.length >= 3 && x[2].length() >= 16) l.add(new ApiKey(x[0], "operator".equalsIgnoreCase(x[1]) ? "operator" : "read", x[2]));
                }
                keys = List.copyOf(l);
                keysMtime = Files.getLastModifiedTime(keysFile()).toMillis();
            }
        } catch (IOException e) {
            log.warn("cannot read secrets files: {}", e.getMessage());
        }
    }

    public boolean checkAdmin(String user, String password) {
        refresh();
        String u = admin.getProperty("username"), p = admin.getProperty("password");
        if (u == null || p == null || user == null || password == null) return false;
        return eq(u, user) & eq(p, password);
    }

    public Optional<ApiKey> apiKey(String presented) {
        refresh();
        if (presented == null || presented.isBlank()) return Optional.empty();
        ApiKey hit = null;
        for (ApiKey k : keys) if (eq(k.key(), presented.trim())) hit = k;
        return Optional.ofNullable(hit);
    }

    public List<Map<String, Object>> keySummaries() {
        refresh();
        List<Map<String, Object>> l = new ArrayList<>();
        for (ApiKey k : keys) l.add(Map.of("name", k.name(), "scope", k.scope(), "keyPrefix", k.key().substring(0, Math.min(10, k.key().length())) + "…"));
        return l;
    }

    private static boolean eq(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
