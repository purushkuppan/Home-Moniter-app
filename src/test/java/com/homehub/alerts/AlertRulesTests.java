package com.homehub.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.homehub.alerts.Alert.Level;
import com.homehub.events.Event;
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
            List.of("Washer", "Dryer"));

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

    @Test
    void durationsReadNaturally() {
        assertThat(AlertRules.human(sec(40))).isEqualTo("less than a minute");
        assertThat(AlertRules.human(sec(61))).isEqualTo("1 minute");
        assertThat(AlertRules.human(Duration.ofMinutes(60))).isEqualTo("1 hour");
        assertThat(AlertRules.human(Duration.ofMinutes(125))).isEqualTo("2 hours 5 minutes");
    }
}
