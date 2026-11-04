package com.homehub.events;

import java.time.Instant;

public record Event(Long id, String device, String type, String value, Instant time) {
}
