package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MovementRepository extends JpaRepository<Movement, Long> {
    List<Movement> findByUndertakingIdOrderByIdAsc(Long undertakingId);
}
