package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeEventRepository extends JpaRepository<TradeEvent, Long> {
    List<TradeEvent> findByKindAndLedgerIdOrderByIdAsc(String kind, Long ledgerId);
    List<TradeEvent> findByKindAndRefAndLedgerIdIsNullOrderByIdAsc(String kind, String ref);
}
