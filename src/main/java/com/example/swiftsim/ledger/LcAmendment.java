package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "doc_credit_amendment", indexes = @Index(columnList = "lcId"))
public class LcAmendment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long lcId;
    private Integer seqNo;
    private String currency;
    @Column(precision = 20, scale = 3)
    private BigDecimal increase;
    @Column(precision = 20, scale = 3)
    private BigDecimal decrease;
    private LocalDate newExpiryDate;
    private Integer newTolerancePlus;
    private Integer newToleranceMinus;
    private String newForm;
    private String newPartialShipments;
    private Boolean cancellation;
    /** PENDING, ACCEPTED, REJECTED */
    private String status;
    private String senderBic;
    private String receiverBic;
    private Long messageId;
    private Long responseMessageId;
    private Instant createdAt;
    private Instant respondedAt;
    @Column(length = 2000)
    private String note;
}
