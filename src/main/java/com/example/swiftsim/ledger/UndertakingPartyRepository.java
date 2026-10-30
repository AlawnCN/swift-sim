package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UndertakingPartyRepository extends JpaRepository<UndertakingParty, Long> {
    List<UndertakingParty> findByUndertakingIdOrderByIdAsc(Long undertakingId);
}
