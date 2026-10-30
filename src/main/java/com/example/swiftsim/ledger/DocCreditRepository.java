package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocCreditRepository extends JpaRepository<DocCredit, Long> {
    Optional<DocCredit> findByRef(String ref);
    List<DocCredit> findAllByOrderByUpdatedAtDesc();
}
