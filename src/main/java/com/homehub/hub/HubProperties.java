package com.homehub.hub;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Power, internet and hub battery watching (#103), bound from {@code homehub.hub.*}. Off unless the hub sets
 * {@code power-supply-dir} or {@code internet-hosts} (the prod profile does), so a dev laptop or CI stays quiet.
 *
 * @param powerSupplyDir    where Linux describes the chargers and batteries, {@code /sys/class/power_supply}; empty: off
 * @param internetHosts     {@code host:port} to try; the internet is up while any answers. Empty: no internet check
 * @param internetLostAfter how long nothing answers before "Internet is down" (short blips are ignored)
 * @param batteryWarnings   hub battery levels (%) that warn during a power cut, e.g. 30 and 10
 * @param freezerCheckAfter a power cut longer than this asks the family to check the freezer
 * @param freezer           the device whose temperature that reminder quotes
 * @param healthBelow       a battery holding less than this % of its design capacity is worth replacing
 */
@ConfigurationProperties("homehub.hub")
public record HubProperties(String powerSupplyDir,
                            List<String> internetHosts,
                            @DefaultValue("3m") Duration internetLostAfter,
                            @DefaultValue({"30", "10"}) List<Integer> batteryWarnings,
                            @DefaultValue("2h") Duration freezerCheckAfter,
                            @DefaultValue("Refrigerator/Freezer") String freezer,
                            @DefaultValue("60") int healthBelow) {

    public HubProperties {
        powerSupplyDir = powerSupplyDir == null ? "" : powerSupplyDir.trim();
        internetHosts = internetHosts == null ? List.of()
                : internetHosts.stream().map(String::trim).filter(h -> !h.isEmpty()).toList();
        if (internetLostAfter == null) internetLostAfter = Duration.ofMinutes(3);
        batteryWarnings = (batteryWarnings == null ? List.of(30, 10) : batteryWarnings).stream()
                .sorted(Comparator.reverseOrder()).toList();
        if (freezerCheckAfter == null) freezerCheckAfter = Duration.ofHours(2);
        freezer = freezer == null ? "" : freezer.trim();
    }

    /** The defaults, with nothing switched on. */
    static HubProperties defaults() {
        return new HubProperties(null, null, null, null, null, "Refrigerator/Freezer", 60);
    }
}
