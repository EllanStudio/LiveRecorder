package com.liverecorder.network;

import java.util.*;

/** Pure scheduling policy, shared with the regression tests. All access is serialized. */
public final class NetworkPolicy {
    public enum Consent { UNSET, ACCEPTED, DECLINED }
    public enum Mode { AUTO, MANUAL, SPECTATOR }
    public static final long LEASE_MS = 4000;
    public static final long TRANSFER_GRACE_MS = 15000;
    public static final class Presence {
        public final String server, nonce;
        public final long time;
        public final boolean eligible;
        public Presence(String server, String nonce, long time, boolean eligible) {
            this.server = server; this.nonce = nonce; this.time = time; this.eligible = eligible;
        }
    }
    public static final class Session {
        public UUID target;
        public Mode mode;
        public long switchedAt;
        public long unavailableSince = -1;
        public boolean enabled;
        public Session(UUID target, Mode mode, boolean enabled) {
            this.target = target; this.mode = mode; this.enabled = enabled;
        }
    }
    public final Map<UUID, Consent> consent = new HashMap<>();
    public final Map<UUID, Presence> presence = new HashMap<>();
    public final Map<UUID, Session> sessions = new LinkedHashMap<>();

    public boolean eligible(UUID id, long now) {
        Presence p = presence.get(id);
        return !sessions.containsKey(id) && consent.get(id) == Consent.ACCEPTED
                && p != null && p.eligible && now - p.time <= LEASE_MS;
    }

    public List<UUID> candidates(long now) {
        List<UUID> list = new ArrayList<>();
        for (UUID id : presence.keySet()) if (eligible(id, now)) list.add(id);
        Collections.sort(list);
        return list;
    }

    public UUID select(Session session, long now, long interval, Random random, boolean sequential) {
        if (!session.enabled) return null;
        boolean valid = eligible(session.target, now);
        if (session.mode != Mode.AUTO) return valid ? session.target : null;
        if (valid) session.unavailableSince = -1;
        else if (session.target != null && consent.get(session.target) == Consent.ACCEPTED
                && !sessions.containsKey(session.target)) {
            // Keep the selected player during a normal backend transfer, without recording stale state.
            if (session.unavailableSince < 0) session.unavailableSince = now;
            if (now - session.unavailableSince < TRANSFER_GRACE_MS) return null;
        }
        if (valid && now - session.switchedAt < interval) return session.target;
        List<UUID> pool = candidates(now);
        UUID previous = session.target;
        if (pool.size() > 1) pool.remove(previous);
        if (pool.isEmpty()) return null;
        UUID next = pool.get(random.nextInt(pool.size()));
        if (sequential) {
            next = pool.get(0);
            if (previous != null) for (UUID id : pool) if (id.compareTo(previous) > 0) { next = id; break; }
        }
        session.target = next;
        session.switchedAt = now;
        session.unavailableSince = -1;
        return next;
    }
}
