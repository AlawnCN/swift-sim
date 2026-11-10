package com.example.swiftsim.ledger;

import com.example.swiftsim.config.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Banking days for examination deadlines. Weekends are never business days. {@code ledger.holidays}
 * (ISO dates in participants.yml) are extra non-business days shared by every virtual bank.
 * There is no per-bank calendar; an empty list keeps the previous weekend-only behaviour.
 */
@Component
@RequiredArgsConstructor
public class BankingCalendar {
    private final ConfigService cfg;

    public boolean isBusinessDay(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        if (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY) return false;
        return !holidays().contains(d);
    }

    public LocalDate plus(LocalDate d, int n) {
        LocalDate x = d;
        int k = 0;
        while (k < n) {
            x = x.plusDays(1);
            if (isBusinessDay(x)) k++;
        }
        return x;
    }

    /** Business days strictly after {@code from} up to and including {@code to}. */
    public int between(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) if (isBusinessDay(d)) n++;
        return n;
    }

    private Set<LocalDate> holidays() {
        Set<LocalDate> s = new HashSet<>();
        for (String h : cfg.getConfig().getLedger().getHolidays()) {
            try {
                s.add(LocalDate.parse(h.trim()));
            } catch (Exception ignored) { }
        }
        return s;
    }
}
