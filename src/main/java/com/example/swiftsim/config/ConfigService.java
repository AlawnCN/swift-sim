package com.example.swiftsim.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ConfigService {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Getter
    private final Path configDir;
    @Getter
    private volatile SimConfig config = new SimConfig();
    @Getter
    private volatile List<Scenario> scenarios = List.of();
    /** group -> active scenario id */
    private final Map<String, String> active = new ConcurrentHashMap<>();

    public ConfigService(@Value("${sim.config-dir:./config}") String dir) {
        this.configDir = Path.of(dir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public synchronized void reload() {
        try {
            config = yaml.readValue(configDir.resolve("participants.yml").toFile(), SimConfig.class);
            Map<String, List<Scenario>> f = yaml.readValue(configDir.resolve("scenarios.yml").toFile(),
                    new TypeReference<Map<String, List<Scenario>>>() {});
            scenarios = List.copyOf(f.getOrDefault("scenarios", List.of()));
            active.clear();
            for (Scenario s : scenarios) {
                if (s.getGroup() != null && (s.isDefaultActive() || !active.containsKey(s.getGroup()))) {
                    if (s.isDefaultActive() || active.get(s.getGroup()) == null) active.put(s.getGroup(), s.getId());
                }
            }
            log.info("Loaded {} participants, {} scenarios from {}", config.getParticipants().size(), scenarios.size(), configDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load config from " + configDir + ": " + e.getMessage(), e);
        }
    }

    public Optional<Participant> byBic(String bic) {
        String b = Bics.toBic11(bic);
        if (b == null) return Optional.empty();
        return config.getParticipants().stream().filter(p -> b.equals(p.bic11())).findFirst();
    }

    public Optional<Participant> byId(String id) {
        return config.getParticipants().stream().filter(p -> p.getId().equalsIgnoreCase(id)).findFirst();
    }

    /** Resolve a participant id or a BIC. */
    public Optional<Participant> resolve(String idOrBic) {
        if (idOrBic == null || idOrBic.isBlank()) return Optional.empty();
        return byId(idOrBic).or(() -> byBic(idOrBic));
    }

    public Optional<Scenario> scenario(String id) {
        return scenarios.stream().filter(s -> s.getId().equals(id)).findFirst();
    }

    public boolean isActive(Scenario s) {
        return s.getGroup() == null || s.getId().equals(active.get(s.getGroup()));
    }

    public void activate(Scenario s) {
        if (s.getGroup() != null) active.put(s.getGroup(), s.getId());
    }

    public Map<String, String> activeMap() {
        return new TreeMap<>(active);
    }

    public String readTemplate(String name) throws IOException {
        Path p = configDir.resolve("templates").resolve(name).normalize();
        if (!p.startsWith(configDir)) throw new IOException("bad template path");
        return Files.readString(p);
    }
}
