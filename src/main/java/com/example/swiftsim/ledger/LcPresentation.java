package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A presentation / drawing under an LC (MT754 / MT750) or a reimbursement claim (MT742, kind CLAIM). */
@Data
@Entity
@Table(name = "doc_credit_presentation", indexes = {@Index(columnList = "lcId"), @Index(columnList = "presentationRef")})
public class LcPresentation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long lcId;
    /** PRESENTATION or CLAIM */
    private String kind;
    @Column(length = 64)
    private String presentationRef;
    /** MT754 / MT750 / MT742 */
    private String msgType;
    /** MT754: PAID / NEGOTIATED / ACCEPTED (32A vs 32B); MT750: DISCREPANT; MT742: CLAIM */
    private String drawingType;
    private String currency;
    @Column(precision = 20, scale = 3)
    private BigDecimal amount;
    /** MT742 field 32B principal, when amount is the 34a total (principal + charges) */
    @Column(precision = 20, scale = 3)
    private BigDecimal principalAmount;
    @Column(precision = 20, scale = 3)
    private BigDecimal paidAmount;
    /** PRESENTED, DISCREPANT, AUTHORISED, ACCEPTED, REFUSED, DISCHARGED, PAID | CLAIMED, REIMBURSED, NON_CONFORMING */
    private String status;
    private String presenterBic;
    /** bank the presentation / claim is addressed to (issuer, confirming bank, reimbursing bank) */
    private String addresseeBic;
    private LocalDate presentedOn;
    private LocalDate maturityDate;
    /** MT754 with 32A: the nominated bank has already paid or negotiated */
    private Boolean nominatedBankPaid;
    /** explicit /PRESREF/ or amount-matched presentation this claim settles */
    @Column(length = 64)
    private String linkedPresentationRef;
    /** claim reference that settled this presentation, so a second claim is not tied to it */
    @Column(length = 64)
    private String linkedClaimRef;
    private Boolean utilisationBooked;
    @Column(length = 2000)
    private String discrepancies;
    private String disposal;
    private Long messageId;
    private Long resolutionMessageId;
    private Instant createdAt;
    private Instant resolvedAt;
}
