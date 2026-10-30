package com.example.swiftsim.review;

import com.example.swiftsim.config.ConfigService;
import com.example.swiftsim.config.Participant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-participant manual / AI review mode (participants.yml reviewMode, overridden at runtime and persisted in H2). */
@Service
@RequiredArgsConstructor
public class ReviewModeService {
    private final ParticipantSettingRepository repo;
    private final ConfigService cfg;

    public boolean isReviewMode(Participant p) {
        if (p == null || p.isSut()) return false;
        return repo.findById(p.getId()).map(ParticipantSetting::getReviewMode).filter(b -> b != null).orElse(p.isReviewMode());
    }

    public void set(Participant p, boolean enabled) {
        if (p.isSut()) throw new IllegalArgumentException("review mode applies to virtual banks only, not to the SUT");
        ParticipantSetting s = repo.findById(p.getId()).orElseGet(ParticipantSetting::new);
        s.setParticipantId(p.getId());
        s.setReviewMode(enabled);
        repo.save(s);
    }

    public Map<String, Boolean> all() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (Participant p : cfg.getConfig().getParticipants()) if (!p.isSut()) m.put(p.getId(), isReviewMode(p));
        return m;
    }
}
