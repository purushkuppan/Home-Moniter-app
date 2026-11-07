package com.homehub.weather;

import java.time.Instant;
import java.util.List;

/**
 * Outside conditions from Home Assistant's weather entity. Numbers are in Home Assistant's units (see the *Unit
 * fields) and may be null when the source doesn't report them.
 *
 * @param condition   Home Assistant condition, e.g. "cloudy", "partlycloudy", "rainy", "unavailable"
 * @param attribution credit line required by the source (met.no)
 * @param fetchedAt   when Kudil read it from Home Assistant
 * @param stale       true when Home Assistant couldn't be reached and this is the last result
 * @param days        today first, then the next days
 */
public record Weather(
        String condition,
        Double temperature,
        String temperatureUnit,
        Double humidity,
        Double windSpeed,
        String windSpeedUnit,
        String precipitationUnit,
        String attribution,
        Instant fetchedAt,
        boolean stale,
        List<Day> days) {

    /**
     * One day of the forecast.
     *
     * @param time          the forecast's own time stamp (ISO 8601), so the browser shows the right weekday
     * @param high          highest temperature
     * @param low           lowest temperature
     * @param precipitation rain or snow expected that day, in {@code precipitationUnit}
     */
    public record Day(String time, String condition, Double high, Double low, Double precipitation) {
    }

    Weather asStale() {
        return new Weather(condition, temperature, temperatureUnit, humidity, windSpeed, windSpeedUnit, precipitationUnit,
                attribution, fetchedAt, true, days);
    }
}
