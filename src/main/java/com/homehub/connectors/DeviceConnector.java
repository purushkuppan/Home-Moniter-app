package com.homehub.connectors;

import com.homehub.events.Event;
import java.util.List;

public interface DeviceConnector {

    String name();

    List<Event> poll();
}
