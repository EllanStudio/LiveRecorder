package com.liverecorder.velocity;

import com.liverecorder.network.*;
import com.liverecorder.network.NetworkPolicy.*;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.*;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CoordinatorTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private LiveRecorderProxy coordinator;
    private NetworkPolicy policy;
    private ProxyServer proxy;
    private Player camera, target;
    private ServerConnection connection;
    private NetworkStore store;
    private final UUID cameraId = new UUID(0, 10), targetId = new UUID(0, 20);
    private static final String SECRET = "coordinator-test-key-01234567890123456789";

    private void field(String name, Object value) throws Exception {
        Field f = LiveRecorderProxy.class.getDeclaredField(name); f.setAccessible(true); f.set(coordinator, value);
    }
    private Object field(String name) throws Exception {
        Field f = LiveRecorderProxy.class.getDeclaredField(name); f.setAccessible(true); return f.get(coordinator);
    }
    private void tick() throws Exception {
        Method m = LiveRecorderProxy.class.getDeclaredMethod("tick"); m.setAccessible(true); m.invoke(coordinator);
    }
    @Before public void setup() throws Exception {
        proxy = mock(ProxyServer.class);
        camera = mock(Player.class); target = mock(Player.class); connection = mock(ServerConnection.class);
        when(camera.getUniqueId()).thenReturn(cameraId); when(camera.getUsername()).thenReturn("Camera");
        when(target.getUniqueId()).thenReturn(targetId);
        when(camera.getCurrentServer()).thenReturn(Optional.of(connection));
        when(proxy.getPlayer(cameraId)).thenReturn(Optional.of(camera));
        when(connection.getServerInfo()).thenReturn(new ServerInfo("spawn", new java.net.InetSocketAddress("127.0.0.1", 25566)));
        when(connection.sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class))).thenReturn(true);
        coordinator = new LiveRecorderProxy(proxy, mock(Logger.class), folder.getRoot().toPath());
        policy = (NetworkPolicy) field("policy");
        field("secret", SECRET); field("interval", 60000L);
        store = new NetworkStore(folder.getRoot().toPath().resolve("network.db")); field("store", store);
        policy.sessions.put(cameraId, new Session(targetId, Mode.MANUAL, true));
        policy.consent.put(targetId, Consent.ACCEPTED);
        long now = System.currentTimeMillis();
        policy.presence.put(cameraId, new Presence("spawn", "camera-nonce", now, true));
        policy.presence.put(targetId, new Presence("spawn", "target-nonce", now, true));
    }
    @After public void cleanup() { coordinator.shutdown(null); }
    private List<String> state() throws Exception {
        ArgumentCaptor<byte[]> packet = ArgumentCaptor.forClass(byte[].class);
        verify(connection).sendPluginMessage(any(ChannelIdentifier.class), packet.capture());
        return Wire.decode(SECRET, packet.getValue());
    }
    @Test public void readyLocalTargetGetsAuthenticatedState() throws Exception {
        tick(); List<String> state = state();
        assertEquals("STATE", state.get(0)); assertEquals("camera-nonce", state.get(2));
        assertEquals(targetId.toString(), state.get(4)); assertEquals(targetId.toString(), state.get(6));
    }
    @Test public void revokedTargetProducesEmptyWaitingState() throws Exception {
        policy.consent.put(targetId, Consent.DECLINED); tick();
        List<String> state = state(); assertEquals("", state.get(4)); assertEquals(6, state.size());
    }
    @Test public void staleCameraCannotReceiveFollowState() throws Exception {
        policy.presence.put(cameraId, new Presence("spawn", "n", 1, true)); tick();
        verify(connection, never()).sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class));
    }
    @Test public void remoteTargetPausesBeforeTransferAndRetriesAreThrottled() throws Exception {
        policy.presence.put(targetId, new Presence("redstone", "target-nonce", System.currentTimeMillis(), true));
        RegisteredServer destination = mock(RegisteredServer.class);
        when(proxy.getServer("redstone")).thenReturn(Optional.of(destination));
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.isSuccessful()).thenReturn(true);
        when(request.connect()).thenReturn(CompletableFuture.completedFuture(result));
        when(camera.createConnectionRequest(destination)).thenReturn(request);
        tick(); List<String> state = state();
        assertEquals("", state.get(4)); assertEquals(6, state.size());
        tick(); verify(camera, times(1)).createConnectionRequest(destination);
    }
    @Test public void unauthenticatedPlayerCannotChangeConsent() throws Exception {
        when(target.isActive()).thenReturn(true);
        when(target.getCurrentServer()).thenReturn(Optional.of(connection));
        policy.presence.put(targetId, new Presence("spawn", "n", System.currentTimeMillis(), false));
        Method m = LiveRecorderProxy.class.getDeclaredMethod("command", com.velocitypowered.api.command.CommandSource.class, String[].class);
        m.setAccessible(true); m.invoke(coordinator, target, new String[]{"decline"});
        assertEquals(Consent.ACCEPTED, policy.consent.get(targetId));
    }
    @Test public void clientCannotForwardControlMessages() throws Exception {
        PluginMessageEvent event = mock(PluginMessageEvent.class);
        when(event.getIdentifier()).thenReturn((ChannelIdentifier) field("channel"));
        when(event.getSource()).thenReturn(camera);
        coordinator.message(event);
        verify(event).setResult(PluginMessageEvent.ForwardResult.handled());
        verify(event, never()).getData();
    }
    @Test public void heartbeatRejectsReplaysAndOldConnectionNonces() throws Exception {
        @SuppressWarnings("unchecked") Set<String> allowedServers = (Set<String>) field("servers");
        allowedServers.add("spawn"); field("running", true);
        when(connection.getPlayer()).thenReturn(camera);
        policy.presence.remove(cameraId);
        String nonce = UUID.randomUUID().toString();
        hello(nonce, 1, true);
        assertTrue(policy.presence.get(cameraId).eligible);
        hello(nonce, 1, false);
        assertTrue(policy.presence.get(cameraId).eligible);
        hello(UUID.randomUUID().toString(), 2, false);
        assertTrue(policy.presence.get(cameraId).eligible);
        hello(nonce, 2, false);
        assertFalse(policy.presence.get(cameraId).eligible);
    }
    private void hello(String nonce, int sequence, boolean ready) throws Exception {
        PluginMessageEvent event = mock(PluginMessageEvent.class);
        when(event.getIdentifier()).thenReturn((ChannelIdentifier) field("channel"));
        when(event.getSource()).thenReturn(connection); when(event.getTarget()).thenReturn(camera);
        when(event.getData()).thenReturn(Wire.encode(SECRET, "HELLO", cameraId.toString(), nonce,
                Integer.toString(sequence), Boolean.toString(ready), "true"));
        coordinator.message(event);
        ((java.util.concurrent.ScheduledExecutorService) field("actor")).submit(() -> {}).get(3, java.util.concurrent.TimeUnit.SECONDS);
    }
}
