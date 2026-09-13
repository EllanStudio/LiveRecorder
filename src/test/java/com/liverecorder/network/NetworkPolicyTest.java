package com.liverecorder.network;

import com.liverecorder.network.NetworkPolicy.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class NetworkPolicyTest {
    private final NetworkPolicy policy = new NetworkPolicy();
    private final UUID first = new UUID(0, 1), second = new UUID(0, 2);
    private final Random random = new Random(1);
    private void ready(UUID id, String server, long time) {
        policy.consent.put(id, Consent.ACCEPTED);
        policy.presence.put(id, new Presence(server, "nonce", time, true));
    }
    private UUID select(Session session, long now) { return policy.select(session, now, 60000, random, true); }
    @Test public void defaultAndDeclinedAreNotEligible() {
        policy.presence.put(first, new Presence("spawn", "n", 1000, true));
        assertFalse(policy.eligible(first, 1000));
        policy.consent.put(first, Consent.DECLINED); assertFalse(policy.eligible(first, 1000));
    }
    @Test public void staleOrUnreadyAreNotEligible() {
        ready(first, "spawn", 1000); assertFalse(policy.eligible(first, 5001));
        policy.presence.put(first, new Presence("spawn", "n", 6000, false)); assertFalse(policy.eligible(first, 6000));
    }
    @Test public void cameraCannotBeTarget() {
        ready(first, "spawn", 1000);
        policy.sessions.put(first, new Session(null, Mode.AUTO, false)); assertFalse(policy.eligible(first, 1000));
    }
    @Test public void consentWorksAcrossServers() {
        ready(first, "spawn", 1000); assertTrue(policy.eligible(first, 1000));
        policy.presence.put(first, new Presence("redstone", "new", 2000, true));
        assertTrue(policy.eligible(first, 2000));
    }
    @Test public void autoRotatesWithoutImmediateRepeat() {
        ready(first, "spawn", 1000); ready(second, "survival", 1000);
        Session session = new Session(null, Mode.AUTO, true);
        assertEquals(first, select(session, 1000));
        assertEquals(first, select(session, 2000));
        ready(first, "spawn", 62000); ready(second, "survival", 62000);
        assertEquals(second, select(session, 62000));
    }
    @Test public void transferPausesThenResumesSameTarget() {
        ready(first, "spawn", 1000); ready(second, "survival", 1000);
        Session session = new Session(first, Mode.AUTO, true); session.switchedAt = 1000;
        policy.presence.remove(first);
        assertNull(select(session, 2000));
        assertEquals(first, session.target);
        ready(first, "redstone", 7000);
        assertEquals(first, select(session, 7000));
    }
    @Test public void abandonedTransferEventuallyRotates() {
        ready(first, "spawn", 1000);
        Session session = new Session(first, Mode.AUTO, true);
        policy.presence.remove(first); assertNull(select(session, 2000));
        ready(second, "survival", 18000);
        assertEquals(second, select(session, 18000));
    }
    @Test public void revokeStopsFixedAndReplacesAutomaticTarget() {
        ready(first, "spawn", 1000); ready(second, "redstone", 1000);
        policy.consent.put(first, Consent.DECLINED);
        assertNull(select(new Session(first, Mode.MANUAL, true), 1000));
        assertEquals(second, select(new Session(first, Mode.AUTO, true), 1000));
    }
    @Test public void disabledAndEmptyPoolsStayPaused() {
        assertNull(select(new Session(null, Mode.AUTO, true), 1000));
        ready(first, "spawn", 1000);
        assertNull(select(new Session(first, Mode.AUTO, false), 1000));
    }
    @Test public void manualDoesNotChangeTargetOnDisconnect() {
        ready(first, "spawn", 1000); ready(second, "redstone", 1000);
        Session session = new Session(first, Mode.MANUAL, true);
        policy.presence.remove(first); assertNull(select(session, 2000)); assertEquals(first, session.target);
        ready(first, "survival", 9000); assertEquals(first, select(session, 9000));
    }
}
