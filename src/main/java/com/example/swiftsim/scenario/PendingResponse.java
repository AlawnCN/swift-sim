package com.example.swiftsim.scenario;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** A scheduled (delayed) virtual-bank message, persisted so that it survives a restart. */
@Data
@Entity
@Table(name = "pending_response", indexes = @Index(columnList = "status"))
public class PendingResponse {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant createdAt;
    private Instant dueAt;
    private Instant firedAt;
    /** PENDING, FIRING, DONE, FAILED, CANCELLED */
    private String status;
    /** SCENARIO, KICKOFF, BUSINESS_REJECT, REVIEW */
    private String kind;
    private String scenarioId;
    private String template;
    private String fromId;
    private String toId;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String baseVarsJson;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String stepVarsJson;
    private Long inReplyTo;
    private int depth;
    private boolean possibleDuplicate;
    /** copies of one step share a group key; copy 0 renders, others reuse its raw text */
    private String groupKey;
    private int copyIndex;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String presetRaw;
    @Column(length = 64)
    private String ref;
    private Long resultMessageId;
    private String resultStatus;
    @Column(length = 2000)
    private String error;
    /** true if it was re-queued after a restart while FIRING (sent again as possible duplicate) */
    private boolean recovered;
}
