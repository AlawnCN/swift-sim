package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LcAmendmentRepository extends JpaRepository<LcAmendment, Long> {
    List<LcAmendment> findByLcIdOrderByIdAsc(Long lcId);
}
