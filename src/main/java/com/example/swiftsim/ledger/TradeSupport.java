package com.example.swiftsim.ledger;

import com.example.swiftsim.config.ConfigService;
import com.example.swiftsim.config.Participant;
import com.example.swiftsim.domain.MessageRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Shared persistence helpers (parties, events, movements) for the LC and collection ledgers. */
@Component
@RequiredArgsConstructor
class TradeSupport {
    final TradePartyRepository parties;
    final TradeEventRepository events;
    final TradeMovementRepository movements;
    final ConfigService cfg;
    final BusinessClock clock;

    Set<String> roles(String kind, Long id, String bic) {
        Set<String> s = new TreeSet<>();
        if (id == null || bic == null) return s;
        for (TradeParty p : parties.findByKindAndLedgerIdOrderByIdAsc(kind, id)) if (bic.equals(p.getBic())) s.add(p.getRole());
        return s;
    }

    List<String> bicsWithRole(String kind, Long id, String role) {
        List<String> l = new ArrayList<>();
        for (TradeParty p : parties.findByKindAndLedgerIdOrderByIdAsc(kind, id)) if (role.equals(p.getRole())) l.add(p.getBic());
        return l;
    }

    String partyList(String kind, Long id) {
        StringBuilder sb = new StringBuilder();
        for (TradeParty p : parties.findByKindAndLedgerIdOrderByIdAsc(kind, id)) sb.append(sb.isEmpty() ? "" : ", ").append(p.getRole()).append('=').append(p.getBic());
        return sb.toString();
    }

    TradeParty addParty(String kind, Long id, String bic, String role, String source) {
        if (bic == null) return null;
        for (TradeParty p : parties.findByKindAndLedgerIdOrderByIdAsc(kind, id))
            if (p.getBic().equals(bic) && p.getRole().equals(role)) return p;
        TradeParty p = new TradeParty();
        p.setKind(kind);
        p.setLedgerId(id);
        p.setBic(bic);
        p.setRole(role);
        p.setSource(source);
        p.setParticipantId(cfg.byBic(bic).map(Participant::getId).orElse(null));
        return parties.save(p);
    }

    boolean isParticipant(String bic) {
        return bic != null && cfg.byBic(bic).isPresent();
    }

    void event(String kind, Long id, String ref, String type, String from, String to, MessageRecord rec, String detail) {
        TradeEvent e = new TradeEvent();
        e.setKind(kind);
        e.setLedgerId(id);
        e.setRef(ref != null ? trunc(ref, 64) : rec == null ? null : rec.getTxRef());
        e.setAt(Instant.now());
        e.setBusinessDate(clock.today());
        e.setEventType(type);
        e.setFromStatus(from);
        e.setToStatus(to);
        if (rec != null) {
            e.setMessageId(rec.getId());
            e.setMsgType(rec.getMsgType());
            e.setSenderBic(rec.getSenderBic());
            e.setReceiverBic(rec.getReceiverBic());
        }
        e.setDetail(trunc(detail, 4000));
        events.save(e);
    }

    void movement(String kind, Long id, String type, String ccy, BigDecimal amt, BigDecimal after, MessageRecord rec, String itemRef, String note) {
        TradeMovement m = new TradeMovement();
        m.setKind(kind);
        m.setLedgerId(id);
        m.setType(type);
        m.setCurrency(ccy);
        m.setAmount(amt);
        m.setBalanceAfter(after);
        m.setMessageId(rec == null ? null : rec.getId());
        m.setItemRef(trunc(itemRef, 64));
        m.setNote(trunc(note, 1000));
        m.setAt(Instant.now());
        movements.save(m);
    }

    /** add n banking days (Mon-Fri, no holiday calendar) */
    static LocalDate plusBankingDays(LocalDate d, int n) {
        LocalDate x = d;
        int k = 0;
        while (k < n) {
            x = x.plusDays(1);
            if (x.getDayOfWeek() != DayOfWeek.SATURDAY && x.getDayOfWeek() != DayOfWeek.SUNDAY) k++;
        }
        return x;
    }

    static String trunc(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }

    static String nz(BigDecimal b) {
        return LedgerService.swift(b == null ? BigDecimal.ZERO : b);
    }

    static BigDecimal z(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    static String firstLine(String s) {
        return s == null ? null : s.split("\\R", 2)[0].trim();
    }

    static String flat(String s) {
        return s == null ? null : s.replaceAll("\\s*\\R\\s*", " / ").trim();
    }

    /** sim code "/CODE/" anywhere at a line start in the given fields */
    static boolean hasCode(Map<String, String> v, String code, String... tags) {
        for (String t : tags) {
            String x = v.get("f" + t);
            if (x != null && x.lines().anyMatch(l -> l.trim().startsWith("/" + code + "/"))) return true;
        }
        return false;
    }

    /** text after "/CODE/" on the line that starts with it */
    static String codeValue(String text, String code) {
        if (text == null) return null;
        for (String l : text.split("\\R")) {
            String t = l.trim();
            if (t.startsWith("/" + code + "/")) return t.substring(code.length() + 2).trim();
        }
        return null;
    }
}
