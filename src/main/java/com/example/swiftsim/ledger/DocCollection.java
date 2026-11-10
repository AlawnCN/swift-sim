package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Ledger entry of one documentary / clean collection or cash letter (keyed by the remitting bank's collection reference). */
@Data
@Entity
@Table(name = "doc_collection", uniqueConstraints = @UniqueConstraint(columnNames = "ref"))
public class DocCollection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private Long version;
    @Column(length = 64, nullable = false)
    private String ref;
    /** collecting bank's own reference (from its MT410 :20:) */
    @Column(length = 64)
    private String collectingRef;
    /** DP (documents against payment), DA (documents against acceptance), CLEAN, CASH (cash letter) */
    private String collType;
    private String rules;
    @Column(length = 4)
    private String currency;
    @Column(precision = 20, scale = 3)
    private BigDecimal originalAmount;
    @Column(precision = 20, scale = 3)
    private BigDecimal amount;
    /** amount still to be collected */
    @Column(precision = 20, scale = 3)
    private BigDecimal outstanding;
    @Column(precision = 20, scale = 3)
    private BigDecimal paidAmount;
    @Column(precision = 20, scale = 3)
    private BigDecimal acceptedAmount;
    private String tenor;
    private LocalDate maturityDate;
    @Column(length = 600)
    private String drawer;
    @Column(length = 600)
    private String drawee;
    @Column(length = 1000)
    private String documents;
    private String remittingBic;
    private String collectingBic;
    private Boolean tracerPending;
    private Integer tracerCount;
    private Boolean returnRequested;
    /** URC 522 art. 19: documentary partial payment only when /COLLINST/ carries /PARTIAL/ (not NO) */
    private Boolean partialPaymentAuthorised;
    private String milestone;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private Long originMessageId;
    private Long lastMessageId;
}
