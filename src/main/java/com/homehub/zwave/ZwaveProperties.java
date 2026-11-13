package com.homehub.zwave;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kudil's own connection to the Z-Wave JS server (#134, ADR 12), bound from {@code homehub.zwave.*}.
 *
 * @param url the server's WebSocket address, on the hub {@code ws://127.0.0.1:3000} (inside the zwave-js-ui
 *            container, published on the hub itself only). Empty: no connection
 */
@ConfigurationProperties("homehub.zwave")
public record ZwaveProperties(String url) {

    public ZwaveProperties {
        url = url == null ? "" : url.trim();
    }

    public boolean isConfigured() {
        return !url.isEmpty();
    }
}
