package com.example.swiftsim.gateway;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SubmitResult {
    private Long id;
    private String status;       // ACK | NAK
    private String reason;
    private String format;
    private String msgType;
    private String txRef;
    private String sender;
    private String receiver;
    private String prowideClass;
    private Long ackId;
    private String ack;
    /** FIN-like Message Input Reference (MT) */
    private String mir;
    private Integer sessionNo;
    private Long isn;
    private String mur;
    private String uetr;
    private String networkRef;
    private Boolean possibleDuplicate;
    private Long duplicateOf;
    private List<String> errors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
}
