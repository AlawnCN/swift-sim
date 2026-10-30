package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Ledger entry for one guarantee / standby LC (keyed by undertaking number = MT :20: / MX UdrtkgId/Id). */
@Data
@Entity
@Table(name = "undertaking", uniqueConstraints = @UniqueConstraint(columnNames = "ref"))
public class Undertaking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private Long version;
    @Column(length = 64, nullable = false)
    private String ref;
    /** MT / MX (format of the issuing message) */
    private String format;
    /** 22D / tsrv Nm: DGAR, STBY, ... */
    private String formOfUndertaking;
    /** 40C / GovncRulesAndLaw RuleId: URDG, ISPR, UCPR, ... */
    private String rules;
    @Column(length = 4)
    private String currency;
    @Column(precision = 20, scale = 3)
    private BigDecimal originalAmount;
    /** current undertaking (maximum) amount after amendments */
    @Column(precision = 20, scale = 3)
    private BigDecimal amount;
    /** amount still available for demands (amount - payments - reductions) */
    @Column(precision = 20, scale = 3)
    private BigDecimal outstanding;
    private LocalDate issueDate;
    /** FIXD / COND / OPEN (23B / XpryTerms) */
    private String expiryType;
    private LocalDate expiryDate;
    @Column(length = 600)
    private String beneficiary;
    @Column(length = 600)
    private String applicant;
    private String issuerBic;
    /** last lifecycle milestone (ISSUED, ADVISED, AMENDED, PAID, ...) */
    private String milestone;
    /** effective status = milestone overlaid with pending amendment/demand */
    private String status;
    private boolean nonExtensionNotified;
    private Instant createdAt;
    private Instant updatedAt;
    private Long originMessageId;
    private Long lastMessageId;
    /** open MT759 query from a counterparty bank awaiting the SUT's answer (nullable: added after the first release) */
    private String openQueryRef;
    private String openQueryFrom;
    private Instant openQueryAt;
}
