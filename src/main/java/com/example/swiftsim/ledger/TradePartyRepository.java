package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradePartyRepository extends JpaRepository<TradeParty, Long> {
    List<TradeParty> findByKindAndLedgerIdOrderByIdAsc(String kind, Long ledgerId);
}
