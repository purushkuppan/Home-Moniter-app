package com.homehub.events;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";
    private static final RowMapper<Event> ROW_MAPPER = (rs, i) -> new Event(
            rs.getLong("id"), rs.getString("device"), rs.getString("type"),
            rs.getString("value"), Instant.parse(rs.getString("time")));

    private final JdbcTemplate jdbc;
    private final String datasourceUrl;

    public EventRepository(JdbcTemplate jdbc, @Value("${spring.datasource.url}") String datasourceUrl) {
        this.jdbc = jdbc;
        this.datasourceUrl = datasourceUrl;
    }

    @PostConstruct
    void init() throws IOException {
        createDatabaseFolder();
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS events (
                    id     INTEGER PRIMARY KEY AUTOINCREMENT,
                    device TEXT NOT NULL,
                    type   TEXT NOT NULL,
                    value  TEXT,
                    time   TEXT NOT NULL
                )""");
    }

    /** SQLite creates the file but not its folder, so create the folder named in the datasource URL. */
    private void createDatabaseFolder() throws IOException {
        if (!datasourceUrl.startsWith(SQLITE_PREFIX)) return;
        String file = datasourceUrl.substring(SQLITE_PREFIX.length());
        int query = file.indexOf('?');
        if (query >= 0) file = file.substring(0, query);
        if (file.isBlank() || file.startsWith(":memory:") || file.startsWith("file:")) return;
        Path parent = Path.of(file).toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    /** Saves an event stamped with the current time. */
    public Event add(String device, String type, String value) {
        return save(new Event(null, device, type, value, null));
    }

    /** Saves an event, keeping its time if it has one. The id is always assigned by the database. */
    public Event save(Event event) {
        Instant time = event.time() != null ? event.time() : Instant.now();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO events (device, type, value, time) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, event.device());
            ps.setString(2, event.type());
            ps.setString(3, event.value());
            ps.setString(4, time.toString());
            return ps;
        }, keys);
        return new Event(keys.getKey().longValue(), event.device(), event.type(), event.value(), time);
    }

    public List<Event> latest(int limit) {
        return jdbc.query("SELECT id, device, type, value, time FROM events ORDER BY id DESC LIMIT ?",
                ROW_MAPPER, limit);
    }
}
