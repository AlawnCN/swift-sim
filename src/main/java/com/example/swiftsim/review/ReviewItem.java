package com.example.swiftsim.review;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** An inbound message for a virtual bank in manual / AI review mode, waiting for an operator decision. */
@Data
@Entity
@Table(name = "review_item", indexes = @Index(columnList = "status"))
public class ReviewItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant createdAt;
    /** PENDING_REVIEW, RESPONDED, DISMISSED */
    private String status;
    private String participantId;
    private String participantBic;
    private Long messageId;
    private String msgType;
    private String format;
    @Column(length = 64)
    private String txRef;
    private String senderBic;
    /** ledger verdict computed when the message was delivered: APPLIED / REJECTED / ... */
    private String ledgerVerdict;
    @Column(length = 4000)
    private String ledgerErrors;
    /** comma separated actions that make sense for this message type */
    private String suggestedActions;
    private String responseAction;
    @Column(length = 1000)
    private String responseMessageIds;
    @Column(length = 4000)
    private String operatorNote;
    private Instant respondedAt;
}
