package com.example.swiftsim.gateway;

import com.example.swiftsim.config.Bics;
import com.example.swiftsim.config.ConfigService;
import com.example.swiftsim.config.Participant;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Simulated FIN logical-terminal sessions. Each simulator start = a new "login" (session number + 1) for every
 * participant; input (ISN) and output (OSN) sequence numbers continue across sessions. Persisted in H2.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {
    private final LtSessionRepository repo;
    private final ConfigService cfg;

    @PostConstruct
    public synchronized void login() {
        for (Participant p : cfg.getConfig().getParticipants()) {
            LtSession s = get(p.bic11());
            s.setSessionNo(s.getSessionNo() >= 9999 ? 1 : s.getSessionNo() + 1);
            repo.save(s);
        }
        log.info("FIN-like sessions opened for {} participants", cfg.getConfig().getParticipants().size());
    }

    private LtSession get(String bic) {
        return repo.findById(bic).orElseGet(() -> {
            LtSession s = new LtSession();
            s.setBic(bic);
            s.setSessionNo(0);
            return s;
        });
    }

    public synchronized LtSession nextIsn(String bic) {
        LtSession s = get(Bics.toBic11(bic));
        if (s.getSessionNo() == 0) s.setSessionNo(1);
        s.setLastIsn(s.getLastIsn() >= 999999 ? 1 : s.getLastIsn() + 1);
        return repo.save(s);
    }

    public synchronized LtSession nextOsn(String bic) {
        LtSession s = get(Bics.toBic11(bic));
        if (s.getSessionNo() == 0) s.setSessionNo(1);
        s.setLastOsn(s.getLastOsn() >= 999999 ? 1 : s.getLastOsn() + 1);
        return repo.save(s);
    }

    public synchronized long peekIsn(String bic) {
        return get(Bics.toBic11(bic)).getLastIsn();
    }

    public List<LtSession> all() {
        return repo.findAll();
    }
}
