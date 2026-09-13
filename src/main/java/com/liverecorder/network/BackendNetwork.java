package com.liverecorder.network;

import com.liverecorder.LiveRecorder;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import java.io.IOException;
import java.util.*;

/** Network camera adapter. Bukkit objects are only accessed on the server thread. */
public final class BackendNetwork implements Listener, PluginMessageListener, AutoCloseable {
    private final LiveRecorder plugin;
    private final String secret;
    private final Set<UUID> cameras = new HashSet<>();
    private final Map<UUID, Link> links = new HashMap<>();
    private BukkitTask heartbeat, follow;
    private boolean moving;
    private Object authApi;
    private java.lang.reflect.Method isAuthenticated;
    private boolean authWarning;
    private final Set<UUID> hiddenChat = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final class Link {
        final String nonce = UUID.randomUUID().toString();
        final long joined = System.currentTimeMillis();
        long sent, receivedSequence = -1, receivedAt;
        UUID target;
        Set<UUID> allowed = Collections.emptySet();
        String mode = "AUTO";
        boolean waiting;
    }
    public BackendNetwork(LiveRecorder plugin) throws IOException {
        this.plugin = plugin;
        secret = plugin.getConfig().getString("network.secret", "");
        Wire.encode(secret, "CHECK");
        for (String id : plugin.getConfig().getStringList("network.recorder-uuids")) cameras.add(UUID.fromString(id));
        if (cameras.isEmpty()) throw new IOException("network.recorder-uuids must list dedicated camera UUIDs");
    }
    public void start() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, Wire.CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, Wire.CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) joined(player);
        plugin.getCommand("liverecorder").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player) && args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                plugin.reloadConfig(); plugin.getCameraGeometry().reload();
                sender.sendMessage("LiveRecorder camera settings reloaded; network secret/UUID list require restart.");
            } else sender.sendMessage("[艾尔岚直播] 群组命令由 Velocity 处理；请检查代理组件是否启用。");
            return true;
        });
        heartbeat = Bukkit.getScheduler().runTaskTimer(plugin, this::heartbeat, 1, 20);
        follow = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        plugin.getLogger().info("Velocity network camera adapter enabled");
    }
    private boolean authenticated(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("AuthMe")) return true;
        try {
            if (isAuthenticated == null) {
                Class<?> api = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
                authApi = api.getMethod("getInstance").invoke(null);
                isAuthenticated = api.getMethod("isAuthenticated", Player.class);
            }
            return (Boolean) isAuthenticated.invoke(authApi, player);
        } catch (ReflectiveOperationException e) {
            if (!authWarning) { plugin.getLogger().warning("Cannot verify AuthMe login; recording stays paused: " + e); authWarning = true; }
            return false;
        }
    }
    private boolean settled(Link link) {
        return link != null && System.currentTimeMillis() - link.joined >=
                Math.max(20, plugin.getConfig().getLong("network.join-delay-ticks", 100)) * 50;
    }
    private boolean available(Player player) {
        if (!player.isOnline() || player.isDead() || !authenticated(player)) return false;
        for (MetadataValue value : player.getMetadata("vanished")) if (value.asBoolean()) return false;
        return true;
    }
    private void heartbeat() {
        long now = System.currentTimeMillis();
        long delay = Math.max(20, plugin.getConfig().getLong("network.join-delay-ticks", 100)) * 50;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Link link = links.get(player.getUniqueId());
            if (link == null) continue;
            boolean ready = now - link.joined >= delay && available(player);
            if (cameras.contains(player.getUniqueId()) && waitingLocation() == null) ready = false;
            try {
                player.sendPluginMessage(plugin, Wire.CHANNEL, Wire.encode(secret, "HELLO", player.getUniqueId().toString(),
                        link.nonce, Long.toString(++link.sent), Boolean.toString(ready), Boolean.toString(cameras.contains(player.getUniqueId()))));
            } catch (IOException e) { plugin.getLogger().warning("Network heartbeat failed: " + e.getMessage()); }
        }
    }
    @Override public void onPluginMessageReceived(String channel, Player carrier, byte[] data) {
        if (!Wire.CHANNEL.equals(channel) || !cameras.contains(carrier.getUniqueId())) return;
        try {
            List<String> fields = Wire.decode(secret, data);
            Link link = links.get(carrier.getUniqueId());
            if (link == null || fields.size() < 6 || !fields.get(0).equals("STATE")
                    || !fields.get(1).equals(carrier.getUniqueId().toString()) || !fields.get(2).equals(link.nonce)) return;
            long sequence = Long.parseLong(fields.get(3));
            if (sequence <= link.receivedSequence) return;
            UUID target = fields.get(4).isEmpty() ? null : UUID.fromString(fields.get(4));
            NetworkPolicy.Mode.valueOf(fields.get(5));
            Set<UUID> allowed = new HashSet<>();
            for (int i = 6; i < fields.size(); i++) allowed.add(UUID.fromString(fields.get(i)));
            if (target != null && !allowed.contains(target)) return;
            link.target = target; link.mode = fields.get(5); link.allowed = allowed;
            link.receivedSequence = sequence; link.receivedAt = System.currentTimeMillis();
            visibility(carrier, link);
        } catch (Exception ignored) { /* Invalid or stale messages never activate a camera. */ }
    }
    private void tick() {
        for (UUID id : cameras) {
            Player camera = Bukkit.getPlayer(id);
            Link link = links.get(id);
            if (camera == null || link == null) continue;
            if (!settled(link) || !authenticated(camera)) {
                link.allowed = Collections.emptySet();
                visibility(camera, link);
                continue;
            }
            Player target = link.target == null ? null : Bukkit.getPlayer(link.target);
            boolean valid = System.currentTimeMillis() - link.receivedAt <= NetworkPolicy.LEASE_MS
                    && target != null && link.allowed.contains(target.getUniqueId()) && available(target) && available(camera);
            if (!valid) {
                link.allowed = Collections.emptySet();
                visibility(camera, link);
                park(camera, link);
                continue;
            }
            // Spectator mode prevents a dedicated camera from interacting with the filmed scene.
            if (camera.getGameMode() != GameMode.SPECTATOR) camera.setGameMode(GameMode.SPECTATOR);
            if (camera.getSpectatorTarget() != null) camera.setSpectatorTarget(null);
            Location wanted = plugin.getCameraGeometry().calculateCameraLocation(target);
            Location current = camera.getLocation();
            if (!link.waiting && current.getWorld().equals(wanted.getWorld()) && current.distanceSquared(wanted) < 900) {
                double position = Math.max(.01, Math.min(1, plugin.getConfig().getDouble("camera.position-smooth", .12)));
                double rotation = Math.max(.01, Math.min(1, plugin.getConfig().getDouble("camera.rotation-smooth", .1)));
                wanted = plugin.getCameraGeometry().calculateSmoothedState(current, wanted, target, position, rotation);
            }
            if (teleport(camera, wanted)) {
                if (link.waiting) camera.resetTitle();
                link.waiting = false;
            }
        }
    }
    private Location waitingLocation() {
        World world = Bukkit.getWorld(plugin.getConfig().getString("network.waiting-world", "world"));
        if (world == null) return null;
        return new Location(world, plugin.getConfig().getDouble("network.waiting-x", .5), plugin.getConfig().getDouble("network.waiting-y", 100), plugin.getConfig().getDouble("network.waiting-z", .5));
    }
    private void park(Player player, Link link) {
        if (!authenticated(player)) return;
        if (player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
        if (player.getSpectatorTarget() != null) player.setSpectatorTarget(null);
        if (link.waiting) return;
        Location room = waitingLocation();
        if (room == null) { player.kickPlayer("[艾尔岚直播] 未配置有效的直播等待世界，请联系管理员。"); return; }
        if (teleport(player, room)) {
            link.waiting = true;
            player.sendTitle("§6直播等待中", "§7等待授权、目标上线或跨服连接", 0, 200000, 0);
        }
    }
    private boolean teleport(Player camera, Location location) {
        moving = true;
        try { return camera.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN); }
        finally { moving = false; }
    }
    private void visibility(Player camera, Link link) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(camera)) continue;
            if (link.allowed.contains(other.getUniqueId()) && available(other)) camera.showPlayer(plugin, other);
            else camera.hidePlayer(plugin, other);
            other.hidePlayer(plugin, camera);
        }
    }
    private void joined(Player player) {
        links.put(player.getUniqueId(), new Link());
        if (cameras.contains(player.getUniqueId()) && plugin.getConfig().getBoolean("network.hide-chat", true)) hiddenChat.add(player.getUniqueId());
        for (UUID id : cameras) {
            Player camera = Bukkit.getPlayer(id);
            if (camera == null) continue;
            // A new player is hidden until their own consent and readiness have been confirmed.
            if (!camera.equals(player)) { camera.hidePlayer(plugin, player); player.hidePlayer(plugin, camera); }
            if (camera.equals(player)) visibility(camera, links.get(id));
        }
    }
    @EventHandler public void join(PlayerJoinEvent event) { joined(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId(); links.remove(id); hiddenChat.remove(id);
        for (Link link : links.values()) if (id.equals(link.target)) { link.target = null; link.allowed = Collections.emptySet(); }
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void move(PlayerMoveEvent event) {
        if (!moving && cameras.contains(event.getPlayer().getUniqueId()) && !(event instanceof PlayerTeleportEvent)) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void travel(PlayerTeleportEvent event) {
        if (!moving && cameras.contains(event.getPlayer().getUniqueId())
                && settled(links.get(event.getPlayer().getUniqueId())) && authenticated(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void interact(PlayerInteractEvent event) { if (cameras.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void interactEntity(PlayerInteractEntityEvent event) { if (cameras.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void inventory(InventoryOpenEvent event) { if (cameras.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void drop(PlayerDropItemEvent event) { if (cameras.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void attack(EntityDamageByEntityEvent event) { if (cameras.contains(event.getDamager().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void damage(EntityDamageEvent event) { if (cameras.contains(event.getEntity().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void chat(AsyncPlayerChatEvent event) { event.getRecipients().removeIf(p -> hiddenChat.contains(p.getUniqueId())); }
    public void close() {
        if (heartbeat != null) heartbeat.cancel(); if (follow != null) follow.cancel();
        for (UUID id : cameras) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.kickPlayer("[艾尔岚直播] 录制组件已停止，直播连接关闭。");
        }
        HandlerList.unregisterAll(this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, Wire.CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, Wire.CHANNEL);
    }
}
