package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AmendmentRepository extends JpaRepository<Amendment, Long> {
    List<Amendment> findByUndertakingIdOrderByIdAsc(Long undertakingId);
}
