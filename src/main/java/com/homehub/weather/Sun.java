package com.homehub.weather;

import java.time.Instant;

/**
 * Where the sun is at home, from Home Assistant's {@code sun.sun} entity (it knows the home's location). The dashboard
 * uses it for the "Sunset to sunrise" appearance: dark while the sun is down.
 *
 * @param up          true between sunrise and sunset
 * @param nextRising  next sunrise, so the browser can switch at the right moment by itself
 * @param nextSetting next sunset
 */
public record Sun(boolean up, Instant nextRising, Instant nextSetting) {
}
