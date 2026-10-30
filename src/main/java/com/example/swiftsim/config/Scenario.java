package com.example.swiftsim.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class Scenario {
    private String id;
    private String name;
    private String description;
    /** Scenarios in the same group are mutually exclusive; one is "active" per group. */
    private String group;
    @JsonProperty("default")
    private boolean defaultActive;
    /** Used only when no other scenario matched. */
    private boolean fallback;
    private List<Reaction> reactions = new ArrayList<>();
    /** Messages a virtual bank sends on its own when the scenario is triggered (UI button / API). */
    private List<Step> kickoff = new ArrayList<>();

    @Data
    public static class Reaction {
        /** e.g. MT760, tsrv.001, or * */
        private List<String> messageTypes = new ArrayList<>();
        /** participant ids that received the inbound message; empty = any virtual bank */
        private List<String> to = new ArrayList<>();
        /** var -> expected value ("~regex" supported) */
        private Map<String, String> when = new LinkedHashMap<>();
        private List<Step> respond = new ArrayList<>();
    }

    @Data
    public static class Step {
        private double delaySeconds;
        /** participant id, or $receiver (the bank that got the inbound msg) / $sender */
        private String from = "$receiver";
        private String to = "$sender";
        /** template file under config/templates used for MT inbound (or MT kickoff) */
        private String mt;
        /** template file used for MX inbound (or MX kickoff) */
        private String mx;
        private Map<String, String> vars = new LinkedHashMap<>();
        private int repeat = 1;
        /** mark copies after the first as possible duplicates (MT: {5:{PDE:}}, MX: AppHdr/PssblDplct) */
        private boolean possibleDuplicate;
        /** free note written to the log (e.g. for timeout scenarios) */
        private String note;
        /** optional: only run this step if the variables match (same syntax as reaction.when; ledger vars ldg_* available) */
        private Map<String, String> when = new LinkedHashMap<>();
        /** optional: evaluated when the (delayed) step FIRES against the then-current ledger context (e.g. chaser only if still unanswered);
         *  not met = step SKIPPED (no message). Same syntax as when. */
        private Map<String, String> fireWhen = new LinkedHashMap<>();
        /** kickoff only: which ledger situation to pick the transaction from (e.g. DEMAND_WITHDRAWAL, LC_PRESENTATION_DISCREPANT); default = derived from the template's message type */
        private String select;
    }
}
