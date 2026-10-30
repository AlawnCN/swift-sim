package com.example.swiftsim.scenario;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PendingResponseRepository extends JpaRepository<PendingResponse, Long> {
    List<PendingResponse> findByStatusOrderByDueAtAsc(String status);

    List<PendingResponse> findByGroupKeyAndCopyIndexGreaterThan(String groupKey, int copyIndex);

    List<PendingResponse> findTop200ByOrderByIdDesc();

    @Modifying
    @Transactional
    @Query("update PendingResponse p set p.status = 'FIRING' where p.id = ?1 and p.status = 'PENDING'")
    int claim(Long id);
}
