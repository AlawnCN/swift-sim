package com.example.swiftsim.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MessageRepository extends JpaRepository<MessageRecord, Long>, JpaSpecificationExecutor<MessageRecord> {
    List<MessageRecord> findByContentHashAndSenderBicAndIdLessThanOrderByIdAsc(String contentHash, String senderBic, Long id);

    List<MessageRecord> findByStatusAndDirectionNot(String status, String direction);

    @Query("select m from MessageRecord m where m.undertakingRef = ?1 or m.txRef = ?1 or m.relatedRef = ?1 order by m.id asc")
    List<MessageRecord> findForRef(String ref);

    boolean existsBySenderBicAndMurAndIdNot(String senderBic, String mur, Long id);
}
