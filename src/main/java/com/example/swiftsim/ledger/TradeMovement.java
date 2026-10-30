package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/** Amount movement of an LC (ISSUE/INCREASE/DECREASE/UTILISATION/TRANSFER/REIMBURSEMENT...) or a collection (PAYMENT/ADJUSTMENT/...). */
@Data
@Entity
@Table(name = "trade_movement", indexes = @Index(columnList = "kind,ledgerId"))
public class TradeMovement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 8)
    private String kind;
    private Long ledgerId;
    private String type;
    @Column(length = 4)
    private String currency;
    @Column(precision = 20, scale = 3)
    private BigDecimal amount;
    @Column(precision = 20, scale = 3)
    private BigDecimal balanceAfter;
    private Long messageId;
    @Column(length = 64)
    private String itemRef;
    @Column(length = 1000)
    private String note;
    private Instant at;
}
