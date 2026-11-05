package com.homehub.connectors;

import com.homehub.events.Event;
import com.homehub.events.EventRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls every enabled {@link DeviceConnector} on a fixed delay and stores what they return. */
@Component
@ConditionalOnProperty(name = "homehub.connectors.enabled", havingValue = "true", matchIfMissing = true)
public class ConnectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConnectorScheduler.class);

    private final List<DeviceConnector> connectors;
    private final EventRepository repository;

    public ConnectorScheduler(List<DeviceConnector> connectors, EventRepository repository) {
        this.connectors = List.copyOf(connectors);
        this.repository = repository;
        log.info("Connectors active: {}", this.connectors.stream().map(DeviceConnector::name).toList());
    }

    @Scheduled(fixedDelayString = "${homehub.connectors.interval:30s}",
               initialDelayString = "${homehub.connectors.interval:30s}")
    public void pollAll() {
        connectors.forEach(this::pollOne);
    }

    void pollOne(DeviceConnector connector) {
        try {
            List<Event> events = connector.poll();
            events.forEach(repository::saveIfChanged);   // skips readings that match the last saved value
            log.debug("{} produced {} events", connector.name(), events.size());
        } catch (Exception ex) {
            log.warn("Connector {} failed", connector.name(), ex);
        }
    }
}
