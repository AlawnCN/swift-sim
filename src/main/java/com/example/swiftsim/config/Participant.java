package com.example.swiftsim.config;

import lombok.Data;

/** A (fictional) SWIFT participant. Exactly one or more entries may be flagged {@code sut: true} = your system under test. */
@Data
public class Participant {
    private String id;
    private String name;
    /** BIC11, e.g. TSTAKEN0XXX (2nd char of location code = '0' marks a test BIC; all BICs here are fictional). */
    private String bic;
    private String role;
    private boolean sut;
    /** Optional: messages delivered TO this participant are POSTed here as JSON. */
    private String webhookUrl;
    private boolean deliveryNotifications = true;
    /** Manual / AI review mode: inbound messages are queued as PENDING_REVIEW instead of being auto-answered. Runtime toggle overrides this. */
    private boolean reviewMode;

    public String bic11() {
        return Bics.toBic11(bic);
    }
}
