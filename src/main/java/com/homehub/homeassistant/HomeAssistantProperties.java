package com.homehub.homeassistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Home Assistant connection settings, bound from {@code homeassistant.url} and {@code homeassistant.token}.
 * <p>
 * Normally set in ~/.homehub/secrets.properties. Spring's relaxed binding also accepts the
 * HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN environment variables as a fallback.
 * <p>
 * Used by {@link com.homehub.connectors.HomeAssistantConnector}.
 */
@ConfigurationProperties("homeassistant")
public record HomeAssistantProperties(String url, String token) {

    public boolean isConfigured() {
        return url != null && !url.isBlank() && token != null && !token.isBlank();
    }

    /** Never print the token, even by accident (logging, debugging, exceptions). */
    @Override
    public String toString() {
        return "HomeAssistantProperties[url=" + url + ", token=" + (token == null || token.isBlank() ? "<not set>" : "******") + "]";
    }
}
