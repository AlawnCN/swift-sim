package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "undertaking_party", indexes = @Index(columnList = "undertakingId"))
public class UndertakingParty {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long undertakingId;
    private String bic;
    private String participantId;
    /** ISSUER, ADVISING, SECOND_ADVISING, BENEFICIARY, APPLICANT */
    private String role;
    /** where the relationship came from, e.g. "MT760 52A", "receiver of MT760", "admin API" */
    private String source;
}
