package com.homehub.connectors;

import com.homehub.events.Event;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Generates sample events so the app can be tested without real devices. */
@Component
@ConditionalOnProperty(name = "homehub.connectors.fake.enabled", havingValue = "true")
public class FakeConnector implements DeviceConnector {

    private static final String[] WASHER_CYCLES = {"idle", "wash", "rinse", "spin", "done"};

    private boolean locked = true;
    private int washerStep = 0;

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public synchronized List<Event> poll() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Instant now = Instant.now();
        if (rnd.nextInt(3) == 0) {
            locked = !locked;
        }
        double temp = 20 + rnd.nextDouble() * 4;
        String cycle = WASHER_CYCLES[washerStep++ % WASHER_CYCLES.length];
        return List.of(
                new Event(null, "front-door-lock", "lock", locked ? "locked" : "unlocked", now),
                new Event(null, "living-room-thermostat", "temperature", String.format(Locale.ROOT, "%.1f", temp), now),
                new Event(null, "washer", "cycle", cycle, now));
    }
}

