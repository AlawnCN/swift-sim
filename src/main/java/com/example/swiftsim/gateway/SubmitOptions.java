package com.example.swiftsim.gateway;

import lombok.Builder;
import lombok.Data;

@Data
@Builder(toBuilder = true)
public class SubmitOptions {
    @Builder.Default
    private String channel = "REST";
    /** sender override (participant id or BIC) – used for MX without AppHdr, or to wrap a bare MT block 4 */
    private String from;
    private String to;
    /** MT type used when wrapping a bare block-4 text, e.g. "760" */
    private String mtType;
    /** force a scenario for the reactions to this message */
    private String scenario;
    /** scenario that produced this message (for logging) */
    private String producedBy;
    private Long inReplyTo;
    private int depth;
    /** message is a simulator-generated business reject (ledger only links it, receiver does not react) */
    private boolean businessReject;
    /**
     * HTTP or FILE. Null lets {@code FileTransportService} decide: replies follow the inbound message,
     * folder drops use FILE, and simulator kickoff uses the originate setting.
     */
    private String outboundTransport;
    /** Id of the kenex {@code file_claim} row this submission came from, when channel is FOLDER. */
    private Long fileClaimId;
}
