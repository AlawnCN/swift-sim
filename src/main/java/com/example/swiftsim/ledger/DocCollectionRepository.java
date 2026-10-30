package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocCollectionRepository extends JpaRepository<DocCollection, Long> {
    Optional<DocCollection> findByRef(String ref);
    Optional<DocCollection> findFirstByCollectingRef(String collectingRef);
    List<DocCollection> findAllByOrderByUpdatedAtDesc();
}
