package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UndertakingRepository extends JpaRepository<Undertaking, Long> {
    Optional<Undertaking> findByRef(String ref);

    List<Undertaking> findAllByOrderByUpdatedAtDesc();
}
