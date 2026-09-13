package com.liverecorder.velocity;

import com.liverecorder.network.NetworkPolicy;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Used exclusively by the coordinator executor; never by a Minecraft tick thread. */
public final class NetworkStore implements AutoCloseable {
    private final Connection connection;
    public NetworkStore(Path path) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS consent (uuid TEXT PRIMARY KEY, status TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS sessions (uuid TEXT PRIMARY KEY, target TEXT, mode TEXT NOT NULL, enabled INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER, message TEXT)");
        }
    }
    public void load(NetworkPolicy policy) throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM consent")) {
            while (r.next()) policy.consent.put(UUID.fromString(r.getString("uuid")), NetworkPolicy.Consent.valueOf(r.getString("status")));
        }
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM sessions")) {
            while (r.next()) {
                String target = r.getString("target");
                policy.sessions.put(UUID.fromString(r.getString("uuid")), new NetworkPolicy.Session(target == null ? null : UUID.fromString(target), NetworkPolicy.Mode.valueOf(r.getString("mode")), r.getBoolean("enabled")));
            }
        }
    }
    public void consent(UUID id, NetworkPolicy.Consent status) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("INSERT OR REPLACE INTO consent VALUES (?,?)")) {
            s.setString(1, id.toString()); s.setString(2, status.name()); s.executeUpdate();
        }
    }
    public void session(UUID id, NetworkPolicy.Session session) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("INSERT OR REPLACE INTO sessions VALUES (?,?,?,?)")) {
            s.setString(1, id.toString()); s.setString(2, session.target == null ? null : session.target.toString());
            s.setString(3, session.mode.name()); s.setBoolean(4, session.enabled); s.executeUpdate();
        }
    }
    public void log(String message) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("INSERT INTO audit(time,message) VALUES (?,?)")) {
            s.setLong(1, System.currentTimeMillis()); s.setString(2, message); s.executeUpdate();
        }
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("DELETE FROM audit WHERE id < (SELECT COALESCE(MAX(id),0)-10000 FROM audit)");
        }
    }
    public List<String> logs() throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT time,message FROM audit ORDER BY id DESC LIMIT 10")) {
            while (r.next()) lines.add(new java.util.Date(r.getLong(1)) + " " + r.getString(2));
        }
        return lines;
    }
    public void close() throws SQLException { connection.close(); }
}
