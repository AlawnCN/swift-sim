package com.example.swiftsim.files;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * One simulator output file destined for the payment application's incoming folder.
 * The row (including the chosen file name) is committed before the file is linked into place,
 * so a restart can finish the same name instead of writing a second copy.
 */
@Data
@Entity
@Table(name = "file_publication", indexes = @Index(columnList = "sha256"))
public class FilePublication {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private Long messageId;
    /** PENDING, PUBLISHED, ERROR */
    @Column(length = 16)
    private String status;
    @Column(length = 8)
    private String format;
    @Column(length = 32)
    private String msgType;
    @Column(length = 240)
    private String fileName;
    @Column(length = 1000)
    private String absolutePath;
    @Column(length = 64)
    private String sha256;
    @Column(length = 1000)
    private String errorReason;
    private Instant createdAt;
    private Instant updatedAt;
}
