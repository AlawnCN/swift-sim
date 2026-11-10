package com.example.swiftsim.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SimConfig {
    private Gateway gateway = new Gateway();
    private Ledger ledger = new Ledger();
    private List<Participant> participants = new ArrayList<>();

    @Data
    public static class Gateway {
        /** Delay between ACK and simulated delivery to the receiver. */
        private long deliveryDelayMs = 800;
        /** Used for MX without AppHdr (and no from/to query params). */
        private String defaultMxSender;
        private String defaultMxReceiver;
        /** Max scenario chain depth (virtual bank -> virtual bank). */
        private int maxDepth = 5;
        /** NAK (instead of warn) when the sender supplies a non-zero ISN in block 1 that is not the expected next one. */
        private boolean enforceIsn = false;
        /** NAK MX messages that have no AppHdr (FINplus-like strictness). Default false = warn only. */
        private boolean requireAppHdr = false;
        /** Add {TNG:} trailer on output (all BICs are test BICs). Approximation. */
        private boolean testAndTrainingTrailer = true;
        /** Generate the field-format validation (Prowide validatorPattern) errors. */
        private boolean fieldFormatValidation = true;
    }

    @Data
    public static class Ledger {
        /** Reject a second amendment while one is still awaiting the beneficiary's response. */
        private boolean allowConcurrentAmendments = false;
        /** Reject a second demand while one is still under examination. */
        private boolean allowConcurrentDemands = false;
        /** Messages (other than issuance/application) whose undertaking reference is unknown get a business reject. */
        private boolean rejectUnknownReferences = true;
        /** MT759 23H codes that may refer to a not-yet-existing undertaking (pre-issuance). */
        private List<String> preIssuanceMt759Codes = new ArrayList<>(List.of("REQISSUE"));
        /** Delay before a virtual bank sends its business reject. */
        private double rejectDelaySeconds = 1;
        /** Expiry sweep interval. */
        private long expirySweepMs = 60000;
        /** Extra non-business days (ISO dates) on top of weekends. Empty keeps weekend-only banking days. */
        private List<String> holidays = new ArrayList<>();
        /** When true, fallback scenarios (generic-ack, trade-no-reply) do not run. Default false. */
        private boolean disableFallbackScenarios = false;
        /** When true, a D/A collection payment before maturity is BIZ-K14. Default false (date is recorded, early payment allowed). */
        private boolean enforceCollectionMaturity = false;
    }
}
