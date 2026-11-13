package com.homehub.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.homehub.HomehubApplication;
import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** The schema comes from Flyway; databases made before Flyway keep working; SQLite runs in WAL mode. */
class DatabaseMigrationTests {

    private static ConfigurableApplicationContext start(Path db) {
        return new SpringApplicationBuilder(HomehubApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=" + url(db),
                "--homehub.connectors.enabled=false",
                "--homehub.alerts.enabled=false");
    }

    private static String url(Path db) {
        return "jdbc:sqlite:" + db.toString().replace('\\', '/');
    }

    private static List<String> schemaObjects(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT type || ' ' || name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
                String.class);
    }

    @Test
    void freshDatabaseIsCreatedByFlywayAlone(@TempDir Path dir) {
        Path db = dir.resolve("new-folder/homehub.db");   // the folder doesn't exist yet either

        try (var ctx = start(db)) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

            assertThat(schemaObjects(jdbc)).containsExactly(
                    "index flyway_schema_history_s_idx",
                    "index idx_events_device_type",
                    "table devices",
                    "table events",
                    "table flyway_schema_history");
            assertThat(jdbc.queryForList("SELECT version, type, success FROM flyway_schema_history"))
                    .containsExactly(Map.of("version", "1", "type", "SQL", "success", 1),
                            Map.of("version", "2", "type", "SQL", "success", 1),    // V2: devices (#99)
                            Map.of("version", "3", "type", "SQL", "success", 1),    // V3: device alert settings (#100)
                            Map.of("version", "4", "type", "SQL", "success", 1));   // V4: appliance alert choices (#100)

            Event saved = ctx.getBean(EventRepository.class).add("Refrigerator/Fridge", "contact", "open");
            assertThat(saved.id()).isEqualTo(1);
        }
    }

    @Test
    void databaseMadeBeforeFlywayIsBaselinedWithItsEventsIntact(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("homehub.db");
        // Exactly what EventRepository created at startup before Flyway, with some history in it.
        try (Connection con = DriverManager.getConnection(url(db)); Statement st = con.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS events (
                        id     INTEGER PRIMARY KEY AUTOINCREMENT,
                        device TEXT NOT NULL,
                        type   TEXT NOT NULL,
                        value  TEXT,
                        time   TEXT NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_events_device_type ON events (device, type, id)");
            st.execute("""
                    INSERT INTO events (device, type, value, time) VALUES
                        ('Refrigerator/Fridge', 'contact', 'closed', '2026-11-01T08:00:00Z'),
                        ('Refrigerator/Fridge', 'temperature', '38 F', '2026-11-01T08:00:01Z'),
                        ('Washer', 'machineState', 'stop', '2026-11-02T19:30:00Z')""");
        }

        try (var ctx = start(db)) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            EventRepository repository = ctx.getBean(EventRepository.class);

            assertThat(jdbc.queryForList("SELECT version, type, success FROM flyway_schema_history"))
                    .containsExactly(Map.of("version", "1", "type", "BASELINE", "success", 1),
                            Map.of("version", "2", "type", "SQL", "success", 1),    // then V2 and V3 run on top
                            Map.of("version", "3", "type", "SQL", "success", 1),
                            Map.of("version", "4", "type", "SQL", "success", 1));
            assertThat(repository.latest(10)).extracting(Event::id, Event::device, Event::value).containsExactly(
                    tuple(3L, "Washer", "stop"),
                    tuple(2L, "Refrigerator/Fridge", "38 F"),
                    tuple(1L, "Refrigerator/Fridge", "closed"));
            // Still the same table: new events continue after the old ids, repeats are still skipped.
            assertThat(repository.saveIfChanged(new Event(null, "Washer", "machineState", "stop", null))).isEmpty();
            assertThat(repository.add("Washer", "machineState", "run").id()).isEqualTo(4);
        }

        // The next start finds the history table and has nothing to do.
        try (var ctx = start(db)) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(4);
        }
    }

    @Test
    void sqliteRunsInWalModeWithNormalSync(@TempDir Path dir) {
        Path db = dir.resolve("homehub.db");

        try (var ctx = start(db)) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("wal");
            assertThat(jdbc.queryForObject("PRAGMA synchronous", Integer.class)).isEqualTo(1);   // 1 = NORMAL
            assertThat(Files.exists(dir.resolve("homehub.db-wal"))).isTrue();
        }
    }

    @Test
    void folderIsOnlyCreatedForSqliteFiles(@TempDir Path dir) {
        DatabaseFolder.create(url(dir.resolve("a/b/homehub.db")) + "?journal_mode=WAL");
        DatabaseFolder.create("jdbc:sqlite::memory:");
        DatabaseFolder.create("jdbc:postgresql://localhost/homehub");

        assertThat(dir.resolve("a/b")).isDirectory();
        assertThat(dir.resolve("a/b/homehub.db")).doesNotExist();
    }
}
