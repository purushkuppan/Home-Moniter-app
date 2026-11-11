package com.homehub.alerts;

import com.homehub.homeassistant.HomeAssistantProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sends alerts to phones through Home Assistant's notify actions ({@code homehub.alerts.send-to}), for example
 * {@code notify.mobile_app_phone}, which pushes to the Home Assistant Companion app.
 * <p>
 * Tapping the notification opens {@code homehub.alerts.link} (the dashboard). The "all clear" for a problem carries
 * the same tag as the problem, so it replaces the earlier notification on the phone. A failed send is logged and
 * otherwise ignored: the alert is still stored and shown on the dashboard.
 */
@Component
@ConditionalOnProperty(name = "homehub.alerts.enabled", havingValue = "true")
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);
    private static final Pattern TARGET = Pattern.compile("notify\\.[a-z0-9_]+");
    private static final String TITLE = "Kudil";   // product name shown on phones

    private final RestClient client;
    private final boolean configured;
    private final List<String> services;
    private final String link;

    @Autowired
    public AlertNotifier(HomeAssistantProperties homeAssistant, AlertProperties alerts) {
        this(RestClient.builder().requestFactory(timeouts()), homeAssistant, alerts);
    }

    /** Lets tests supply a builder bound to a mock server. */
    AlertNotifier(RestClient.Builder builder, HomeAssistantProperties homeAssistant, AlertProperties alerts) {
        this.configured = homeAssistant.isConfigured();
        this.link = alerts.link();
        this.services = alerts.sendTo().stream()
                .filter(target -> {
                    boolean valid = TARGET.matcher(target).matches();
                    if (!valid) log.warn("Ignoring homehub.alerts.send-to entry '{}': expected notify.<name>", target);
                    return valid;
                })
                .map(target -> target.substring("notify.".length()))
                .toList();
        if (configured) {
            String token = homeAssistant.token().trim();
            builder.baseUrl(homeAssistant.url().trim().replaceAll("/+$", "") + "/api")
                    .defaultHeaders(h -> h.setBearerAuth(token));
        }
        this.client = builder.build();
        if (!services.isEmpty() && !configured) {
            log.warn("homehub.alerts.send-to is set but Home Assistant is not configured; alerts will only show on the dashboard.");
        }
    }

    /** Sends one alert to every configured phone. Never throws. */
    public void send(Alert alert) {
        if (!configured || services.isEmpty()) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tag", "homehub-" + alert.key());   // same tag = replaces the earlier notification
        data.put("group", "homehub");
        if (!link.isEmpty()) {
            data.put("url", link);           // iPhone: opened on tap
            data.put("clickAction", link);   // Android: opened on tap
        }
        if (alert.level() == Alert.Level.PROBLEM && alert.key().startsWith("leak:")) {
            // A leak can't wait: delivered at once even while the phone is idle (Android), and through Focus (iPhone).
            data.put("priority", "high");
            data.put("ttl", 0);
            data.put("push", Map.of("interruption-level", "time-sensitive"));
        }
        Map<String, Object> body = Map.of("title", TITLE, "message", alert.message(), "data", data);

        for (String service : services) {
            try {
                client.post().uri("/services/notify/{service}", service)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve().toBodilessEntity();
            } catch (RestClientResponseException ex) {
                log.warn("Could not send the alert through notify.{}: Home Assistant answered HTTP {}",
                        service, ex.getStatusCode().value());
            } catch (RestClientException ex) {
                log.warn("Could not send the alert through notify.{}: Home Assistant is not reachable", service);
            }
        }
    }

    private static JdkClientHttpRequestFactory timeouts() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }
}
