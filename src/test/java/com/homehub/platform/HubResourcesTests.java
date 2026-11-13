package com.homehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.homehub.alerts.Alert;
import com.homehub.alerts.Alert.Level;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HubResourcesTests {

    private static final Instant T = Instant.parse("2026-11-13T21:00:00Z");
    private static final long GB = 1_000_000_000L;
    private final HubResources resources = new HubResources(85, 10);

    @Test
    void aFullDiskWarnsOnceAndClearsWhenComfortablyBetter() {
        assertThat(resources.disk(100 * GB, 20 * GB, T)).as("80% full").isEmpty();
        assertThat(resources.disk(100 * GB, 14 * GB, T)).extracting(Alert::key, Alert::level, Alert::message)
                .containsExactly(tuple("disk:hub", Level.PROBLEM, "The hub's disk is 86% full: 14 GB free."));
        assertThat(resources.active()).extracting(Alert::key).containsExactly("disk:hub");
        assertThat(resources.disk(100 * GB, 3 * GB, T)).as("once").isEmpty();
        assertThat(resources.disk(100 * GB, 16 * GB, T)).as("84%: still close to the limit").isEmpty();
        assertThat(resources.disk(100 * GB, 20 * GB, T)).extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.CLEARED, "The hub's disk has room again: 80% full."));
        assertThat(resources.active()).isEmpty();
        assertThat(resources.disk(0, 0, T)).as("no disk information").isEmpty();
    }

    @Test
    void lowMemoryOrFullSwapWarns() {
        Map<String, Long> fine = Map.of("MemTotal", 4_000_000L, "MemAvailable", 1_200_000L, "SwapTotal", 2_000_000L, "SwapFree", 1_900_000L);
        assertThat(resources.memory(fine, T)).isEmpty();

        Map<String, Long> low = Map.of("MemTotal", 4_000_000L, "MemAvailable", 300_000L, "SwapTotal", 2_000_000L, "SwapFree", 1_000_000L);
        assertThat(resources.memory(low, T)).extracting(Alert::key, Alert::message)
                .containsExactly(tuple("memory:hub", "The hub is short of memory: 293 MB free, swap 50% used."));
        assertThat(resources.memory(low, T)).as("once").isEmpty();
        assertThat(resources.memory(fine, T)).extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.CLEARED, "The hub has enough memory again: 1172 MB free."));

        Map<String, Long> swapFull = Map.of("MemTotal", 4_000_000L, "MemAvailable", 1_000_000L, "SwapTotal", 2_000_000L, "SwapFree", 100_000L);
        assertThat(resources.memory(swapFull, T)).extracting(Alert::message)
                .containsExactly("The hub is short of memory: 977 MB free, swap 95% used.");
    }

    @Test
    void noSwapIsFineAndOddInputIsIgnored() {
        assertThat(resources.memory(Map.of("MemTotal", 4_000_000L, "MemAvailable", 2_000_000L, "SwapTotal", 0L, "SwapFree", 0L), T)).isEmpty();
        assertThat(resources.memory(Map.of("MemTotal", 4_000_000L, "MemAvailable", 200_000L), T)).extracting(Alert::message)
                .containsExactly("The hub is short of memory: 195 MB free.");
        assertThat(new HubResources(85, 10).memory(Map.of(), T)).isEmpty();
    }
}
