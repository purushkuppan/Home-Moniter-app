package com.homehub.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.homehub.alerts.Alert.Level;
import com.homehub.events.Event;
import com.homehub.events.EventCleanup;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertRulesTests {

    private static final Instant T0 = Instant.parse("2026-11-06T18:00:00Z");
    private static final AlertProperties SETTINGS = new AlertProperties(
            List.of(), "",
            Map.of("Refrigerator/Fridge", Duration.ofMinutes(1), "Refrigerator/Freezer", Duration.ofMinutes(2)),
            Map.of("Refrigerator/Fridge", 45.0, "Refrigerator/Freezer", 10.0),
            Duration.ofMinutes(15),
            List.of("Washer", "Dryer"),
            Map.of("Refrigerator", Duration.ofMinutes(30)),
            Duration.ofMinutes(10),
            null, null, null, null, 20.0, Map.of("Kitchen leak sensor", "Water leak in the kitchen"));

    private final AlertRules rules = new AlertRules(SETTINGS);
    private long nextId = 1;

    private Event event(String device, String type, String value, Duration after) {
        return new Event(nextId++, device, type, value, T0.plus(after));
    }

    private static Duration sec(long s) {
        return Duration.ofSeconds(s);
    }

    @Test
    void doorOpenTooLongIsReportedOnceThenClearedWhenItCloses() {
        assertThat(rules.onEvent(event("Refrigerator/Fridge", "contact", "open", sec(0)))).isEmpty();

        assertThat(rules.check(T0.plus(sec(59)))).isEmpty();
        assertThat(rules.check(T0.plus(sec(65)))).extracting(Alert::level, Alert::device, Alert::message)
                .containsExactly(tuple(Level.PROBLEM, "Refrigerator/Fridge", "Fridge door has been open for 1 minute"));
        assertThat(rules.check(T0.plus(sec(300)))).as("reported once").isEmpty();
        assertThat(rules.active()).extracting(Alert::message, Alert::time)
                .containsExactly(tuple("Fridge door has been open for 1 minute", T0));

        assertThat(rules.onEvent(event("Refrigerator/Fridge", "contact", "closed", sec(200))))
                .extracting(Alert::key, Alert::level, Alert::message)
                .containsExactly(tuple("door:refrigerator/fridge", Level.CLEARED, "Fridge door closed. It was open for 3 min 20 s."));
        assertThat(rules.active()).isEmpty();
    }

    @Test
    void eachDoorHasItsOwnLimitAndShortOpeningsAreQuiet() {
        rules.onEvent(event("Refrigerator/Fridge", "contact", "open", sec(0)));
        rules.onEvent(event("Refrigerator/Fridge", "contact", "closed", sec(30)));   // quick grab: no alert
        rules.onEvent(event("Refrigerator/Freezer", "contact", "open", sec(0)));

        assertThat(rules.check(T0.plus(sec(90)))).isEmpty();   // freezer allows 2 minutes
        assertThat(rules.check(T0.plus(sec(125)))).extracting(Alert::message)
                .containsExactly("Freezer door has been open for 2 minutes");
    }

    @Test
    void unavailableDoesNotEndTheWatchAndRepeatedOpenKeepsTheStartTime() {
        rules.onEvent(event("Refrigerator/Fridge", "contact", "open", sec(0)));
        rules.onEvent(event("Refrigerator/Fridge", "contact", "unavailable", sec(10)));
        rules.onEvent(event("Refrigerator/Fridge", "contact", "open", sec(30)));

        assertThat(rules.check(T0.plus(sec(61)))).hasSize(1);
    }

    @Test
    void temperatureMustStayTooHighBeforeAnAlert() {
        rules.onEvent(event("Refrigerator/Fridge", "temperature", "46 F", sec(0)));
        rules.onEvent(event("Refrigerator/Fridge", "temperature", "44 F", Duration.ofMinutes(5)));   // recovered
        assertThat(rules.check(T0.plus(Duration.ofMinutes(20)))).isEmpty();

        rules.onEvent(event("Refrigerator/Fridge", "temperature", "46 F", Duration.ofMinutes(20)));
        rules.onEvent(event("Refrigerator/Fridge", "temperature", "unavailable", Duration.ofMinutes(25)));
        rules.onEvent(event("Refrigerator/Fridge", "temperature", "47.5 F", Duration.ofMinutes(30)));
        assertThat(rules.check(T0.plus(Duration.ofMinutes(34)))).isEmpty();
        assertThat(rules.check(T0.plus(Duration.ofMinutes(35)))).extracting(Alert::message)
                .containsExactly("Fridge is too warm: 47.5 °F (above 45 °F for 15 minutes)");

        assertThat(rules.onEvent(event("Refrigerator/Fridge", "temperature", "45 F", Duration.ofMinutes(50))))
                .extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.CLEARED, "Fridge temperature is back to normal: 45 °F"));
    }

    @Test
    void washerAndDryerReportTheEndOfACycleOnce() {
        assertThat(rules.onEvent(event("Washer", "machineState", "stop", sec(0)))).as("was not running").isEmpty();

        rules.onEvent(event("Washer", "machineState", "run", sec(1)));
        rules.onEvent(event("Washer", "jobState", "spin", sec(2)));
        rules.onEvent(event("Washer", "machineState", "pause", sec(3)));
        assertThat(rules.onEvent(event("Washer", "jobState", "finish", sec(4))))
                .extracting(Alert::level, Alert::device, Alert::message)
                .containsExactly(tuple(Level.NOTICE, "Washer", "Washer finished"));
        assertThat(rules.onEvent(event("Washer", "machineState", "stop", sec(5)))).as("same cycle").isEmpty();

        // Dryers say "finished"; a stop without one counts too.
        rules.onEvent(event("Dryer", "machineState", "run", sec(10)));
        assertThat(rules.onEvent(event("Dryer", "machineState", "stop", sec(11)))).extracting(Alert::message)
                .containsExactly("Dryer finished");
        assertThat(rules.active()).as("news, not a lasting problem").isEmpty();
    }

    @Test
    void devicesWithoutRulesAndOwnAlertsAreIgnored() {
        assertThat(rules.onEvent(event("Garage", "contact", "open", sec(0)))).isEmpty();
        assertThat(rules.onEvent(event("Dishwasher", "machineState", "run", sec(0)))).isEmpty();
        assertThat(rules.onEvent(event("Dishwasher", "machineState", "stop", sec(1)))).isEmpty();
        assertThat(rules.onEvent(event("Refrigerator/Fridge", "alert", "open", sec(0)))).isEmpty();
        assertThat(rules.check(T0.plus(Duration.ofHours(1)))).isEmpty();
    }

    @Test
    void namesMatchWithoutRegardToCase() {
        rules.onEvent(event("refrigerator/FRIDGE", "contact", "open", sec(0)));
        assertThat(rules.check(T0.plus(sec(60)))).extracting(Alert::message)
                .containsExactly("FRIDGE door has been open for 1 minute");
    }

    @Test
    void afterARestartAnOpenDoorIsStillWatchedButNotReportedTwice() {
        // Before the restart: freezer door opened and was reported; fridge door opened later, not reported yet.
        List<Event> latestPerReading = List.of(
                event("Refrigerator/Freezer", "contact", "open", sec(0)),
                event("Refrigerator/Freezer", "alert", "Freezer door has been open for 2 minutes", sec(120)),
                event("Refrigerator/Fridge", "contact", "open", Duration.ofMinutes(5)),
                event("Washer", "machineState", "run", sec(0)));
        rules.seed(latestPerReading);

        assertThat(rules.active()).extracting(Alert::message).containsExactly("Freezer door has been open for 2 minutes");
        assertThat(rules.check(T0.plus(Duration.ofMinutes(10)))).extracting(Alert::message)
                .containsExactly("Fridge door has been open for 5 minutes");
        assertThat(rules.onEvent(event("Washer", "machineState", "stop", Duration.ofMinutes(11))))
                .extracting(Alert::message).containsExactly("Washer finished");
    }

    @Test
    void freezerAllClearUsesTheSameWording() {
        rules.onEvent(event("Refrigerator/Freezer", "contact", "open", sec(0)));
        rules.check(T0.plus(sec(125)));
        assertThat(rules.onEvent(event("Refrigerator/Freezer", "contact", "closed", Duration.ofMinutes(12))))
                .extracting(Alert::message).containsExactly("Freezer door closed. It was open for 12 minutes.");
    }

    @Test
    void doorOpenTimeIsExactForShortOpenings() {
        assertThat(AlertRules.openFor(sec(45))).isEqualTo("45 s");
        assertThat(AlertRules.openFor(sec(110))).isEqualTo("1 min 50 s");
        assertThat(AlertRules.openFor(Duration.ofMinutes(3))).isEqualTo("3 min");
        assertThat(AlertRules.openFor(sec(599))).isEqualTo("9 min 59 s");
        assertThat(AlertRules.openFor(Duration.ofMinutes(12))).isEqualTo("12 minutes");
        assertThat(AlertRules.openFor(Duration.ofMinutes(65))).isEqualTo("1 hour 5 minutes");
    }

    // ------------------------------------------------------------ offline devices

    /** The fridge's four readings, as the hub stores them. */
    private void fridgeReadings(String value, Duration after) {
        rules.onEvent(event("Refrigerator/Fridge", "contact", value.equals("ok") ? "closed" : value, after));
        rules.onEvent(event("Refrigerator/Freezer", "contact", value.equals("ok") ? "closed" : value, after));
        rules.onEvent(event("Refrigerator/Fridge", "temperature", value.equals("ok") ? "44 F" : value, after));
        rules.onEvent(event("Refrigerator/Freezer", "temperature", value.equals("ok") ? "5 F" : value, after));
    }

    @Test
    void aDeviceIsOfflineWhenAllItsReadingsAreUnavailableFor30Minutes() {
        fridgeReadings("ok", sec(0));
        fridgeReadings("unavailable", Duration.ofMinutes(1));

        assertThat(rules.check(T0.plus(Duration.ofMinutes(30)))).as("29 minutes offline").isEmpty();
        assertThat(rules.check(T0.plus(Duration.ofMinutes(31)))).extracting(Alert::key, Alert::level, Alert::device, Alert::message)
                .containsExactly(tuple("offline:refrigerator", Level.PROBLEM, "Refrigerator",
                        "Refrigerator is offline. No data for 30 minutes."));
        assertThat(rules.check(T0.plus(Duration.ofMinutes(60)))).as("reported once").isEmpty();
        assertThat(rules.active()).extracting(Alert::device, Alert::time)
                .containsExactly(tuple("Refrigerator", T0.plus(Duration.ofMinutes(1))));

        // Any reading coming back means the fridge is online again.
        assertThat(rules.onEvent(event("Refrigerator/Fridge", "temperature", "44 F", Duration.ofMinutes(43))))
                .extracting(Alert::key, Alert::level, Alert::message)
                .containsExactly(tuple("offline:refrigerator", Level.CLEARED,
                        "Refrigerator is back online. It was offline for 42 minutes."));
        assertThat(rules.active()).isEmpty();
    }

    @Test
    void oneUnavailableReadingOrAShortOutageIsNotOffline() {
        fridgeReadings("ok", sec(0));
        rules.onEvent(event("Refrigerator/Fridge", "temperature", "unavailable", sec(10)));   // one sensor only
        assertThat(rules.check(T0.plus(Duration.ofHours(2)))).isEmpty();

        fridgeReadings("unavailable", Duration.ofHours(2));
        assertThat(rules.onEvent(event("Refrigerator/Fridge", "contact", "closed", Duration.ofHours(2).plusMinutes(5))))
                .as("back before the alert: nothing to clear").isEmpty();
        assertThat(rules.check(T0.plus(Duration.ofHours(3)))).isEmpty();
    }

    @Test
    void commandsAndUnwatchedDevicesDontCount() {
        fridgeReadings("unavailable", sec(0));
        rules.onEvent(event("Refrigerator", "command", "Fridge temperature set to 43 °F", sec(5)));   // not a reading
        rules.onEvent(event("Washer", "machineState", "unavailable", sec(0)));                       // not watched
        assertThat(rules.check(T0.plus(Duration.ofMinutes(31)))).extracting(Alert::device).containsExactly("Refrigerator");
    }

    @Test
    void afterARestartWhileOfflineItIsNotReportedTwice() {
        rules.seed(List.of(
                event("Refrigerator/Fridge", "contact", "unavailable", sec(0)),
                event("Refrigerator/Fridge", "temperature", "unavailable", sec(0)),
                event("Refrigerator", "alert", "Refrigerator is offline. No data for 30 minutes.", Duration.ofMinutes(30))));

        assertThat(rules.active()).extracting(Alert::message).containsExactly("Refrigerator is offline. No data for 30 minutes.");
        assertThat(rules.check(T0.plus(Duration.ofHours(2)))).isEmpty();
        assertThat(rules.onEvent(event("Refrigerator/Fridge", "contact", "closed", Duration.ofHours(2))))
                .extracting(Alert::message).containsExactly("Refrigerator is back online. It was offline for 2 hours.");
    }

    // ------------------------------------------------------------ Home Assistant connection

    @Test
    void lostConnectionIsShownAfter10MinutesAndClearedWhenBack() {
        assertThat(rules.onConnection(true, T0)).isEmpty();
        assertThat(rules.onConnection(false, T0.plus(sec(5)))).isEmpty();
        assertThat(rules.check(T0.plus(Duration.ofMinutes(10)))).as("9 min 55 s").isEmpty();

        List<Alert> lost = rules.check(T0.plus(Duration.ofMinutes(11)));
        assertThat(lost).extracting(Alert::key, Alert::level, Alert::device, Alert::message)
                .containsExactly(tuple("connection:homeassistant", Level.PROBLEM, "Home Assistant",
                        "Can't reach Home Assistant. No contact for 10 minutes."));
        assertThat(lost.get(0).reachesPhone()).as("phones are reached through Home Assistant").isFalse();
        assertThat(rules.active()).extracting(Alert::time).containsExactly(T0.plus(sec(5)));
        assertThat(rules.onConnection(false, T0.plus(Duration.ofMinutes(12)))).as("still down").isEmpty();

        List<Alert> back = rules.onConnection(true, T0.plus(Duration.ofMinutes(25)).plus(sec(5)));
        assertThat(back).extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.CLEARED, "Kudil lost Home Assistant for 25 minutes. Back now."));
        assertThat(back.get(0).reachesPhone()).isTrue();
        assertThat(rules.active()).isEmpty();
    }

    @Test
    void aShortConnectionBlipIsQuiet() {
        rules.onConnection(false, T0);
        assertThat(rules.onConnection(true, T0.plus(Duration.ofMinutes(3)))).isEmpty();
        assertThat(rules.check(T0.plus(Duration.ofMinutes(20)))).isEmpty();
    }

    @Test
    void otherAlertsReachPhones() {
        rules.onEvent(event("Refrigerator/Fridge", "contact", "open", sec(0)));
        assertThat(rules.check(T0.plus(sec(61)))).allMatch(Alert::reachesPhone);
    }

    // ---------------------------------------------------------------- low battery (#15)

    @Test
    void aLowBatteryIsReportedAtOnceAndOnlyOnce() {
        assertThat(rules.onEvent(event("Leak sensor", "battery", "35 %", sec(0)))).isEmpty();
        assertThat(rules.onEvent(event("Leak sensor", "battery", "20 %", sec(60))))
                .extracting(Alert::key, Alert::level, Alert::message)
                .containsExactly(tuple("battery:leak sensor", Level.NOTICE, "Leak sensor battery is low: 20%."));
        assertThat(rules.onEvent(event("Leak sensor", "battery", "12 %", sec(120)))).as("not again").isEmpty();
        assertThat(rules.onEvent(event("Leak sensor", "battery", "unavailable", sec(130)))).isEmpty();
        assertThat(rules.active()).extracting(Alert::message).containsExactly("Leak sensor battery is low: 20%.");
    }

    @Test
    void aBatteryThatStaysLowIsMentionedAgainAtMostWeekly() {
        rules.onEvent(event("Leak sensor", "battery", "18 %", sec(0)));
        rules.onEvent(event("Leak sensor", "battery", "11 %", Duration.ofDays(3)));
        assertThat(rules.check(T0.plus(Duration.ofDays(6)))).isEmpty();
        assertThat(rules.check(T0.plus(Duration.ofDays(7)))).extracting(Alert::key, Alert::level, Alert::message)
                .containsExactly(tuple("battery:leak sensor", Level.NOTICE, "Leak sensor battery is still low: 11%."));
        assertThat(rules.check(T0.plus(Duration.ofDays(10)))).as("not again for a week").isEmpty();
        assertThat(rules.check(T0.plus(Duration.ofDays(14)))).hasSize(1);
        rules.onEvent(event("Leak sensor", "battery", "100 %", Duration.ofDays(15)));
        assertThat(rules.check(T0.plus(Duration.ofDays(30)))).as("replaced: no more reminders").isEmpty();
    }

    @Test
    void aBatteryWobblingAroundTheLimitIsNotFineAgainUntilReplaced() {
        rules.onEvent(event("Leak sensor", "battery", "19", sec(0)));
        assertThat(rules.onEvent(event("Leak sensor", "battery", "22", sec(60)))).as("within the margin").isEmpty();
        assertThat(rules.onEvent(event("Leak sensor", "battery", "18", sec(120)))).as("still the same warning").isEmpty();
        assertThat(rules.onEvent(event("Leak sensor", "battery", "100 %", sec(180))))
                .extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.CLEARED, "Leak sensor battery is fine again: 100%."));
        assertThat(rules.active()).isEmpty();
    }

    @Test
    void aBatterySensorThatOnlySaysLowWorksToo() {
        assertThat(rules.onEvent(event("Garage/Door sensor", "battery", "on", sec(0)))).extracting(Alert::message)
                .containsExactly("Door sensor battery is low.");
        assertThat(rules.onEvent(event("Garage/Door sensor", "battery", "off", sec(60)))).extracting(Alert::message)
                .containsExactly("Door sensor battery is fine again.");
    }

    @Test
    void afterARestartALowBatteryIsStillActiveButNotReportedTwice() {
        rules.seed(List.of(event("Leak sensor", "battery", "15 %", sec(0)),
                event("Leak sensor", "alert", "Leak sensor battery is low: 15%. Replace it soon.", sec(0))));

        assertThat(rules.active()).extracting(Alert::message).containsExactly("Leak sensor battery is low: 15%.");
        assertThat(rules.onEvent(event("Leak sensor", "battery", "14 %", sec(60)))).isEmpty();
        assertThat(rules.check(T0.plus(Duration.ofHours(1)))).isEmpty();
    }

    @Test
    void noBatteryWarningsWithoutALimit() {
        AlertRules off = new AlertRules(new AlertProperties(List.of(), "", Map.of(), Map.of(), null, List.of(), Map.of(),
                null, null, null, null, null, null, null));
        assertThat(off.onEvent(event("Leak sensor", "battery", "5 %", sec(0)))).isEmpty();
        assertThat(off.active()).isEmpty();
    }

    // ---------------------------------------------------------------- water leak (#36)

    @Test
    void aWaterLeakIsReportedAtOnceAndClearedWhenDry() {
        assertThat(rules.onEvent(event("Kitchen leak sensor", "leak", "dry", sec(0)))).isEmpty();
        assertThat(rules.onEvent(event("Kitchen leak sensor", "leak", "wet", sec(60))))
                .extracting(Alert::key, Alert::level, Alert::device, Alert::message)
                .containsExactly(tuple("leak:kitchen leak sensor", Level.PROBLEM, "Kitchen leak sensor", "Water leak in the kitchen"));
        assertThat(rules.onEvent(event("Kitchen leak sensor", "leak", "unavailable", sec(90)))).as("still a leak").isEmpty();
        assertThat(rules.onEvent(event("Kitchen leak sensor", "leak", "wet", sec(120)))).as("reported once").isEmpty();
        assertThat(rules.active()).extracting(Alert::message).containsExactly("Water leak in the kitchen");

        assertThat(rules.onEvent(event("Kitchen leak sensor", "leak", "dry", Duration.ofMinutes(10))))
                .extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.CLEARED, "Kitchen leak sensor is dry again."));
        assertThat(rules.active()).isEmpty();
    }

    @Test
    void everyLeakSensorIsWatchedEvenWithoutAMessage() {
        assertThat(rules.onEvent(event("Laundry/Leak sensor", "leak", "wet", sec(0)))).extracting(Alert::message)
                .containsExactly("Water leak: Leak sensor");
    }

    @Test
    void afterARestartDuringALeakItIsStillActiveButNotReportedTwice() {
        rules.seed(List.of(event("Kitchen leak sensor", "leak", "wet", sec(0)),
                event("Kitchen leak sensor", "alert", "Water leak in the kitchen", sec(0))));

        assertThat(rules.active()).extracting(Alert::message).containsExactly("Water leak in the kitchen");
        assertThat(rules.onEvent(event("Kitchen leak sensor", "leak", "wet", sec(60)))).isEmpty();
        assertThat(rules.check(T0.plus(Duration.ofHours(1)))).isEmpty();
    }

    // ---------------------------------------------------------------- ready-made alerts by kind (#100)

    private static final java.time.ZoneId DENTON = java.time.ZoneId.of("America/Chicago");
    private static final Instant NOON = Instant.parse("2026-11-12T18:00:00Z");      // 12:00 in Denton
    private static final Instant NIGHT = Instant.parse("2026-11-13T05:00:00Z");     // 23:00 in Denton

    /** Rules with the ready-made alerts on (10 minutes, night 22:00-06:00) and the Devices list's settings. */
    private static AlertRules withDefaults(Map<String, Duration> perDevice) {
        return new AlertRules(SETTINGS, DefaultAlertProperties.defaults(), DENTON,
                device -> java.util.Optional.ofNullable(perDevice.get(device)));
    }

    private static Event at(String device, String type, String value, Instant time) {
        return new Event(null, device, type, value, time);
    }

    @Test
    void anyDoorLeftOpenIsANoticeByDayAndClosingIsReported() {
        AlertRules rules = withDefaults(Map.of());
        rules.onEvent(at("Back door", "contact", "open", NOON));

        assertThat(rules.check(NOON.plus(Duration.ofMinutes(9)))).isEmpty();
        assertThat(rules.check(NOON.plus(Duration.ofMinutes(10)))).extracting(Alert::key, Alert::level, Alert::message)
                .containsExactly(tuple("door:back door", Level.NOTICE, "Back door has been open for 10 minutes"));
        assertThat(rules.check(NOON.plus(Duration.ofMinutes(20)))).as("once").isEmpty();
        assertThat(rules.onEvent(at("Back door", "contact", "closed", NOON.plus(Duration.ofMinutes(12)))))
                .extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.CLEARED, "Back door closed. It was open for 12 minutes."));
    }

    @Test
    void atNightADoorLeftOpenIsAWarning() {
        AlertRules rules = withDefaults(Map.of());
        rules.onEvent(at("Garage/Side window", "contact", "open", NIGHT));
        assertThat(rules.check(NIGHT.plus(Duration.ofMinutes(10)))).extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.PROBLEM, "Side window has been open for 10 minutes"));
    }

    @Test
    void theDevicesListCanShortenOrSwitchOffTheOpenDoorAlert() {
        AlertRules rules = withDefaults(Map.of("Back door", Duration.ofMinutes(2), "Patio door", Duration.ZERO));
        rules.onEvent(at("Back door", "contact", "open", NOON));
        rules.onEvent(at("Patio door", "contact", "open", NOON));

        assertThat(rules.check(NOON.plus(Duration.ofHours(3)))).extracting(Alert::message)
                .containsExactly("Back door has been open for 3 hours");
        assertThat(rules.active()).extracting(Alert::device).containsExactly("Back door");
    }

    @Test
    void theFridgeKeepsItsOwnLimitAndIsAWarningEvenByDay() {
        AlertRules rules = withDefaults(Map.of("Refrigerator", Duration.ZERO));   // the list's setting doesn't apply
        rules.onEvent(at("Refrigerator/Fridge", "contact", "open", NOON));
        assertThat(rules.check(NOON.plus(Duration.ofMinutes(1)))).extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.PROBLEM, "Fridge door has been open for 1 minute"));
    }

    @Test
    void withoutReadyMadeAlertsOnlyConfiguredDoorsAlert() {
        rules.onEvent(event("Back door", "contact", "open", sec(0)));
        assertThat(rules.check(T0.plus(Duration.ofHours(1)))).isEmpty();
    }

    @Test
    void afterARestartADoorAlreadyReportedByDayIsNotReportedAgain() {
        AlertRules rules = withDefaults(Map.of());
        rules.seed(List.of(new Event(1L, "Back door", "contact", "open", NOON),
                new Event(2L, "Back door", "notice", "Back door has been open for 10 minutes", NOON.plus(Duration.ofMinutes(10)))));
        assertThat(rules.check(NOON.plus(Duration.ofMinutes(30)))).isEmpty();
        assertThat(rules.active()).extracting(Alert::message).containsExactly("Back door has been open for 10 minutes");
    }

    @Test
    void smokeAndCarbonMonoxideAlertAtOnceAndClear() {
        AlertRules rules = withDefaults(Map.of());
        assertThat(rules.onEvent(at("Hall smoke alarm", "smoke", "clear", NOON))).isEmpty();
        assertThat(rules.onEvent(at("Hall smoke alarm", "smoke", "detected", NOON))).extracting(Alert::key, Alert::level, Alert::message)
                .containsExactly(tuple("smoke:hall smoke alarm", Level.PROBLEM, "Smoke detected: Hall smoke alarm."));
        assertThat(rules.onEvent(at("Hall smoke alarm", "smoke", "detected", NOON.plus(Duration.ofMinutes(1))))).as("once").isEmpty();
        assertThat(rules.onEvent(at("Hall CO alarm", "co", "detected", NOON))).extracting(Alert::key, Alert::message)
                .containsExactly(tuple("co:hall co alarm", "Carbon monoxide detected: Hall CO alarm."));
        assertThat(rules.active()).hasSize(2);

        assertThat(rules.onEvent(at("Hall smoke alarm", "smoke", "clear", NOON.plus(Duration.ofMinutes(4)))))
                .extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.CLEARED, "Hall smoke alarm is clear again. Smoke was detected for 4 min."));
        assertThat(rules.active()).extracting(Alert::device).containsExactly("Hall CO alarm");
    }

    @Test
    void aJammedLockIsAWarningUntilItWorksAgain() {
        AlertRules rules = withDefaults(Map.of());
        assertThat(rules.onEvent(at("Front door lock", "lock", "locked", NOON))).isEmpty();
        assertThat(rules.onEvent(at("Front door lock", "lock", "jammed", NOON))).extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.PROBLEM, "Front door lock is jammed."));
        assertThat(rules.onEvent(at("Front door lock", "lock", "locked", NOON.plus(Duration.ofMinutes(2)))))
                .extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.CLEARED, "Front door lock works again: locked."));
    }

    /** What the Devices list says for the appliances (#100), over the configured defaults in SETTINGS. */
    private static AlertRules withChoices(Map<String, Duration> openFor, Map<String, Double> tooWarm,
                                          Map<String, Boolean> finished, Map<String, Duration> offline) {
        return new AlertRules(SETTINGS, DefaultAlertProperties.defaults(), DENTON, new DeviceAlertSettings() {
            @Override public java.util.Optional<Duration> openFor(String device) { return java.util.Optional.ofNullable(openFor.get(device)); }
            @Override public java.util.Optional<Double> tooWarm(String device) { return java.util.Optional.ofNullable(tooWarm.get(device)); }
            @Override public java.util.Optional<Boolean> finished(String device) { return java.util.Optional.ofNullable(finished.get(device)); }
            @Override public java.util.Optional<Duration> offlineAfter(String device) { return java.util.Optional.ofNullable(offline.get(device)); }
        });
    }

    @Test
    void theDevicesListCanTuneTheFridgeDoors() {
        AlertRules rules = withChoices(Map.of("Refrigerator/Fridge", Duration.ofMinutes(5), "Refrigerator/Freezer", Duration.ZERO),
                Map.of(), Map.of(), Map.of());
        rules.onEvent(at("Refrigerator/Fridge", "contact", "open", NOON));
        rules.onEvent(at("Refrigerator/Freezer", "contact", "open", NOON));

        assertThat(rules.check(NOON.plus(Duration.ofMinutes(2)))).as("not after the configured 1 minute any more").isEmpty();
        assertThat(rules.check(NOON.plus(Duration.ofMinutes(5)))).extracting(Alert::level, Alert::message)
                .containsExactly(tuple(Level.PROBLEM, "Fridge door has been open for 5 minutes"));   // still a warning by day
        assertThat(rules.check(NOON.plus(Duration.ofHours(1)))).as("the freezer's is switched off").isEmpty();
    }

    @Test
    void theDevicesListCanTuneOrSwitchOffTooWarm() {
        AlertRules rules = withChoices(Map.of(), Map.of("Refrigerator/Fridge", 40.0, "Refrigerator/Freezer", Double.NaN),
                Map.of(), Map.of());
        rules.onEvent(at("Refrigerator/Fridge", "temperature", "42 F", NOON));        // under the configured 45
        rules.onEvent(at("Refrigerator/Freezer", "temperature", "30 F", NOON));       // far above the configured 10
        assertThat(rules.check(NOON.plus(Duration.ofMinutes(15)))).extracting(Alert::message)
                .containsExactly("Fridge is too warm: 42 °F (above 40 °F for 15 minutes)");
    }

    @Test
    void theDevicesListCanSwitchCycleNoticesAndOfflineAlerts() {
        AlertRules rules = withChoices(Map.of(), Map.of(), Map.of("Washer", false, "Dishwasher", true),
                Map.of("Refrigerator", Duration.ZERO));
        rules.onEvent(at("Washer", "machineState", "run", NOON));
        assertThat(rules.onEvent(at("Washer", "machineState", "stop", NOON.plus(Duration.ofMinutes(50))))).isEmpty();
        rules.onEvent(at("Dishwasher", "machineState", "run", NOON));
        assertThat(rules.onEvent(at("Dishwasher", "machineState", "stop", NOON.plus(Duration.ofMinutes(90)))))
                .extracting(Alert::message).containsExactly("Dishwasher finished");

        rules.onEvent(at("Refrigerator/Fridge", "temperature", "unavailable", NOON));
        rules.onEvent(at("Refrigerator/Fridge", "contact", "unavailable", NOON));
        assertThat(rules.check(NOON.plus(Duration.ofHours(2)))).as("offline alert switched off").isEmpty();
    }

    @Test
    void anApplianceCanGetAnOfflineAlertOfItsOwn() {
        AlertRules rules = withChoices(Map.of(), Map.of(), Map.of(), Map.of("Washer", Duration.ofMinutes(15)));
        rules.onEvent(at("Washer", "machineState", "unavailable", NOON));
        assertThat(rules.check(NOON.plus(Duration.ofMinutes(15)))).extracting(Alert::message)
                .containsExactly("Washer is offline. No data for 15 minutes.");
    }

    @Test
    void doorNamesReadNaturally() {
        assertThat(AlertRules.doorName("Refrigerator/Fridge")).isEqualTo("Fridge door");
        assertThat(AlertRules.doorName("Back door")).isEqualTo("Back door");
        assertThat(AlertRules.doorName("Bedroom window")).isEqualTo("Bedroom window");
        assertThat(AlertRules.doorName("Garage")).isEqualTo("Garage");
    }

    @Test
    void theNightSpansMidnight() {
        DefaultAlertProperties d = DefaultAlertProperties.defaults();
        assertThat(d.isNight(java.time.LocalTime.of(23, 0))).isTrue();
        assertThat(d.isNight(java.time.LocalTime.of(5, 59))).isTrue();
        assertThat(d.isNight(java.time.LocalTime.of(6, 0))).isFalse();
        assertThat(d.isNight(java.time.LocalTime.of(21, 59))).isFalse();
    }

    @Test
    void durationsReadNaturally() {
        assertThat(AlertRules.human(sec(40))).isEqualTo("less than a minute");
        assertThat(AlertRules.human(sec(61))).isEqualTo("1 minute");
        assertThat(AlertRules.human(Duration.ofMinutes(60))).isEqualTo("1 hour");
        assertThat(AlertRules.human(Duration.ofMinutes(125))).isEqualTo("2 hours 5 minutes");
    }

    @Test
    void eventCleanupKeepsEveryAlertTypeForTheAlertPeriod() {
        assertThat(EventCleanup.ALERT_TYPES).containsExactlyInAnyOrderElementsOf(Alert.EVENT_TYPES);
    }
}
