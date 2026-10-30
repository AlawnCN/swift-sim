package com.example.swiftsim.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, Long> {
    List<ReviewItem> findByStatusOrderByIdAsc(String status);

    List<ReviewItem> findTop500ByOrderByIdDesc();

    List<ReviewItem> findByTxRefOrderByIdAsc(String txRef);
}
