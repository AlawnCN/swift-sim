package com.example.swiftsim.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "message_log", indexes = {
        @Index(columnList = "txRef"), @Index(columnList = "receiverBic"), @Index(columnList = "senderBic"),
        @Index(columnList = "undertakingRef"), @Index(columnList = "contentHash")})
public class MessageRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant createdAt;
    private Instant updatedAt;
    /** IN = from your system (SUT) to the simulator; OUT = virtual bank -> SUT; INTERNAL = virtual -> virtual; SYS = ACK/NAK/delivery notifications/notes */
    private String direction;
    private String channel;      // REST, FILE, UI, SCENARIO, GATEWAY
    private String format;       // MT, MX, SYS
    private String msgType;      // MT760, tsrv.001.001.01, ACK, NAK, DLV, NOTE
    private String senderBic;
    private String receiverBic;
    @Column(length = 64)
    private String txRef;
    @Column(length = 64)
    private String relatedRef;
    /** RECEIVED, ACK, NAK, DELIVERED, SENT */
    private String status;
    @Column(length = 4000)
    private String statusReason;
    @Column(length = 4000)
    private String warnings;
    private String scenarioId;
    private Long inReplyTo;
    private String prowideClass;
    @Column(length = 500)
    private String webhookStatus;
    @Column(length = 400)
    private String outboxFile;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String raw;

    // ---- network (gateway) level ----
    /** Message Input Reference (MT, FIN-like): YYMMDD + sender LT(12) + session(4) + ISN(6). */
    @Column(length = 40)
    private String mir;
    /** Message Output Reference (MT, FIN-like): YYMMDD + receiver LT(12) + session(4) + OSN(6). */
    @Column(length = 40)
    private String mor;
    private Integer sessionNo;
    private Long isn;
    private Long osn;
    /** MUR (block 3 field 108) or MX BizMsgIdr. */
    @Column(length = 64)
    private String mur;
    /** MT block 3 {121:} or simulator-assigned transport UUID for MX (see README). */
    @Column(length = 40)
    private String uetr;
    /** Simulator network reference for MX (SWIFTNet-like, approximate). */
    @Column(length = 40)
    private String networkRef;
    /** PDE ({5:{PDE:}}) / AppHdr PssblDplct flag set by the sender. */
    private Boolean possibleDuplicate;
    /** id of the earlier message this PDE copy duplicates (null = no original found). */
    private Long duplicateOf;
    @Column(length = 64)
    private String contentHash;
    /** what the receiver gets: MT in output format ({2:O...}, MOR in block 1, trailers). */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String deliveredRaw;
    // ---- business (receiver application) level ----
    @Column(length = 64)
    private String undertakingRef;
    /** APPLIED | REJECTED | INCONSISTENT | DUPLICATE_IGNORED | INFO | NOT_APPLICABLE | PENDING_REVIEW */
    private String businessStatus;
    @Column(length = 4000)
    private String businessReason;
    /** persisted so that a restart can re-deliver ACKed-but-undelivered messages with the same options */
    private String scenarioOverride;
    private Integer depth;
    private Boolean businessReject;
}
