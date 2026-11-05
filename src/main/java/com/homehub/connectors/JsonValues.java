package com.homehub.connectors;

import java.util.Map;

/** Null-safe helpers for walking JSON that was parsed into plain maps and lists. */
final class JsonValues {

    private JsonValues() {
    }

    /** The value as a map, or an empty map if it is missing or not an object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** The value as text, or "" if it is missing. */
    static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** The first value that is not blank, or "". */
    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
