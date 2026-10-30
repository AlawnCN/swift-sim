package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "undertaking_amendment", indexes = @Index(columnList = "undertakingId"))
public class Amendment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long undertakingId;
    private Integer seqNo;
    /** PENDING, ACCEPTED, REJECTED, EFFECTIVE (no consent needed / extension answering extend-or-pay) */
    private String status;
    private String currency;
    @Column(precision = 20, scale = 3)
    private BigDecimal increase;
    @Column(precision = 20, scale = 3)
    private BigDecimal decrease;
    private LocalDate newExpiryDate;
    private String senderBic;
    private String receiverBic;
    private Long messageId;
    private Long responseMessageId;
    private Instant createdAt;
    private Instant respondedAt;
    @Column(length = 2000)
    private String note;
}
