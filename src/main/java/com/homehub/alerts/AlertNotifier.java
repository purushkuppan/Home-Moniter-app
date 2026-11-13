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
public class AlertNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);
    private static final Pattern TARGET = Pattern.compile("notify\\.[a-z0-9_]+");
    private static final String TITLE = "Kudil";   // product name shown on phones

    private final RestClient client;
    private final boolean configured;
    private final List<String> services;
    private final List<String> speakOn;   // Android phones that also hear critical alerts spoken (#150)
    private final String link;

    @Autowired
    public AlertNotifier(HomeAssistantProperties homeAssistant, AlertProperties alerts, CriticalAlertProperties critical) {
        this(RestClient.builder().requestFactory(timeouts()), homeAssistant, alerts, critical);
    }

    /** Lets tests supply a builder bound to a mock server. Nothing is spoken. */
    AlertNotifier(RestClient.Builder builder, HomeAssistantProperties homeAssistant, AlertProperties alerts) {
        this(builder, homeAssistant, alerts, CriticalAlertProperties.defaults());
    }

    /** Lets tests supply a builder bound to a mock server and the phones that speak critical alerts. */
    AlertNotifier(RestClient.Builder builder, HomeAssistantProperties homeAssistant, AlertProperties alerts,
                  CriticalAlertProperties critical) {
        this.configured = homeAssistant.isConfigured();
        this.link = alerts.link();
        this.services = targets(alerts.sendTo(), "homehub.alerts.send-to");
        this.speakOn = targets(critical.speakOn(), "homehub.alerts.critical.speak-on");
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

    /** Whether Home Assistant is set up and there are phones to send to. */
    @Override
    public boolean configured() {
        return configured && !services.isEmpty();
    }

    /** Sends one alert to every configured phone, as an ordinary (not critical) alert. Never throws. */
    public void send(Alert alert) {
        send(alert, false);
    }

    /** Sends one alert to every configured phone; returns how many accepted it. Never throws. */
    @Override
    public int send(Alert alert, boolean critical) {
        return sendTo(alert, critical, services);
    }

    /** Sends one alert to these notify actions ({@code mobile_app_phone}, without "notify."). Never throws. */
    int sendTo(Alert alert, boolean critical, List<String> phones) {
        if (!configured) return 0;
        int sent = 0;
        for (String service : phones) {
            if (post(service, Map.of("title", TITLE, "message", NextSteps.withStep(alert), "data", data(alert, critical, service)))) {
                sent++;
            }
            if (critical && speakOn.contains(service)) speak(service, alert);
        }
        return sent;
    }

    /**
     * Speaks a critical alert on an Android phone at full alarm volume (#150). The Companion app turns the alarm volume
     * up to the maximum, speaks, and turns it back, also on silent: unlike a notification sound, which some phones play
     * like any other however the channel is set. Sent right after the notification, which carries the button.
     */
    private void speak(String service, Alert alert) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tts_text", "Kudil alert. " + NextSteps.spoken(alert));
        data.put("media_stream", "alarm_stream_max");
        data.put("ttl", 0);              // delivered at once, even while the phone sleeps
        data.put("priority", "high");
        post(service, Map.of("message", "TTS", "data", data));
    }

    /** Removes the alert's notification from these phones, e.g. once someone else said "I'm on it". Never throws. */
    void clear(String key, List<String> phones) {
        if (!configured) return;
        for (String service : phones) {
            post(service, Map.of("message", "clear_notification", "data", Map.of("tag", tag(key))));
        }
    }

    /** The phones alerts go to ({@code homehub.alerts.send-to}), without "notify.". */
    List<String> phones() {
        return services;
    }

    /**
     * What the Companion app needs besides the text. Ordinary alerts: a notice is passive (no sound, iPhone), a
     * problem is time-sensitive (through Focus, iPhone). Critical alerts (#101) ring as an alarm even on silent and in
     * Do Not Disturb, once the phone allows it (README: Critical alerts), and carry an "I'm on it" button.
     */
    private Map<String, Object> data(Alert alert, boolean critical, String service) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tag", tag(alert.key()));   // same tag = replaces the earlier notification
        data.put("group", "homehub");
        if (!link.isEmpty()) {
            data.put("url", link);           // iPhone: opened on tap
            data.put("clickAction", link);   // Android: opened on tap
        }
        if (critical) {
            data.put("ttl", 0);                     // Android: delivered at once, even while the phone sleeps
            data.put("priority", "high");
            data.put("channel", "alarm_stream");    // Android: plays on the alarm volume, through Do Not Disturb
            data.put("push", Map.of("sound", Map.of("name", "default", "critical", 1, "volume", 1.0),
                    "interruption-level", "critical"));   // iPhone: critical alert
            data.put("actions", List.of(Map.of("action", ackAction(service, alert.key()), "title", "I'm on it")));
        } else {
            data.put("push", Map.of("interruption-level", switch (alert.level()) {
                case PROBLEM -> "time-sensitive";
                case NOTICE -> "passive";
                case CLEARED -> "active";
            }));
        }
        return data;
    }

    /** Prefix of the "I'm on it" button's action: {@code KUDIL_ACK|<phone>|<alert key>}. */
    static final String ACK_ACTION = "KUDIL_ACK";

    static String ackAction(String service, String key) {
        return ACK_ACTION + "|" + service + "|" + key;
    }

    private static String tag(String key) {
        return "homehub-" + key;
    }

    private boolean post(String service, Map<String, Object> body) {
        try {
            client.post().uri("/services/notify/{service}", service)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            log.warn("Could not send the alert through notify.{}: Home Assistant answered HTTP {}",
                    service, ex.getStatusCode().value());
        } catch (RestClientException ex) {
            log.warn("Could not send the alert through notify.{}: Home Assistant is not reachable", service);
        }
        return false;
    }

    /** Valid notify actions from a setting, without "notify."; logs and skips anything else. */
    static List<String> targets(List<String> configured, String setting) {
        return configured.stream()
                .filter(target -> {
                    boolean valid = TARGET.matcher(target).matches();
                    if (!valid) log.warn("Ignoring {} entry '{}': expected notify.<name>", setting, target);
                    return valid;
                })
                .map(target -> target.substring("notify.".length()))
                .toList();
    }

    private static JdkClientHttpRequestFactory timeouts() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }
}
