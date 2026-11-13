package com.homehub.devices;

import static com.homehub.homeassistant.JsonValues.asMap;
import static com.homehub.homeassistant.JsonValues.firstNonBlank;
import static com.homehub.homeassistant.JsonValues.text;

import com.homehub.alerts.DeviceAlertSettings;
import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import com.homehub.homeassistant.EntityDirectory;
import com.homehub.homeassistant.EntityNames;
import com.homehub.homeassistant.EntityPatterns;
import com.homehub.homeassistant.RegistrySnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
public class DeviceCatalog implements EntityDirectory, DeviceAlertSettings {

    private static final Logger log = LoggerFactory.getLogger(DeviceCatalog.class);

    /** Published after a sync or a choice changed what is recorded, so the connector reads the states again. */
    public record DevicesChanged(Instant time) { }

    private final DeviceRepository repository;
    private final EventRepository events;
    private final EntityPatterns include;
    private final EntityNames entityNames;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    /** Lookups for the connector and the alerts, rebuilt after every change; read without locking. */
    private record Index(Set<String> recorded, Set<String> hidden, Map<String, String> names, Set<String> onlyOfKind,
                         Map<String, Duration> openFor, Map<String, Map<String, String>> choices) {
        static final Index EMPTY = new Index(Set.of(), Set.of(), Map.of(), Set.of(), Map.of(), Map.of());
    }

    private volatile Index index = Index.EMPTY;

    /** "Alert if open longer than" choices on the Devices list, in minutes; 0 = never. */
    static final List<Integer> OPEN_FOR_CHOICES = List.of(0, 1, 2, 5, 10, 15, 30, 60);

    @Autowired
    public DeviceCatalog(DeviceRepository repository, EventRepository events,
                         @Value("${homehub.connectors.homeassistant.include:}") String include,
                         EntityNames entityNames, ApplicationEventPublisher publisher) {
        this(repository, events, include, entityNames, publisher, Clock.systemUTC());
    }

    DeviceCatalog(DeviceRepository repository, EventRepository events, String include, EntityNames entityNames,
                  ApplicationEventPublisher publisher, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.include = EntityPatterns.parse(include);
        this.entityNames = entityNames == null ? EntityNames.none() : entityNames;
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
            List<Device.Reading> readings = DeviceKinds.readingsOf(kind, entities);
            Device fromHa = new Device(0, haId, haName, haName, room, blankToNull(text(d.get("manufacturer"))),
                    blankToNull(text(d.get("model"))), kind, Device.Status.NEW, entityIds, readings, true, now, null, null, Map.of());
            // Readings already shown under one name of their own (homehub.connectors.homeassistant.names, e.g.
            // "Back door"): that is what the household calls the device, so the Devices list uses it too.
            String shownAs = commonName(readings);

            Device existing = known.get(haId);
            if (existing != null) {
                repository.updateFromHomeAssistant(fromHa);
                if (shownAs != null && existing.name().equals(existing.haName()) && !shownAs.equals(existing.name())) {
                    repository.updateChoice(existing.id(), shownAs, existing.status(), existing.confirmedAt());
                }
                continue;
            }
            // Recorded before #99 through the include patterns: confirmed as it is, so nothing changes for it.
            boolean alreadyWatched = entityIds.stream().anyMatch(include::matches);
            String name = shownAs != null ? shownAs : haName;
            Device added = alreadyWatched
                    ? new Device(0, haId, name, haName, room, fromHa.manufacturer(), fromHa.model(), kind,
                    Device.Status.CONFIRMED, entityIds, readings, true, now, now, null, Map.of())
                    : fromHa;
            repository.insert(added);
            found.add(added);
        }

        // Removed from Home Assistant: kept (its history stays), no longer recorded.
        for (Device d : known.values()) {
            if (!seen.contains(d.haDeviceId()) && d.inHomeAssistant()) {
                repository.updateFromHomeAssistant(new Device(d.id(), d.haDeviceId(), d.name(), d.haName(), d.room(),
                        d.manufacturer(), d.model(), d.kind(), d.status(), d.entities(), d.readings(), false,
                        d.firstSeen(), d.confirmedAt(), d.openForMinutes(), d.alertChoices()));
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

    /**
     * A door or window's "alert if open longer than" (#100): minutes, 0 for never, null for the default. Only for door,
     * window and garage door sensors; the fridge's doors have limits of their own.
     */
    public synchronized Device setOpenFor(long id, Integer minutes) {
        Device d = find(id);
        if (d.kind() != DeviceKind.OPENING && d.kind() != DeviceKind.GARAGE) {
            throw new IllegalArgumentException("Only door and window sensors have this setting.");
        }
        if (minutes != null && !OPEN_FOR_CHOICES.contains(minutes)) {
            throw new IllegalArgumentException("Choose one of " + OPEN_FOR_CHOICES + " minutes.");
        }
        repository.updateOpenFor(id, minutes);
        reindex();
        return find(id);
    }

    // ---------------------------------------------------------------- DeviceAlertSettings (for the alerts)

    @Override
    public Optional<Duration> openFor(String device) {
        if (device == null) return Optional.empty();
        // One door of an appliance ("Refrigerator/Fridge"): its choice on the Devices list.
        Optional<String> part = choice(device, "open-for:");
        if (part.isPresent()) return part.map(v -> v.equals("off") ? Duration.ZERO : Duration.ofMinutes(Long.parseLong(v)));
        String key = device.trim().toLowerCase(Locale.ROOT);
        Duration own = index.openFor().get(key);
        if (own == null && key.contains("/")) own = index.openFor().get(key.substring(0, key.indexOf('/')));
        return Optional.ofNullable(own);
    }

    @Override
    public Optional<Double> tooWarm(String device) {
        return choice(device, "too-warm:").map(v -> v.equals("off") ? Double.NaN : Double.valueOf(v));
    }

    @Override
    public Optional<Boolean> finished(String device) {
        return choice(device, "finished").map(v -> v.equals("on"));
    }

    @Override
    public Optional<Duration> offlineAfter(String device) {
        return choice(device, "offline").map(v -> v.equals("off") ? Duration.ZERO : Duration.ofMinutes(Long.parseLong(v)));
    }

    /**
     * The choice for a reading or device label: "Refrigerator/Fridge" with "too-warm:" looks up "too-warm:Fridge" on
     * the Refrigerator; "Washer" with "finished" looks up "finished" on the Washer.
     */
    private Optional<String> choice(String label, String prefix) {
        if (label == null) return Optional.empty();
        String trimmed = label.trim();
        int slash = trimmed.indexOf('/');
        String main = (slash < 0 ? trimmed : trimmed.substring(0, slash)).toLowerCase(Locale.ROOT);
        Map<String, String> choices = index.choices().get(main);
        if (choices == null) return Optional.empty();
        if (!prefix.endsWith(":")) return Optional.ofNullable(choices.get(prefix));
        if (slash < 0) return Optional.empty();
        String part = trimmed.substring(slash + 1).trim().toLowerCase(Locale.ROOT);
        return choices.entrySet().stream()
                .filter(c -> c.getKey().toLowerCase(Locale.ROOT).equals(prefix + part))
                .map(Map.Entry::getValue).findFirst();
    }

    /** Offline after: the minutes that can be chosen for an appliance. */
    static final List<Integer> OFFLINE_CHOICES = List.of(15, 30, 60, 120);

    /**
     * An appliance alert choice (#100): {@code open-for:<part>} (minutes from {@link #OPEN_FOR_CHOICES}, or off),
     * {@code too-warm:<part>} (a temperature in the reading's unit, or off), {@code finished} (on or off),
     * {@code offline} (minutes from {@link #OFFLINE_CHOICES}, or off). An empty value goes back to the default.
     */
    public synchronized Device setChoice(long id, String key, String value) {
        Device d = find(id);
        if (d.status() != Device.Status.CONFIRMED) throw new IllegalArgumentException("Confirm the device first.");
        String k = key == null ? "" : key.trim();
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        boolean valid = v.isEmpty() || switch (k.contains(":") ? k.substring(0, k.indexOf(':') + 1) : k) {
            case "open-for:" -> validPart(k) && (v.equals("off") || isInt(v) && OPEN_FOR_CHOICES.contains(Integer.parseInt(v)) && !v.equals("0"));
            case "too-warm:" -> validPart(k) && (v.equals("off") || isNumber(v) && Math.abs(Double.parseDouble(v)) <= 150);
            case "finished" -> v.equals("on") || v.equals("off");
            case "offline" -> v.equals("off") || isInt(v) && OFFLINE_CHOICES.contains(Integer.parseInt(v));
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("That isn't a choice for " + d.name() + ".");
        Map<String, String> choices = new HashMap<>(d.alertChoices());
        if (v.isEmpty()) choices.remove(k);
        else choices.put(k, v);
        repository.updateAlertChoices(id, choices);
        reindex();
        return find(id);
    }

    private static boolean validPart(String key) {
        String part = key.substring(key.indexOf(':') + 1);
        return !part.isBlank() && part.length() <= 40 && !part.contains("/");
    }

    private static boolean isInt(String s) {
        return s.matches("\\d{1,4}");
    }

    private static boolean isNumber(String s) {
        return s.matches("-?\\d{1,3}(\\.\\d{1,2})?");
    }

    /** The one name all these readings are shown under (name overrides), or null. */
    private String commonName(List<Device.Reading> readings) {
        Set<String> shown = new HashSet<>();
        for (Device.Reading r : readings) shown.add(entityNames.nameOf(r.entityId()));
        return readings.isEmpty() || shown.size() != 1 || shown.contains(null) ? null : shown.iterator().next();
    }

    private Device find(long id) {
        return repository.find(id).orElseThrow(() -> new java.util.NoSuchElementException("No such device."));
    }

    /** Rebuilds the connector's lookups; tells the connector when what it records changed. */
    private void reindex() {
        Set<String> recorded = new HashSet<>(), hidden = new HashSet<>(), only = new HashSet<>();
        Map<String, String> names = new HashMap<>();
        Map<String, Duration> openFor = new HashMap<>();
        Map<String, Map<String, String>> choices = new HashMap<>();
        for (Device d : repository.all()) {
            if (d.status() == Device.Status.HIDDEN) {
                hidden.addAll(d.entities());
                continue;
            }
            d.entities().forEach(id -> names.put(id, d.name()));
            if (d.openForMinutes() != null) openFor.put(d.name().toLowerCase(Locale.ROOT), Duration.ofMinutes(d.openForMinutes()));
            if (!d.alertChoices().isEmpty()) choices.put(d.name().toLowerCase(Locale.ROOT), d.alertChoices());
            if (d.status() != Device.Status.CONFIRMED || !d.inHomeAssistant()) continue;
            Map<String, Integer> perKind = new HashMap<>();
            d.readings().forEach(r -> perKind.merge(r.kindKey(), 1, Integer::sum));
            for (Device.Reading r : d.readings()) {
                recorded.add(r.entityId());
                if (perKind.get(r.kindKey()) == 1) only.add(r.entityId());
            }
        }
        Index before = index;
        index = new Index(Set.copyOf(recorded), Set.copyOf(hidden), Map.copyOf(names), Set.copyOf(only), Map.copyOf(openFor),
                Map.copyOf(choices));
        if (!before.recorded().equals(index.recorded()) || !before.hidden().equals(index.hidden())
                || !before.names().equals(index.names())) {
            publisher.publishEvent(new DevicesChanged(clock.instant()));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
