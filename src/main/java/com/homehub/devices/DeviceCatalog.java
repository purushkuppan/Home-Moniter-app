package com.homehub.devices;

import static com.homehub.homeassistant.JsonValues.asMap;
import static com.homehub.homeassistant.JsonValues.firstNonBlank;
import static com.homehub.homeassistant.JsonValues.text;

import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.homeassistant.EntityDirectory;
import com.homehub.homeassistant.EntityPatterns;
import com.homehub.homeassistant.RegistrySnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The devices Kudil knows (#99), kept in step with Home Assistant's registries.
 * <ul>
 *   <li>Every Home Assistant device with entities appears (services such as Sun or the weather don't), with its room,
 *       maker, model and {@link DeviceKind}. Unknown devices are "Other", never dropped.</li>
 *   <li>A device seen for the first time is <b>new</b>: shown on the Devices list, nothing recorded and no alerts, until
 *       someone confirms it with a name, or hides it. A device whose entities match the configured include patterns
 *       (everything Kudil recorded before #99) is confirmed straight away, under its Home Assistant name, so nothing
 *       changes for it.</li>
 *   <li>For confirmed devices it tells the Home Assistant connector what to record and under which name; hidden devices
 *       are never recorded, include patterns or not.</li>
 * </ul>
 */
@Service
public class DeviceCatalog implements EntityDirectory {

    private static final Logger log = LoggerFactory.getLogger(DeviceCatalog.class);

    /** Published after a sync or a choice changed what is recorded, so the connector reads the states again. */
    public record DevicesChanged(Instant time) { }

    private final DeviceRepository repository;
    private final EventRepository events;
    private final EntityPatterns include;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    /** Lookups for the connector, rebuilt after every change; read without locking. */
    private record Index(Set<String> recorded, Set<String> hidden, Map<String, String> names, Set<String> onlyOfKind) {
        static final Index EMPTY = new Index(Set.of(), Set.of(), Map.of(), Set.of());
    }

    private volatile Index index = Index.EMPTY;

    @Autowired
    public DeviceCatalog(DeviceRepository repository, EventRepository events,
                         @Value("${homehub.connectors.homeassistant.include:}") String include,
                         ApplicationEventPublisher publisher) {
        this(repository, events, include, publisher, Clock.systemUTC());
    }

    DeviceCatalog(DeviceRepository repository, EventRepository events, String include,
                  ApplicationEventPublisher publisher, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.include = EntityPatterns.parse(include);
        this.publisher = publisher;
        this.clock = clock;
        reindex();   // what was confirmed before a restart is recorded from the first reading on
    }

    public List<Device> all() {
        return repository.all();
    }

    // ---------------------------------------------------------------- EntityDirectory (for the connector)

    @Override
    public boolean records(String entityId) {
        return index.recorded().contains(entityId);
    }

    @Override
    public boolean hidden(String entityId) {
        return index.hidden().contains(entityId);
    }

    @Override
    public Optional<String> deviceName(String entityId) {
        return Optional.ofNullable(index.names().get(entityId));
    }

    @Override
    public boolean onlyReadingOfItsKind(String entityId) {
        return index.onlyOfKind().contains(entityId);
    }

    // ---------------------------------------------------------------- sync with Home Assistant

    @Override
    public synchronized void sync(RegistrySnapshot snapshot) {
        Map<String, String> areas = new HashMap<>();
        snapshot.areas().forEach(a -> areas.put(text(a.get("area_id")), text(a.get("name"))));
        Map<String, String> classes = new HashMap<>();
        snapshot.states().forEach(s -> classes.put(text(s.get("entity_id")), text(asMap(s.get("attributes")).get("device_class"))));

        Map<String, List<DeviceKinds.EntityInfo>> byDevice = new LinkedHashMap<>();
        for (Map<String, Object> e : snapshot.entities()) {
            String deviceId = text(e.get("device_id"));
            String entityId = text(e.get("entity_id"));
            if (deviceId.isEmpty() || entityId.isEmpty() || !text(e.get("disabled_by")).isEmpty()) continue;
            byDevice.computeIfAbsent(deviceId, k -> new ArrayList<>()).add(new DeviceKinds.EntityInfo(entityId,
                    classes.getOrDefault(entityId, ""), text(e.get("entity_category")), text(e.get("platform"))));
        }

        Map<String, Device> known = new HashMap<>();
        repository.all().forEach(d -> known.put(d.haDeviceId(), d));
        boolean firstSync = known.isEmpty();
        Instant now = clock.instant();
        Set<String> seen = new HashSet<>();
        List<Device> found = new ArrayList<>();

        for (Map<String, Object> d : snapshot.devices()) {
            String haId = text(d.get("id"));
            List<DeviceKinds.EntityInfo> entities = byDevice.getOrDefault(haId, List.of());
            // Services (Sun, weather, backups) aren't things in the house; a device without entities reports nothing.
            if (haId.isEmpty() || "service".equals(text(d.get("entry_type"))) || !text(d.get("disabled_by")).isEmpty()
                    || entities.isEmpty()) {
                continue;
            }
            seen.add(haId);
            DeviceKind kind = DeviceKinds.kindOf(entities);
            String haName = firstNonBlank(text(d.get("name_by_user")), text(d.get("name")), "Unnamed device");
            String room = areas.get(text(d.get("area_id")));
            List<String> entityIds = entities.stream().map(DeviceKinds.EntityInfo::entityId).toList();
            Device fromHa = new Device(0, haId, haName, haName, room, blankToNull(text(d.get("manufacturer"))),
                    blankToNull(text(d.get("model"))), kind, Device.Status.NEW, entityIds,
                    DeviceKinds.readingsOf(kind, entities), true, now, null);

            Device existing = known.get(haId);
            if (existing != null) {
                repository.updateFromHomeAssistant(fromHa);
                continue;
            }
            // Recorded before #99 through the include patterns: confirmed as it is, so nothing changes for it.
            boolean alreadyWatched = entityIds.stream().anyMatch(include::matches);
            Device added = alreadyWatched
                    ? new Device(0, haId, haName, haName, room, fromHa.manufacturer(), fromHa.model(), kind,
                    Device.Status.CONFIRMED, entityIds, fromHa.readings(), true, now, now)
                    : fromHa;
            repository.insert(added);
            found.add(added);
        }

        // Removed from Home Assistant: kept (its history stays), no longer recorded.
        for (Device d : known.values()) {
            if (!seen.contains(d.haDeviceId()) && d.inHomeAssistant()) {
                repository.updateFromHomeAssistant(new Device(d.id(), d.haDeviceId(), d.name(), d.haName(), d.room(),
                        d.manufacturer(), d.model(), d.kind(), d.status(), d.entities(), d.readings(), false,
                        d.firstSeen(), d.confirmedAt()));
            }
        }

        // A new device to review is news (not on the very first sync, which finds everything already there).
        for (Device d : found) {
            if (firstSync || d.status() != Device.Status.NEW) continue;
            String where = d.room() == null ? "" : " in the " + d.room().toLowerCase(java.util.Locale.ROOT);
            events.save(new Event(null, "Kudil", "notice",
                    "New device found" + where + ": " + d.haName() + " (" + d.kind().label.toLowerCase(java.util.Locale.ROOT)
                            + "). Review it under Settings > Devices.", now));
        }
        long toReview = repository.all().stream().filter(d -> d.status() == Device.Status.NEW && d.inHomeAssistant()).count();
        log.info("Devices: {} from Home Assistant, {} new to review", seen.size(), toReview);
        reindex();
    }

    // ---------------------------------------------------------------- the household's choices

    /** Confirms a device under a name: from now on it is recorded and alerts. */
    public synchronized Device confirm(long id, String name) {
        Device d = find(id);
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (d.status() == Device.Status.NEW) {
            if (clean.isEmpty() || clean.length() > 40 || clean.contains("/")) {
                throw new IllegalArgumentException("Give it a name of up to 40 characters, without \"/\".");
            }
        } else {
            clean = d.name();   // a confirmed device keeps its name: its history and alert settings use it
        }
        repository.updateChoice(id, clean, Device.Status.CONFIRMED,
                d.confirmedAt() != null ? d.confirmedAt() : clock.instant());
        reindex();
        return find(id);
    }

    /** Hides a device: never recorded, also not through an include pattern. */
    public synchronized Device hide(long id) {
        Device d = find(id);
        repository.updateChoice(id, d.name(), Device.Status.HIDDEN, d.confirmedAt());
        reindex();
        return find(id);
    }

    /** Back to new, for a hidden device someone wants after all. */
    public synchronized Device unhide(long id) {
        Device d = find(id);
        if (d.status() != Device.Status.HIDDEN) return d;
        repository.updateChoice(id, d.name(), d.confirmedAt() != null ? Device.Status.CONFIRMED : Device.Status.NEW,
                d.confirmedAt());
        reindex();
        return find(id);
    }

    private Device find(long id) {
        return repository.find(id).orElseThrow(() -> new java.util.NoSuchElementException("No such device."));
    }

    /** Rebuilds the connector's lookups; tells the connector when what it records changed. */
    private void reindex() {
        Set<String> recorded = new HashSet<>(), hidden = new HashSet<>(), only = new HashSet<>();
        Map<String, String> names = new HashMap<>();
        for (Device d : repository.all()) {
            if (d.status() == Device.Status.HIDDEN) {
                hidden.addAll(d.entities());
                continue;
            }
            d.entities().forEach(id -> names.put(id, d.name()));
            if (d.status() != Device.Status.CONFIRMED || !d.inHomeAssistant()) continue;
            Map<String, Integer> perKind = new HashMap<>();
            d.readings().forEach(r -> perKind.merge(r.kindKey(), 1, Integer::sum));
            for (Device.Reading r : d.readings()) {
                recorded.add(r.entityId());
                if (perKind.get(r.kindKey()) == 1) only.add(r.entityId());
            }
        }
        Index before = index;
        index = new Index(Set.copyOf(recorded), Set.copyOf(hidden), Map.copyOf(names), Set.copyOf(only));
        if (!before.recorded().equals(index.recorded()) || !before.hidden().equals(index.hidden())
                || !before.names().equals(index.names())) {
            publisher.publishEvent(new DevicesChanged(clock.instant()));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
