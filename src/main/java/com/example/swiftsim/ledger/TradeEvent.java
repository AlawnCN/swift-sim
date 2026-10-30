package com.example.swiftsim.ledger;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

/** Status history / audit trail of a documentary credit (LC) or collection (COLL); ledgerId null = unlinked message. */
@Data
@Entity
@Table(name = "trade_event", indexes = {@Index(columnList = "kind,ledgerId"), @Index(columnList = "ref")})
public class TradeEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 8)
    private String kind;
    private Long ledgerId;
    @Column(length = 64)
    private String ref;
    private Instant at;
    private LocalDate businessDate;
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private Long messageId;
    private String msgType;
    private String senderBic;
    private String receiverBic;
    @Column(length = 4000)
    private String detail;
}
