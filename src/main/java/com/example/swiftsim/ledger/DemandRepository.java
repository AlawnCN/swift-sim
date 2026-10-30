package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DemandRepository extends JpaRepository<Demand, Long> {
    List<Demand> findByUndertakingIdOrderByIdAsc(Long undertakingId);
}
