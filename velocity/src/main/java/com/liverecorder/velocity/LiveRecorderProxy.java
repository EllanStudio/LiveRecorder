package com.liverecorder.velocity;

import com.google.inject.Inject;
import com.liverecorder.network.*;
import com.liverecorder.network.NetworkPolicy.*;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Plugin(id="liverecorder-network", name="LiveRecorder Network", version="1.1.0-ellan", authors={"七月个人制作组", "EllanStudio"})
public final class LiveRecorderProxy {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path folder;
    private final ScheduledExecutorService actor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "LiveRecorder-coordinator"); t.setDaemon(true); return t;
    });
    private final NetworkPolicy policy = new NetworkPolicy();
    private final Set<String> servers = new HashSet<>();
    private final Map<UUID, Long> attempts = new HashMap<>(), incomingSequence = new HashMap<>();
    private final Random random = new Random();
    private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(Wire.CHANNEL);
    private NetworkStore store;
    private String secret;
    private long sequence, interval;
    private boolean sequential;
    private volatile boolean running;
    private volatile Set<UUID> configuredRecorders = Collections.emptySet();

    @Inject public LiveRecorderProxy(ProxyServer proxy, Logger logger, @DataDirectory Path folder) {
        this.proxy = proxy; this.logger = logger; this.folder = folder;
    }
    @Subscribe public void init(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(folder);
            Path config = folder.resolve("network.properties");
            if (!Files.exists(config)) {
                Properties defaults = new Properties();
                defaults.setProperty("secret", UUID.randomUUID().toString() + UUID.randomUUID());
                defaults.setProperty("servers", "spawn,survival,redstone");
                defaults.setProperty("recorders", "");
                defaults.setProperty("switch-seconds", "60");
                defaults.setProperty("rotation", "RANDOM");
                try (OutputStream out = Files.newOutputStream(config)) { defaults.store(out, "See NETWORK.md. Dedicated camera UUIDs only."); }
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(config)) { p.load(in); }
            secret = p.getProperty("secret", "");
            Wire.encode(secret, "CHECK");
            for (String name : p.getProperty("servers", "").split(",")) if (!name.trim().isEmpty()) servers.add(name.trim());
            if (servers.isEmpty()) throw new IllegalArgumentException("servers must not be empty");
            interval = Math.max(10, Long.parseLong(p.getProperty("switch-seconds", "60"))) * 1000;
            sequential = p.getProperty("rotation", "RANDOM").equalsIgnoreCase("SEQUENTIAL");
            store = new NetworkStore(folder.resolve("network.db"));
            store.load(policy);
            Set<UUID> recorders = new HashSet<>();
            for (String id : p.getProperty("recorders", "").split(",")) if (!id.trim().isEmpty()) recorders.add(UUID.fromString(id.trim()));
            policy.sessions.keySet().retainAll(recorders);
            for (UUID id : recorders) policy.sessions.putIfAbsent(id, new Session(null, Mode.AUTO, false));
            configuredRecorders = Collections.unmodifiableSet(new HashSet<>(recorders));
            proxy.getChannelRegistrar().register(channel);
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("liverecorder").aliases("lr").plugin(this).build(), new Commands());
            running = true;
            actor.scheduleAtFixedRate(() -> guarded(this::tick), 1, 1, TimeUnit.SECONDS);
            logger.info("LiveRecorder network ready: {} camera(s), servers {}", recorders.size(), servers);
        } catch (Exception e) { logger.error("LiveRecorder network disabled: configuration/storage failure", e); }
    }
    private void submit(Runnable action) { if (running) actor.execute(() -> guarded(action)); }
    private void guarded(Runnable action) {
        try { action.run(); } catch (Exception e) { logger.error("LiveRecorder coordinator operation failed", e); }
    }
    @Subscribe public void message(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(channel)) return;
        // Consume before checking the source, so clients cannot forward forged control messages.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection) || !(event.getTarget() instanceof Player player)) return;
        if (!servers.contains(connection.getServerInfo().getName()) || !connection.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
        byte[] data = event.getData().clone();
        submit(() -> {
            try {
                if (player.getCurrentServer().orElse(null) != connection) return;
                List<String> f = Wire.decode(secret, data);
                if (f.size() != 6 || !f.get(0).equals("HELLO") || !f.get(1).equals(player.getUniqueId().toString())) return;
                UUID.fromString(f.get(2));
                long seq = Long.parseLong(f.get(3));
                boolean ready = Boolean.parseBoolean(f.get(4));
                boolean camera = Boolean.parseBoolean(f.get(5));
                UUID id = player.getUniqueId();
                Presence old = policy.presence.get(id);
                if (old != null && !old.nonce.equals(f.get(2))) return;
                if (seq <= incomingSequence.getOrDefault(id, -1L)) return;
                incomingSequence.put(id, seq);
                if (camera != policy.sessions.containsKey(id)) {
                    policy.presence.remove(id);
                    return;
                }
                policy.presence.put(id, new Presence(connection.getServerInfo().getName(), f.get(2), System.currentTimeMillis(), ready));
            } catch (Exception ignored) { /* Invalid packets never grant consent or start cameras. */ }
        });
    }
    @Subscribe public void connected(ServerConnectedEvent event) {
        submit(() -> { policy.presence.remove(event.getPlayer().getUniqueId()); incomingSequence.remove(event.getPlayer().getUniqueId()); tick(); });
    }
    @Subscribe(order=com.velocitypowered.api.event.PostOrder.LAST)
    public void preConnect(ServerPreConnectEvent event) {
        if (!configuredRecorders.contains(event.getPlayer().getUniqueId())) return;
        event.getResult().getServer().ifPresent(destination -> {
            if (!servers.contains(destination.getServerInfo().getName())) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                reply(event.getPlayer(), "专用直播号只能进入已配置录制组件的服务器。");
            }
        });
    }
    @Subscribe public void disconnected(DisconnectEvent event) {
        submit(() -> { UUID id = event.getPlayer().getUniqueId(); policy.presence.remove(id); incomingSequence.remove(id); attempts.remove(id); tick(); });
    }
    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> entry : policy.sessions.entrySet()) {
            Player recorder = proxy.getPlayer(entry.getKey()).orElse(null);
            if (recorder == null) continue;
            Session session = entry.getValue();
            UUID old = session.target;
            UUID targetId = policy.select(session, now, interval, random, sequential);
            if (!Objects.equals(old, session.target)) persist(entry.getKey(), session);
            if (!session.enabled) targetId = null;
            Presence camera = policy.presence.get(recorder.getUniqueId());
            if (camera == null || !camera.eligible || now - camera.time > NetworkPolicy.LEASE_MS) continue;
            Presence target = policy.presence.get(targetId);
            ServerConnection connection = recorder.getCurrentServer().orElse(null);
            if (connection == null || !connection.getServerInfo().getName().equals(camera.server)) continue;
            UUID localTarget = target != null && target.server.equals(camera.server) ? targetId : null;
            List<String> fields = new ArrayList<>(Arrays.asList("STATE", recorder.getUniqueId().toString(), camera.nonce,
                    Long.toString(++sequence), localTarget == null ? "" : localTarget.toString(), session.mode.name()));
            for (UUID allowed : localTarget == null ? Collections.<UUID>emptyList() : policy.candidates(now)) {
                if (policy.presence.get(allowed).server.equals(camera.server)) fields.add(allowed.toString());
            }
            try { connection.sendPluginMessage(channel, Wire.encode(secret, fields.toArray(new String[0]))); }
            catch (IOException e) { logger.warn("Cannot send camera state: {}", e.getMessage()); continue; }
            if (target != null && !target.server.equals(camera.server) && now - attempts.getOrDefault(entry.getKey(), 0L) > 10000) {
                attempts.put(entry.getKey(), now);
                proxy.getServer(target.server).ifPresent(destination -> recorder.createConnectionRequest(destination).connect().whenComplete((result, error) -> {
                    if (error != null || !result.isSuccessful()) logger.warn("Camera transfer failed for {} to {}; retry in 10 seconds", recorder.getUsername(), target.server);
                }));
            }
        }
    }
    private void persist(UUID id, Session session) {
        try { store.session(id, session); } catch (Exception e) { session.enabled = false; logger.error("Session save failed; camera paused", e); }
    }
    private void reply(CommandSource sender, String text) { sender.sendMessage(Component.text("[艾尔岚直播] " + text)); }
    private String name(UUID id) { return id == null ? "等待目标" : proxy.getPlayer(id).map(Player::getUsername).orElse(id.toString()); }
    private final class Commands implements SimpleCommand {
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments().clone();
            CommandSource sender = invocation.source();
            submit(() -> command(sender, args));
        }
        public List<String> suggest(Invocation invocation) {
            if (invocation.arguments().length <= 1) return Arrays.asList("accept", "decline", "privacy", "setprivacy", "bind", "unbind", "switch", "mode", "list", "logs");
            if (invocation.source().hasPermission("liverecorder.admin")) {
                List<String> names = new ArrayList<>(); proxy.getAllPlayers().forEach(p -> names.add(p.getUsername())); return names;
            }
            return Collections.emptyList();
        }
    }
    private void command(CommandSource sender, String[] args) {
        try {
            if (sender instanceof Player player) {
                Presence present = policy.presence.get(player.getUniqueId());
                ServerConnection connection = player.getCurrentServer().orElse(null);
                if (!player.isActive() || present == null || !present.eligible
                        || System.currentTimeMillis() - present.time > NetworkPolicy.LEASE_MS
                        || connection == null || !connection.getServerInfo().getName().equals(present.server)) {
                    reply(sender, "请先完成登录验证，并等待服务器同步完成后再使用直播命令。");
                    return;
                }
            }
            if (args.length == 0) { reply(sender, "/lr accept | decline | privacy | setprivacy accepted/declined/unset；管理: bind/unbind/switch/mode/list/logs"); return; }
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (Arrays.asList("accept", "decline", "privacy", "setprivacy").contains(sub)) {
                if (!(sender instanceof Player player)) { reply(sender, "请在游戏内设置自己的隐私。"); return; }
                UUID id = player.getUniqueId();
                if (sub.equals("privacy")) { reply(sender, "全服直播授权: " + policy.consent.getOrDefault(id, Consent.UNSET)); return; }
                Consent value = sub.equals("accept") ? Consent.ACCEPTED : sub.equals("decline") ? Consent.DECLINED
                        : Consent.valueOf(args.length > 1 ? args[1].toUpperCase(Locale.ROOT) : "UNSET");
                // Revocations take effect in memory even if disk writes fail; never acknowledge an unsaved acceptance.
                if (value != Consent.ACCEPTED) policy.consent.put(id, value);
                store.consent(id, value);
                policy.consent.put(id, value);
                store.log("CONSENT " + id + " " + value);
                tick();
                reply(sender, "已保存全服直播授权: " + value + "。所有子服共享。");
                return;
            }
            if (!sender.hasPermission("liverecorder.admin")) { reply(sender, "没有管理员权限。"); return; }
            if (sub.equals("logs")) { for (String line : store.logs()) reply(sender, line); return; }
            if (sub.equals("list")) {
                for (Map.Entry<UUID, Session> e : policy.sessions.entrySet()) reply(sender, name(e.getKey()) + " -> " + name(e.getValue().target) + " " + e.getValue().mode + (e.getValue().enabled ? " 启用" : "暂停"));
                return;
            }
            if (sub.equals("reload")) { reply(sender, "代理的服务器列表、密钥和录制账号需重启代理生效；后端控制台可执行 lr reload。"); return; }
            if (args.length < 2) { reply(sender, "用法: /lr " + sub + " <录制者> [目标/模式]"); return; }
            Player recorder = proxy.getPlayer(args[1]).orElse(null);
            UUID recorderId = recorder != null ? recorder.getUniqueId() : UUID.fromString(args[1]);
            Session session = policy.sessions.get(recorderId);
            if (session == null) { reply(sender, "请先把专用直播号 UUID 加入代理及三服的录制账号名单。"); return; }
            if (sub.equals("unbind")) { session.enabled = false; session.target = null; }
            else if (sub.equals("mode") && args.length >= 3) {
                session.mode = Mode.valueOf(args[2].toUpperCase(Locale.ROOT)); session.enabled = true; session.switchedAt = 0; session.unavailableSince = -1;
            } else if ((sub.equals("bind") || sub.equals("switch")) && args.length >= 3) {
                Player target = proxy.getPlayer(args[2]).orElse(null);
                if (target == null || target.getUniqueId().equals(recorderId) || policy.sessions.containsKey(target.getUniqueId())) { reply(sender, "目标必须是全服在线的普通玩家。"); return; }
                if (policy.consent.getOrDefault(target.getUniqueId(), Consent.UNSET) != Consent.ACCEPTED) {
                    reply(sender, "目标尚未同意直播，未开始跟拍。请对方使用 /lr accept 授权后重试。");
                    reply(target, "管理员请求直播你的游戏画面。/lr accept 全服同意，/lr decline 全服拒绝。"); return;
                }
                session.mode = args.length > 3 ? Mode.valueOf(args[3].toUpperCase(Locale.ROOT)) : sub.equals("bind") ? Mode.AUTO : session.mode;
                session.target = target.getUniqueId(); session.enabled = true; session.switchedAt = System.currentTimeMillis(); session.unavailableSince = -1;
            } else { reply(sender, "未知命令或参数不全。"); return; }
            persist(recorderId, session);
            store.log(sub.toUpperCase(Locale.ROOT) + " " + recorderId + " -> " + session.target + " " + session.mode);
            tick(); reply(sender, "已更新: " + name(recorderId) + " -> " + name(session.target) + (session.enabled ? "（等待后端就绪后跟拍）" : "（已暂停）"));
        } catch (IllegalArgumentException e) { reply(sender, "参数无效。模式: auto/manual/spectator；隐私: accepted/declined/unset。"); }
        catch (Exception e) { logger.error("Network command failed", e); reply(sender, "保存失败，本次操作未确认，请联系管理员。"); tick(); }
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        running = false;
        actor.shutdown();
        try { if (!actor.awaitTermination(5, TimeUnit.SECONDS)) actor.shutdownNow(); if (store != null) store.close(); }
        catch (Exception e) { logger.warn("Network shutdown", e); }
    }
}
