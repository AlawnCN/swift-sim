package com.example.swiftsim.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LcPresentationRepository extends JpaRepository<LcPresentation, Long> {
    List<LcPresentation> findByLcIdOrderByIdAsc(Long lcId);
    List<LcPresentation> findByPresentationRef(String presentationRef);
}
