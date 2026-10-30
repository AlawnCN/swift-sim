package com.example.swiftsim.ledger;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Simulated business date (defaults to the real date). Overridable via /api/admin/business-date to test expiry. In memory only. */
@Component
public class BusinessClock {
    private volatile LocalDate override;

    public LocalDate today() {
        return override != null ? override : LocalDate.now();
    }

    public LocalDate getOverride() {
        return override;
    }

    public void set(LocalDate d) {
        this.override = d;
    }
}
