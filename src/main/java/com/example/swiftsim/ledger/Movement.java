package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/** Amount movements: ISSUE, INCREASE, DECREASE, PAYMENT, REDUCTION, RELEASE, TERMINATION. */
@Data
@Entity
@Table(name = "undertaking_movement", indexes = @Index(columnList = "undertakingId"))
public class Movement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long undertakingId;
    private String type;
    private String currency;
    @Column(precision = 20, scale = 3)
    private BigDecimal amount;
    @Column(precision = 20, scale = 3)
    private BigDecimal outstandingAfter;
    private Long messageId;
    private String demandRef;
    private Instant at;
    @Column(length = 1000)
    private String note;
}
