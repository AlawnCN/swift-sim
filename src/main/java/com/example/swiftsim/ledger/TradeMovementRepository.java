package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeMovementRepository extends JpaRepository<TradeMovement, Long> {
    List<TradeMovement> findByKindAndLedgerIdOrderByIdAsc(String kind, Long ledgerId);
}
