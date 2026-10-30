package com.example.swiftsim.gateway;

import jakarta.persistence.*;
import lombok.Data;

/** Simulated FIN logical-terminal session state per participant (session number + input/output sequence numbers). */
@Data
@Entity
@Table(name = "lt_session")
public class LtSession {
    @Id
    private String bic;
    private int sessionNo;
    private long lastIsn;
    private long lastOsn;
}
