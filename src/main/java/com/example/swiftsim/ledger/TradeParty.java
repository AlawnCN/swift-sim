package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

/** Party (bank + role) of a documentary credit (kind LC) or documentary collection (kind COLL). */
@Data
@Entity
@Table(name = "trade_party", indexes = @Index(columnList = "kind,ledgerId"))
public class TradeParty {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 8)
    private String kind;
    private Long ledgerId;
    private String bic;
    private String role;
    private String participantId;
    private String source;
}
