package com.homehub.homeassistant;

import java.util.List;
import java.util.Map;

/** Null-safe helpers for walking JSON that was parsed into plain maps and lists. */
public final class JsonValues {

    private JsonValues() {
    }

    /** The value as a map, or an empty map if it is missing or not an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** The value as a list, or an empty list if it is missing or not an array. */
    public static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    /** The value as text, or "" if it is missing. */
    public static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** The first value that is not blank, or "". */
    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
