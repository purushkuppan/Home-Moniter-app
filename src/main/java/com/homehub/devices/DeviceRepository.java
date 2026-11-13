package com.homehub.devices;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The devices table (V2__devices.sql). */
@Repository
public class DeviceRepository {

    private static final String COLUMNS = "id, ha_device_id, name, ha_name, room, manufacturer, model, kind, status, "
            + "entities, readings, in_home_assistant, first_seen, confirmed_at, open_for_minutes, alert_choices";

    private static final RowMapper<Device> ROW_MAPPER = (rs, i) -> new Device(
            rs.getLong("id"), rs.getString("ha_device_id"), rs.getString("name"), rs.getString("ha_name"),
            rs.getString("room"), rs.getString("manufacturer"), rs.getString("model"),
            DeviceKind.valueOf(rs.getString("kind")), Device.Status.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
            split(rs.getString("entities")), split(rs.getString("readings")).stream().map(Device.Reading::parse).toList(),
            rs.getInt("in_home_assistant") == 1,
            Instant.parse(rs.getString("first_seen")),
            rs.getString("confirmed_at") == null ? null : Instant.parse(rs.getString("confirmed_at")),
            rs.getObject("open_for_minutes") == null ? null : rs.getInt("open_for_minutes"),
            choices(rs.getString("alert_choices")));

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Device> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM devices ORDER BY name COLLATE NOCASE", ROW_MAPPER);
    }

    public Optional<Device> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM devices WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    public void insert(Device d) {
        jdbc.update("INSERT INTO devices (ha_device_id, name, ha_name, room, manufacturer, model, kind, status, entities, "
                        + "readings, in_home_assistant, first_seen, confirmed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                d.haDeviceId(), d.name(), d.haName(), d.room(), d.manufacturer(), d.model(), d.kind().name(),
                status(d.status()), join(d.entities()), readings(d), d.inHomeAssistant() ? 1 : 0,
                d.firstSeen().toString(), d.confirmedAt() == null ? null : d.confirmedAt().toString());
    }

    /** What Home Assistant says about the device; Kudil's name and status stay. */
    public void updateFromHomeAssistant(Device d) {
        jdbc.update("UPDATE devices SET ha_name = ?, room = ?, manufacturer = ?, model = ?, kind = ?, entities = ?, "
                        + "readings = ?, in_home_assistant = ? WHERE ha_device_id = ?",
                d.haName(), d.room(), d.manufacturer(), d.model(), d.kind().name(), join(d.entities()), readings(d),
                d.inHomeAssistant() ? 1 : 0, d.haDeviceId());
    }

    /** Doors and windows: alert when open longer than this many minutes; 0 = never; null = the default (#100). */
    public void updateOpenFor(long id, Integer minutes) {
        jdbc.update("UPDATE devices SET open_for_minutes = ? WHERE id = ?", minutes, id);
    }

    /** The household's choice: confirm (with a name), hide, or back to new. */
    public void updateChoice(long id, String name, Device.Status status, Instant confirmedAt) {
        jdbc.update("UPDATE devices SET name = ?, status = ?, confirmed_at = ? WHERE id = ?",
                name, status(status), confirmedAt == null ? null : confirmedAt.toString(), id);
    }

    private static String status(Device.Status status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    /** The household's alert choices for an appliance (#100); an empty map clears them all. */
    public void updateAlertChoices(long id, Map<String, String> choices) {
        jdbc.update("UPDATE devices SET alert_choices = ? WHERE id = ?",
                choices.isEmpty() ? null : JSON.writeValueAsString(new TreeMap<>(choices)), id);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Map<String, String> choices(String json) {
        if (json == null || json.isBlank()) return Map.of();
        Map<String, String> choices = new LinkedHashMap<>();
        JSON.readValue(json, Map.class).forEach((k, v) -> { if (k != null && v != null) choices.put(k.toString(), v.toString()); });
        return Map.copyOf(choices);
    }

    private static String readings(Device d) {
        return String.join(",", d.readings().stream().map(Device.Reading::stored).toList());
    }

    private static String join(List<String> ids) {
        return String.join(",", ids);
    }

    private static List<String> split(String ids) {
        return ids == null || ids.isBlank() ? List.of() : Arrays.stream(ids.split(",")).map(String::trim).toList();
    }
}
