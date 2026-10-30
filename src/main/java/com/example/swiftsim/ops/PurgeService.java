package com.example.swiftsim.ops;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Removes test data for selected business references (all ledgers + their messages, review items, scheduled responses).
 * Two-phase: a dry run returns what would be deleted and a confirmation token; the real run needs that token.
 * Protected references (sim.protected-refs, default KEGTEE2026000101) are never selected unless listed explicitly in includeProtected.
 * LT sessions (ISN/OSN) are never touched.
 */
@Service
@RequiredArgsConstructor
public class PurgeService {
    private final OpsProps props;
    private final PlatformTransactionManager txm;
    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> purge(Map<String, Object> body, boolean dryRun) {
        TransactionTemplate tx = new TransactionTemplate(txm);
        return tx.execute(s -> {
            Set<String> includeProtected = new HashSet<>(strings(body.get("includeProtected")));
            Set<String> prot = new HashSet<>(props.protectedRefs());
            prot.removeAll(includeProtected);
            // ---- business records
            List<Object[]> und = em.createQuery("select u.id, u.ref from Undertaking u").getResultList();
            List<Object[]> lcs = em.createQuery("select l.id, l.ref from DocCredit l").getResultList();
            List<Object[]> cols = em.createQuery("select c.id, c.ref, c.collectingRef from DocCollection c").getResultList();
            boolean all = Boolean.TRUE.equals(body.get("all"));
            String prefix = body.get("prefix") == null ? null : String.valueOf(body.get("prefix")).trim();
            Set<String> explicit = new LinkedHashSet<>(strings(body.get("refs")));
            if (!all && (prefix == null || prefix.isEmpty()) && explicit.isEmpty() && !Boolean.TRUE.equals(body.get("nakMessages")))
                throw new IllegalArgumentException("select refs, prefix, all=true or nakMessages=true");
            List<Long> uIds = new ArrayList<>(), lIds = new ArrayList<>(), cIds = new ArrayList<>();
            Set<String> refs = new LinkedHashSet<>(), skipped = new TreeSet<>();
            for (Object[] r : und) if (pick((String) r[1], all, prefix, explicit)) { if (prot.contains(r[1])) skipped.add((String) r[1]); else { uIds.add((Long) r[0]); refs.add((String) r[1]); } }
            for (Object[] r : lcs) if (pick((String) r[1], all, prefix, explicit)) { if (prot.contains(r[1])) skipped.add((String) r[1]); else { lIds.add((Long) r[0]); refs.add((String) r[1]); } }
            for (Object[] r : cols) if (pick((String) r[1], all, prefix, explicit)) { if (prot.contains(r[1])) skipped.add((String) r[1]); else { cIds.add((Long) r[0]); refs.add((String) r[1]); if (r[2] != null) refs.add((String) r[2]); } }
            for (String e : explicit) if (!prot.contains(e)) refs.add(e); else skipped.add(e);
            Set<String> msgRefs = new LinkedHashSet<>(refs);
            if (!lIds.isEmpty()) msgRefs.addAll(em.createQuery("select p.presentationRef from LcPresentation p where p.lcId in :ids and p.presentationRef is not null", String.class).setParameter("ids", lIds).getResultList());
            if (!uIds.isEmpty()) msgRefs.addAll(em.createQuery("select d.demandRef from Demand d where d.undertakingId in :ids and d.demandRef is not null", String.class).setParameter("ids", uIds).getResultList());
            msgRefs.removeAll(prot);
            // ---- messages
            Set<Long> msgIds = new TreeSet<>();
            if (!msgRefs.isEmpty())
                msgIds.addAll(em.createQuery("select m.id from MessageRecord m where m.txRef in :r or m.relatedRef in :r or m.undertakingRef in :r", Long.class).setParameter("r", msgRefs).getResultList());
            if (Boolean.TRUE.equals(body.get("nakMessages")))
                msgIds.addAll(em.createQuery("select m.id from MessageRecord m where m.status = 'NAK' and m.direction <> 'SYS' and m.undertakingRef is null", Long.class).getResultList());
            // never delete messages that belong to a protected reference
            if (!prot.isEmpty() && !msgIds.isEmpty())
                msgIds.removeAll(em.createQuery("select m.id from MessageRecord m where m.txRef in :p or m.relatedRef in :p or m.undertakingRef in :p", Long.class).setParameter("p", prot).getResultList());
            if (!msgIds.isEmpty()) // ACK/NAK/DLV/NOTE service messages of the selected messages
                msgIds.addAll(em.createQuery("select m.id from MessageRecord m where m.direction = 'SYS' and m.inReplyTo in :ids", Long.class).setParameter("ids", msgIds).getResultList());
            List<Long> rvIds = msgIds.isEmpty() && msgRefs.isEmpty() ? List.of()
                    : em.createQuery("select r.id from ReviewItem r where r.messageId in :ids or r.txRef in :refs", Long.class)
                    .setParameter("ids", msgIds.isEmpty() ? List.of(-1L) : msgIds).setParameter("refs", msgRefs.isEmpty() ? List.of("\u0000") : msgRefs).getResultList();
            List<Long> prIds = msgRefs.isEmpty() ? List.of() : em.createQuery("select p.id from PendingResponse p where p.ref in :refs", Long.class).setParameter("refs", msgRefs).getResultList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dryRun", dryRun);
            out.put("refs", refs);
            out.put("undertakings", uIds.size());
            out.put("documentaryCredits", lIds.size());
            out.put("collections", cIds.size());
            out.put("messages", msgIds.size());
            out.put("reviewItems", rvIds.size());
            out.put("scheduledResponses", prIds.size());
            out.put("protectedSkipped", skipped);
            out.put("sessionsTouched", false);
            String token = "PURGE-" + String.format("%04d", Math.floorMod(Objects.hash(refs, msgIds, rvIds, prIds), 10000));
            if (dryRun) {
                out.put("confirmToken", token);
                out.put("hint", "repeat with dryRun=false and confirm=" + token);
                s.setRollbackOnly();
                return out;
            }
            if (!token.equals(String.valueOf(body.get("confirm")))) throw new IllegalArgumentException("confirmation token mismatch - run a dry run first and pass its confirmToken (selection may have changed)");
            int n = 0;
            if (!uIds.isEmpty()) {
                for (String ent : List.of("UndertakingParty", "Amendment", "Demand", "Movement", "LedgerEvent"))
                    n += em.createQuery("delete from " + ent + " x where x.undertakingId in :ids").setParameter("ids", uIds).executeUpdate();
                n += em.createQuery("delete from Undertaking x where x.id in :ids").setParameter("ids", uIds).executeUpdate();
            }
            if (!refs.isEmpty()) {
                n += em.createQuery("delete from LedgerEvent x where x.undertakingId is null and x.ref in :r").setParameter("r", refs).executeUpdate();
                n += em.createQuery("delete from TradeEvent x where x.ledgerId is null and x.ref in :r").setParameter("r", refs).executeUpdate();
            }
            if (!lIds.isEmpty()) {
                n += em.createQuery("delete from LcAmendment x where x.lcId in :ids").setParameter("ids", lIds).executeUpdate();
                n += em.createQuery("delete from LcPresentation x where x.lcId in :ids").setParameter("ids", lIds).executeUpdate();
                for (String ent : List.of("TradeParty", "TradeMovement", "TradeEvent"))
                    n += em.createQuery("delete from " + ent + " x where x.kind = 'LC' and x.ledgerId in :ids").setParameter("ids", lIds).executeUpdate();
                n += em.createQuery("delete from DocCredit x where x.id in :ids").setParameter("ids", lIds).executeUpdate();
            }
            if (!cIds.isEmpty()) {
                for (String ent : List.of("TradeParty", "TradeMovement", "TradeEvent"))
                    n += em.createQuery("delete from " + ent + " x where x.kind = 'COLL' and x.ledgerId in :ids").setParameter("ids", cIds).executeUpdate();
                n += em.createQuery("delete from DocCollection x where x.id in :ids").setParameter("ids", cIds).executeUpdate();
            }
            if (!rvIds.isEmpty()) n += em.createQuery("delete from ReviewItem x where x.id in :ids").setParameter("ids", rvIds).executeUpdate();
            if (!prIds.isEmpty()) n += em.createQuery("delete from PendingResponse x where x.id in :ids").setParameter("ids", prIds).executeUpdate();
            if (!msgIds.isEmpty()) n += em.createQuery("delete from MessageRecord x where x.id in :ids").setParameter("ids", msgIds).executeUpdate();
            out.put("rowsDeleted", n);
            return out;
        });
    }

    private static boolean pick(String ref, boolean all, String prefix, Set<String> explicit) {
        if (ref == null) return false;
        return all || (prefix != null && !prefix.isEmpty() && ref.startsWith(prefix)) || explicit.contains(ref);
    }

    private static List<String> strings(Object o) {
        if (o == null) return List.of();
        if (o instanceof Collection<?> c) return c.stream().map(String::valueOf).map(String::trim).filter(x -> !x.isEmpty()).toList();
        return Arrays.stream(String.valueOf(o).split("[,\\s]+")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
