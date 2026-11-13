package com.homehub.alerts;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Alerts to Android phones through a self-hosted ntfy server (#154, ADR 12), bound from {@code homehub.ntfy.*}.
 * Off while {@code url} or {@code topic} is empty.
 *
 * @param url           the ntfy server, e.g. {@code http://127.0.0.1:2586} on the hub
 * @param topic         the topic the Android phones subscribe to; every alert goes here
 * @param fallbackTopic the topic iPhones subscribe to in the ntfy app, used only when Home Assistant couldn't deliver
 *                      an alert to them; empty: no fallback
 * @param token         ntfy access token, if the server requires one. A secret: set it in
 *                      {@code ~/.homehub/secrets.properties} or as the systemd credential {@code homehub.ntfy.token},
 *                      never in this repository
 */
@ConfigurationProperties("homehub.ntfy")
public record NtfyProperties(String url, String topic, String fallbackTopic, String token) {

    public NtfyProperties {
        url = url == null ? "" : url.trim().replaceAll("/+$", "");
        topic = topic == null ? "" : topic.trim();
        fallbackTopic = fallbackTopic == null ? "" : fallbackTopic.trim();
        token = token == null ? "" : token.trim();
    }

    public boolean isConfigured() {
        return !url.isEmpty() && !topic.isEmpty();
    }

    static NtfyProperties off() {
        return new NtfyProperties(null, null, null, null);
    }
}
