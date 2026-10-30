package com.example.swiftsim.parse;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ParsedMessage {
    private String format;     // MT | MX
    /** MT760 / tsrv.001.001.01 */
    private String msgType;
    /** MT760 / tsrv.001 (used for scenario matching) */
    private String shortType;
    private String sender;
    private String receiver;
    private String txRef;
    private String relatedRef;
    private String prowideClass;
    private String normalizedRaw;
    private final Map<String, String> vars = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public boolean valid() {
        return errors.isEmpty();
    }
}
