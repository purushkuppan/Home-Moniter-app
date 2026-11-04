package com.homehub.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectorSchedulerTests {

    private static DeviceConnector connector(String name, List<Event> events) {
        return new DeviceConnector() {
            public String name() { return name; }
            public List<Event> poll() { return events; }
        };
    }

    @Test
    void savesEveryEventAndKeepsGoingWhenAConnectorFails() {
        EventRepository repo = mock(EventRepository.class);
        Event e = new Event(null, "lamp", "switch", "on", Instant.now());
        DeviceConnector broken = new DeviceConnector() {
            public String name() { return "broken"; }
            public List<Event> poll() { throw new IllegalStateException("boom"); }
        };

        new ConnectorScheduler(List.of(broken, connector("ok", List.of(e, e))), repo).pollAll();

        verify(repo, times(2)).save(e);
    }

    @Test
    void fakeConnectorProducesThreeDevices() {
        List<Event> events = new FakeConnector().poll();
        assertThat(events).extracting(Event::device)
                .containsExactly("front-door-lock", "living-room-thermostat", "washer");
        assertThat(events.get(1).value()).matches("\\d+\\.\\d");
    }

    @Test
    void worksWithNoConnectors() {
        EventRepository repo = mock(EventRepository.class);
        new ConnectorScheduler(List.of(), repo).pollAll();
        verify(repo, never()).save(any());
    }
}
