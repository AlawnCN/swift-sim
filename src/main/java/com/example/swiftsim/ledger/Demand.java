package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "undertaking_demand", indexes = @Index(columnList = "undertakingId"))
public class Demand {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long undertakingId;
    @Column(length = 64)
    private String demandRef;
    /** PAYM / PAEX */
    private String type;
    private String currency;
    @Column(precision = 20, scale = 3)
    private BigDecimal amount;
    private LocalDate requestedExpiry;
    /** RECEIVED, ACCEPTED (awaiting settlement), PARTIALLY_PAID, PAID, REFUSED, EXTENDED, WITHDRAWN */
    private String status;
    private String presenterBic;
    private Long messageId;
    private Long resolutionMessageId;
    @Column(precision = 20, scale = 3)
    private BigDecimal paidAmount;
    /** a later MT769 for the same amount was linked to this payment (no second reduction) */
    private boolean reductionAdvised;
    private Instant createdAt;
    private Instant resolvedAt;
    @Column(length = 2000)
    private String reason;
}
