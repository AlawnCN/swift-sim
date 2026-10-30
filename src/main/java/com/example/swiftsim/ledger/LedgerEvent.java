package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

/** Status history / audit trail of an undertaking (also used for rejects of unknown references: undertakingId = null). */
@Data
@Entity
@Table(name = "undertaking_event", indexes = {@Index(columnList = "undertakingId"), @Index(columnList = "ref")})
public class LedgerEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long undertakingId;
    @Column(length = 64)
    private String ref;
    private Instant at;
    private LocalDate businessDate;
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private Long messageId;
    private String msgType;
    private String senderBic;
    private String receiverBic;
    @Column(length = 4000)
    private String detail;
}
