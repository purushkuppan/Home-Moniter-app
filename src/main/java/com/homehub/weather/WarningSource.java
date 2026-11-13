package com.homehub.weather;

import java.util.List;

/** A country's official weather warnings (#106). The home's country (from Home Assistant) picks the source. */
public interface WarningSource {

    /** "US National Weather Service": shown on the weather card. */
    String name();

    /** Whether it covers a country (ISO 3166 code from Home Assistant, e.g. "US"). */
    boolean covers(String country);

    /**
     * The warnings in force now at a location (already rounded to about 1 km by the caller).
     *
     * @throws RuntimeException when the service can't be reached; the caller tries again later
     */
    List<WeatherWarning> active(double latitude, double longitude);
}
