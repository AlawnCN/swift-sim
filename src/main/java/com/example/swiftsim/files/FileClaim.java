package com.example.swiftsim.files;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Durable claim of one payment-application outgoing file. The row is written before the bytes are
 * submitted to the gateway, and the gateway message stores {@code fileClaimId}. A restart submits
 * a CLAIMED row only when no message with that id exists.
 */
@Data
@Entity
@Table(name = "file_claim", indexes = {
        @Index(columnList = "status"),
        @Index(columnList = "sourcePath")
})
public class FileClaim {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** MT or MX (the outgoing folder the file came from). */
    @Column(length = 8)
    private String kind;
    @Column(length = 240)
    private String originalName;
    @Column(length = 1000)
    private String sourcePath;
    @Column(length = 1000)
    private String inflightPath;
    @Column(length = 1000)
    private String archivePath;
    @Column(length = 64)
    private String sha256;
    private Long sizeBytes;
    /** CLAIMED, SUBMITTED, PROCESSED, ERROR, ECHO */
    @Column(length = 16)
    private String status;
    private Long messageId;
    @Column(length = 4000)
    private String errorReason;
    private Instant createdAt;
    private Instant updatedAt;
}
