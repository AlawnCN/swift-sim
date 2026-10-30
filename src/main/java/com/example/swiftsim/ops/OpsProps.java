package com.example.swiftsim.ops;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** Ports / paths of the admin console (default 8081) and the AI API (default 8082); the gateway stays on server.port (8080). */
@Component
@Getter
public class OpsProps {
    @Value("${server.port:8080}") private int mainPort;
    @Value("${sim.admin.enabled:true}") private boolean adminEnabled;
    @Value("${sim.admin.port:8081}") private int adminPort;
    @Value("${sim.admin.address:127.0.0.1}") private String adminAddress;
    @Value("${sim.ai.enabled:true}") private boolean aiEnabled;
    @Value("${sim.ai.port:8082}") private int aiPort;
    @Value("${sim.ai.address:127.0.0.1}") private String aiAddress;
    @Value("${sim.secrets-dir:./config/secrets}") private String secretsDir;
    @Value("${sim.data-dir:./data}") private String dataDir;
    @Value("${sim.backup-dir:}") private String backupDirRaw;
    @Value("${sim.protected-refs:KEGTEE2026000101}") private String protectedRefsRaw;

    public List<String> protectedRefs() {
        return Arrays.stream(protectedRefsRaw.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    public String backupDir() {
        return backupDirRaw == null || backupDirRaw.isBlank() ? dataDir + "/backups" : backupDirRaw;
    }
}
