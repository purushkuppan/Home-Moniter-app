package com.homehub.alerts;

import java.time.Duration;
import java.time.LocalTime;
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
 * @param offline    per device (the part before "/", e.g. {@code Refrigerator}): how long all its readings may be
 *                   unavailable before it is reported offline
 * @param connectionLostAfter how long Home Assistant may be unreachable before the dashboard says so
 * @param freezeBelow a freeze warning when tomorrow's forecast low is at or below this (Home Assistant's unit, e.g.
 *                    32 for °F); empty: no freeze warnings
 * @param freezeCheck local time of the daily freeze check, normally 19:00
 * @param rainFrom    a rain notice when today's forecast has at least this much rain or snow (Home Assistant's unit,
 *                    e.g. 0.1 for inches); empty: no rain notices
 * @param rainCheck   local time of the daily rain check, normally 07:00
 * @param lowBattery  a low-battery warning when a device's battery is at or below this percentage (any device with a
 *                    "battery" reading); empty: no battery warnings
 */
@ConfigurationProperties("homehub.alerts")
public record AlertProperties(
        List<String> sendTo,
        String link,
        Map<String, Duration> doorOpen,
        Map<String, Double> tooWarm,
        @DefaultValue("15m") Duration tooWarmFor,
        List<String> finished,
        Map<String, Duration> offline,
        @DefaultValue("10m") Duration connectionLostAfter,
        Double freezeBelow,
        @DefaultValue("19:00") LocalTime freezeCheck,
        Double rainFrom,
        @DefaultValue("07:00") LocalTime rainCheck,
        Double lowBattery) {

    public AlertProperties {
        offline = offline == null ? Map.of() : Map.copyOf(offline);
        if (connectionLostAfter == null) connectionLostAfter = Duration.ofMinutes(10);
        if (freezeCheck == null) freezeCheck = LocalTime.of(19, 0);
        if (rainCheck == null) rainCheck = LocalTime.of(7, 0);
        sendTo = sendTo == null ? List.of() : sendTo.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        link = link == null ? "" : link.trim();
        doorOpen = doorOpen == null ? Map.of() : Map.copyOf(doorOpen);
        tooWarm = tooWarm == null ? Map.of() : Map.copyOf(tooWarm);
        if (tooWarmFor == null) tooWarmFor = Duration.ofMinutes(15);
        finished = finished == null ? List.of() : finished.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
