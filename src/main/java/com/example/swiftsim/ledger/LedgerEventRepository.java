package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEventRepository extends JpaRepository<LedgerEvent, Long> {
    List<LedgerEvent> findByUndertakingIdOrderByIdAsc(Long undertakingId);

    List<LedgerEvent> findByRefAndUndertakingIdIsNullOrderByIdAsc(String ref);

    boolean existsByMessageId(Long messageId);
}
