package com.homehub.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The dead-man switch (#102), bound from {@code homehub.deadman.*}.
 *
 * @param pingUrl    the outside check Kudil pings while it is healthy, e.g. a Healthchecks.io check
 *                   ({@code https://hc-ping.com/<uuid>}). A secret: whoever has it can mark the hub healthy. Set it in
 *                   {@code ~/.homehub/secrets.properties} or the systemd credential {@code homehub.deadman.ping-url},
 *                   never in this repository. Empty: no pings
 * @param every      how often to ping; the outside check should expect this, with some grace
 * @param diskPath   the disk to watch: the one that holds the database
 * @param diskWarn   warn when that disk is this full (%)
 * @param meminfo    where Linux reports memory and swap; empty: no memory warnings
 * @param memoryWarn warn when free memory falls below this (% of all memory), or swap is this full (%)
 */
@ConfigurationProperties("homehub.deadman")
public record DeadManProperties(String pingUrl,
                                @DefaultValue("5m") Duration every,
                                @DefaultValue("data") String diskPath,
                                @DefaultValue("85") int diskWarn,
                                String meminfo,
                                @DefaultValue("10") int memoryWarn) {

    public DeadManProperties {
        pingUrl = pingUrl == null ? "" : pingUrl.trim();
        if (every == null || every.isNegative() || every.isZero()) every = Duration.ofMinutes(5);
        diskPath = diskPath == null || diskPath.isBlank() ? "data" : diskPath.trim();
        meminfo = meminfo == null ? "" : meminfo.trim();
    }

    /** Only https: the URL is a secret and must not travel in clear text. */
    public boolean pings() {
        return pingUrl.startsWith("https://");
    }
}
