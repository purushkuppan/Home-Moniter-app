package com.homehub.homeassistant;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Entity id patterns from configuration, e.g. {@code binary_sensor.*_door,lock.*} ({@code *} matches anything). */
public final class EntityPatterns {

    private final List<Pattern> patterns;

    private EntityPatterns(List<Pattern> patterns) {
        this.patterns = patterns;
    }

    /** Parses a comma-separated list; blanks are ignored, so "" matches nothing. */
    public static EntityPatterns parse(String commaSeparated) {
        return new EntityPatterns(Arrays.stream(commaSeparated == null ? new String[0] : commaSeparated.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(glob -> Pattern.compile(Arrays.stream(glob.split("\\*", -1))
                        .map(Pattern::quote).collect(Collectors.joining(".*"))))
                .toList());
    }

    public boolean matches(String entityId) {
        return patterns.stream().anyMatch(p -> p.matcher(entityId).matches());
    }
}
