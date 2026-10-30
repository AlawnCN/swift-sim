package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Ledger entry of one documentary credit (keyed by the documentary credit number = MT700 :20:). */
@Data
@Entity
@Table(name = "doc_credit", uniqueConstraints = @UniqueConstraint(columnNames = "ref"))
public class DocCredit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private Long version;
    @Column(length = 64, nullable = false)
    private String ref;
    /** message that created the record: MT700 / MT705 / MT710 / MT740 */
    private String origin;
    /** 40A / 40B form of credit, e.g. IRREVOCABLE / IRREVOCABLE TRANSFERABLE */
    private String form;
    /** 40E applicable rules, e.g. UCP LATEST VERSION */
    private String rules;
    private Boolean transferable;
    @Column(length = 4)
    private String currency;
    @Column(precision = 20, scale = 3)
    private BigDecimal originalAmount;
    /** current credit amount (32B, after amendments) */
    @Column(precision = 20, scale = 3)
    private BigDecimal amount;
    /** 39A tolerance plus / minus in percent */
    private Integer tolerancePlus;
    private Integer toleranceMinus;
    /** utilised = honoured drawings (paid / accepted / negotiated / discharged) */
    @Column(precision = 20, scale = 3)
    private BigDecimal utilised;
    @Column(precision = 20, scale = 3)
    private BigDecimal transferredAmount;
    private LocalDate issueDate;
    private LocalDate expiryDate;
    private String expiryPlace;
    /** 41a: available with (BIC or name) / by (PAYMENT, ACCEPTANCE, NEGOTIATION, DEF PAYMENT, MIXED PYMT) */
    private String availableWith;
    private String availableBy;
    private Boolean availableWithAnyBank;
    /** 43P / 43T: ALLOWED / NOT ALLOWED / CONDITIONAL */
    private String partialShipments;
    private String transhipment;
    private LocalDate latestShipment;
    /** 48 period for presentation in days */
    private Integer presentationDays;
    /** 49: CONFIRM / MAY ADD / WITHOUT */
    private String confirmationInstructions;
    private Boolean confirmed;
    @Column(length = 600)
    private String applicant;
    @Column(length = 600)
    private String beneficiary;
    private String issuerBic;
    // ---- reimbursement (MT740 / MT747 / MT742)
    private String reimbursingBic;
    @Column(precision = 20, scale = 3)
    private BigDecimal reimbAmount;
    @Column(precision = 20, scale = 3)
    private BigDecimal reimbClaimed;
    private LocalDate reimbExpiry;
    private String milestone;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private Long originMessageId;
    private Long lastMessageId;
}
