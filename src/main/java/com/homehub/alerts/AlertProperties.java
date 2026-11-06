package com.homehub.alerts;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Alert settings, bound from {@code homehub.alerts.*}. Device names are the ones on the dashboard, for example
 * {@code Refrigerator/Fridge}; in a properties file write them in brackets so the slash is kept:
 * <pre>
 * homehub.alerts.door-open.[Refrigerator/Fridge]=1m
 * homehub.alerts.too-warm.[Refrigerator/Fridge]=45
 * </pre>
 *
 * @param sendTo     Home Assistant notify actions to send alerts to, e.g. {@code notify.mobile_app_phone}.
 *                   Empty: alerts are only shown on the dashboard.
 * @param link       page that opens when a notification is tapped (the dashboard), optional
 * @param doorOpen   per door: how long it may stay open before an alert
 * @param tooWarm    per temperature reading: the highest normal value, in the reading's own unit
 * @param tooWarmFor how long a temperature must stay above its limit before an alert
 * @param finished   washers and dryers to report when their cycle ends
 */
@ConfigurationProperties("homehub.alerts")
public record AlertProperties(
        List<String> sendTo,
        String link,
        Map<String, Duration> doorOpen,
        Map<String, Double> tooWarm,
        @DefaultValue("15m") Duration tooWarmFor,
        List<String> finished) {

    public AlertProperties {
        sendTo = sendTo == null ? List.of() : sendTo.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        link = link == null ? "" : link.trim();
        doorOpen = doorOpen == null ? Map.of() : Map.copyOf(doorOpen);
        tooWarm = tooWarm == null ? Map.of() : Map.copyOf(tooWarm);
        if (tooWarmFor == null) tooWarmFor = Duration.ofMinutes(15);
        finished = finished == null ? List.of() : finished.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
