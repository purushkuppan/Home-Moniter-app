package com.homehub.alerts;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends alerts to Android phones through a self-hosted ntfy server (#154, ADR 12). Each phone runs the free ntfy app
 * subscribed to {@code homehub.ntfy.topic}.
 * <ul>
 *   <li>Severity sets ntfy's priority: critical 5 (urgent: the ntfy app can override Do Not Disturb once allowed),
 *       a problem 4, an all clear 3, a notice 2.</li>
 *   <li>Tapping opens the dashboard ({@code homehub.alerts.link}) on that alert (#184). Critical alerts carry an "I'm on it" button that
 *       answers Kudil directly ({@code POST /api/alerts/ack}), so the phone must reach Kudil (home Wi-Fi or
 *       Tailscale).</li>
 *   <li>{@link #sendFallback}: an alert Home Assistant couldn't deliver to the iPhones also goes to
 *       {@code fallback-topic} as an ordinary push.</li>
 * </ul>
 * Published as JSON (ntfy's JSON publishing), so titles and messages need no header encoding. A failed send is logged
 * and otherwise ignored: the alert is still stored and on the dashboard. The token is never logged.
 */
@Component
public class NtfyNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(NtfyNotifier.class);
    private static final String TITLE = "Kudil";
    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static final String ACK_FROM = "Android phone";

    private final NtfyProperties settings;
    private final RestClient client;
    private final String link;
    private final boolean configured;

    @Autowired
    public NtfyNotifier(NtfyProperties settings, AlertProperties alerts) {
        this(RestClient.builder().requestFactory(timeouts()), settings, alerts);
    }

    /** Lets tests supply a builder bound to a mock server. */
    NtfyNotifier(RestClient.Builder builder, NtfyProperties settings, AlertProperties alerts) {
        this.settings = settings;
        this.link = alerts.link() == null ? "" : alerts.link().trim().replaceAll("/+$", "");
        boolean valid = settings.isConfigured() && TOPIC.matcher(settings.topic()).matches()
                && (settings.fallbackTopic().isEmpty() || TOPIC.matcher(settings.fallbackTopic()).matches());
        if (settings.isConfigured() && !valid) {
            log.warn("homehub.ntfy topics may only use letters, digits, - and _; ntfy alerts are off.");
        }
        this.configured = valid;
        if (configured) {
            builder.baseUrl(settings.url());
            if (!settings.token().isEmpty()) builder.defaultHeaders(h -> h.setBearerAuth(settings.token()));
        }
        this.client = builder.build();
    }

    @Override
    public boolean configured() {
        return configured;
    }

    @Override
    public int send(Alert alert, boolean critical) {
        return configured && publish(settings.topic(), alert, critical) ? 1 : 0;
    }

    /** The iPhones' fallback when Home Assistant couldn't deliver: an ordinary push, never critical. */
    public int sendFallback(Alert alert) {
        if (!configured || settings.fallbackTopic().isEmpty()) return 0;
        return publish(settings.fallbackTopic(), alert, false) ? 1 : 0;
    }

    private boolean publish(String topic, Alert alert, boolean critical) {
        try {
            client.post().uri("/")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message(topic, alert, critical))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            log.warn("Could not send the alert through ntfy: the server answered HTTP {}", ex.getStatusCode().value());
        } catch (RestClientException ex) {
            log.warn("Could not send the alert through ntfy: the server is not reachable");
        }
        return false;
    }

    /** The JSON ntfy publishes. */
    Map<String, Object> message(String topic, Alert alert, boolean critical) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("topic", topic);
        m.put("title", TITLE);
        m.put("message", NextSteps.withStep(alert));
        m.put("priority", critical ? 5 : switch (alert.level()) {
            case PROBLEM -> 4;
            case CLEARED -> 3;
            case NOTICE -> 2;
        });
        m.put("tags", List.of(critical ? "rotating_light" : switch (alert.level()) {
            case PROBLEM -> "warning";
            case CLEARED -> "white_check_mark";
            case NOTICE -> "information_source";
        }));
        if (!link.isEmpty()) m.put("click", alert.link(link));   // the dashboard, showing this alert (#184)
        List<Map<String, Object>> actions = new ArrayList<>();
        if (critical && !link.isEmpty()) {
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("action", "http");
            ack.put("label", "I'm on it");
            ack.put("url", link + "/api/alerts/ack");
            ack.put("method", "POST");
            ack.put("headers", Map.of("Content-Type", "application/json"));
            Map<String, String> answer = new LinkedHashMap<>();   // a fixed order, so the body is always the same
            answer.put("key", alert.key());
            answer.put("who", ACK_FROM);
            ack.put("body", JsonMapper.shared().writeValueAsString(answer));
            ack.put("clear", true);
            actions.add(ack);
        }
        if (!actions.isEmpty()) m.put("actions", actions);
        return m;
    }

    private static JdkClientHttpRequestFactory timeouts() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }
}
