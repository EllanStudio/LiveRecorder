package com.liverecorder.velocity;

import com.liverecorder.network.NetworkPolicy;
import com.liverecorder.network.NetworkPolicy.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.Assert.*;

public class NetworkStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test public void consentAndBindingsSurviveRestart() throws Exception {
        Path file = folder.getRoot().toPath().resolve("network.db");
        UUID player = UUID.randomUUID(), camera = UUID.randomUUID();
        try (NetworkStore store = new NetworkStore(file)) {
            store.consent(player, Consent.ACCEPTED);
            store.session(camera, new Session(player, Mode.MANUAL, true));
        }
        NetworkPolicy policy = new NetworkPolicy();
        try (NetworkStore store = new NetworkStore(file)) { store.load(policy); }
        assertEquals(Consent.ACCEPTED, policy.consent.get(player));
        assertEquals(player, policy.sessions.get(camera).target);
        assertEquals(Mode.MANUAL, policy.sessions.get(camera).mode);
        assertTrue(policy.sessions.get(camera).enabled);
        assertFalse(policy.eligible(player, System.currentTimeMillis()));
    }
    @Test public void revokeAndPauseOverwriteOldValues() throws Exception {
        UUID player = UUID.randomUUID();
        try (NetworkStore store = new NetworkStore(folder.getRoot().toPath().resolve("network.db"))) {
            store.consent(player, Consent.ACCEPTED); store.consent(player, Consent.DECLINED);
            store.session(player, new Session(UUID.randomUUID(), Mode.AUTO, true));
            store.session(player, new Session(null, Mode.AUTO, false));
            NetworkPolicy policy = new NetworkPolicy(); store.load(policy);
            assertEquals(Consent.DECLINED, policy.consent.get(player));
            assertNull(policy.sessions.get(player).target); assertFalse(policy.sessions.get(player).enabled);
        }
    }
    @Test public void auditReturnsTenLatestEntries() throws Exception {
        try (NetworkStore store = new NetworkStore(folder.getRoot().toPath().resolve("network.db"))) {
            for (int i = 0; i < 12; i++) store.log("event-" + i);
            assertEquals(10, store.logs().size()); assertTrue(store.logs().get(0).endsWith("event-11"));
        }
    }
}
