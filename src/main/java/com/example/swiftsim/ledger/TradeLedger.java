package com.example.swiftsim.ledger;

import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.parse.ParsedMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A ledger family besides guarantees/standbys (documentary credits, collections). Called by LedgerService inside its transaction. */
public interface TradeLedger {
    /** LC or COLL */
    String family();

    /** message types handled exclusively by this ledger (e.g. MT700) */
    boolean owns(String shortType);

    /** full ownership test (e.g. MT499 carrying a /COLLINST/ collection instruction) */
    default boolean ownsMessage(ParsedMessage pm) { return owns(pm.getShortType()); }

    /** for shared types (MT759 / MT799 / MT202 / MT103): does the message reference a record of this ledger? */
    boolean references(ParsedMessage pm);

    LedgerService.Outcome process(MessageRecord rec, ParsedMessage pm, boolean receiverIsSut, boolean businessReject);

    boolean exists(String ref);

    /** template / condition variables describing the record (ledger memory) */
    Optional<Map<String, String>> context(String ref);

    /** kickoff support: pick a record on which {@code from} may send the given message type / select situation to {@code to} */
    Optional<Map<String, String>> suggest(String selectOrType, String fromBic, String toBic);

    List<Map<String, Object>> list(String status, String party);

    Optional<Map<String, Object>> detail(String ref, boolean includeRaw);

    Object addParty(String ref, String bic, String role);

    int sweep();

    long clear();
}
