package com.example.swiftsim.review;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** Runtime per-participant settings that survive restarts (overrides participants.yml). */
@Data
@Entity
@Table(name = "participant_setting")
public class ParticipantSetting {
    @Id
    private String participantId;
    private Boolean reviewMode;
}
