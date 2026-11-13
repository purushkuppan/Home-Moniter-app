package com.homehub.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.homehub.alerts.Alert;
import com.homehub.alerts.Alert.Level;
import com.homehub.events.Event;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class HubMonitorTests {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Instant T0 = Instant.parse("2026-11-13T08:05:00Z");   // 2:05 AM in Denton
    private static final PowerSupply.Reading MAINS = new PowerSupply.Reading(true, 100, null, null);

    private final HubMonitor monitor = new HubMonitor(HubProperties.defaults(), CHICAGO);

    private static PowerSupply.Reading battery(int percent) {
        return new PowerSupply.Reading(false, percent, null, null);
    }

    private static Instant at(Duration d) {
        return T0.plus(d);
    }

    private HubMonitor.Outcome power(Instant now, PowerSupply.Reading reading) {
        return monitor.check(now, reading, null, null, "");
    }

    @Test
    void aPowerCutIsLoggedAndAlertedAndItsEndSaysHowLong() {
        assertThat(power(T0.minusSeconds(30), MAINS).alerts()).as("first reading: no news").isEmpty();

        HubMonitor.Outcome out = power(T0, battery(97));
        assertThat(out.log()).extracting(Event::device, Event::type, Event::value).containsExactly(tuple("Home", "power", "lost"));
        assertThat(out.alerts()).extracting(Alert::key, Alert::level, Alert::message).containsExactly(tuple("power:home",
                Level.PROBLEM, "Power is out at home since 2:05 AM. The hub is running on its battery: 97%."));
        assertThat(monitor.active()).extracting(Alert::key).containsExactly("power:home");
        assertThat(power(at(Duration.ofMinutes(1)), battery(96)).alerts()).as("once").isEmpty();

        out = power(at(Duration.ofMinutes(72)), MAINS);
        assertThat(out.log()).extracting(Event::value).containsExactly("restored after 1 hour 12 minutes");
        assertThat(out.alerts()).extracting(Alert::level, Alert::message).containsExactly(
                tuple(Level.CLEARED, "Power is back at home. It was out 1 hour 12 minutes, from 2:05 AM."));
        assertThat(monitor.active()).isEmpty();
    }

    @Test
    void theHubBatteryWarnsAt30And10PercentAndIsFineOnceCharging() {
        power(T0.minusSeconds(30), MAINS);
        power(T0, battery(40));
        assertThat(power(at(Duration.ofMinutes(30)), battery(30)).alerts()).extracting(Alert::key, Alert::message)
                .containsExactly(tuple("hubbattery:hub", "The hub's battery is at 30%. It shuts down when it runs out."));
        assertThat(power(at(Duration.ofMinutes(35)), battery(29)).alerts()).as("once per level").isEmpty();
        assertThat(power(at(Duration.ofMinutes(60)), battery(9)).alerts()).extracting(Alert::message)
                .containsExactly("The hub's battery is at 9%. It shuts down when it runs out.");

        assertThat(power(at(Duration.ofMinutes(70)), MAINS).alerts()).extracting(Alert::key, Alert::level)
                .containsExactly(tuple("power:home", Level.CLEARED), tuple("hubbattery:hub", Level.CLEARED));
    }

    @Test
    void afterALongCutItAsksToCheckTheFreezerWithItsTemperature() {
        power(T0.minusSeconds(30), MAINS);
        power(T0, battery(90));
        HubMonitor.Outcome out = monitor.check(at(Duration.ofMinutes(150)), MAINS, null, 14.0, " °F");
        assertThat(out.alerts()).extracting(Alert::message).containsExactly("Power is back at home. It was out 2 hours "
                + "30 minutes, from 2:05 AM. That's long enough to check the freezer: it reads 14 °F now.");
    }

    @Test
    void startingOnBatteryShowsTheCutButSendsNothing() {
        assertThat(power(T0, battery(80)).alerts()).isEmpty();
        assertThat(monitor.active()).extracting(Alert::key).containsExactly("power:home");
    }

    @Test
    void internetDownAfterThreeMinutesHoldsPowerAlertsUntilItIsBack() {
        power(T0.minusSeconds(60), MAINS);
        assertThat(monitor.check(T0, MAINS, false, null, "").alerts()).as("a blip").isEmpty();
        HubMonitor.Outcome down = monitor.check(at(Duration.ofMinutes(3)), MAINS, false, null, "");
        assertThat(down.alerts()).extracting(Alert::key, Alert::level, Alert::message)
                .containsExactly(tuple("internet:home", Level.PROBLEM, "Internet is down at home since 2:05 AM."));
        assertThat(down.log()).extracting(Event::type, Event::value, Event::time).containsExactly(tuple("internet", "lost", T0));

        // The power goes out while the internet is down: logged now, told when phones can be reached.
        HubMonitor.Outcome cut = monitor.check(at(Duration.ofMinutes(10)), battery(95), false, null, "");
        assertThat(cut.log()).extracting(Event::value).containsExactly("lost");
        assertThat(cut.alerts()).isEmpty();

        HubMonitor.Outcome back = monitor.check(at(Duration.ofMinutes(40)), battery(90), true, null, "");
        assertThat(back.alerts()).extracting(Alert::key, Alert::level).containsExactly(
                tuple("power:home", Level.PROBLEM), tuple("internet:home", Level.CLEARED));
        assertThat(back.alerts().get(1).message()).isEqualTo("Internet is back at home. It was down 40 minutes, from 2:05 AM.");
        assertThat(back.log()).extracting(Event::value).containsExactly("restored after 40 minutes");
    }

    @Test
    void batteryHealthIsLoggedWeeklyAndAWornBatteryIsMentionedOnce() {
        PowerSupply.Reading worn = new PowerSupply.Reading(true, 100, 55, 34.0);
        HubMonitor.Outcome first = monitor.check(T0, worn, null, null, "");
        assertThat(first.log()).extracting(Event::device, Event::type, Event::value)
                .containsExactly(tuple("Hub battery", "batteryHealth", "55% of its design capacity, 34 °C"));
        assertThat(first.alerts()).extracting(Alert::key, Alert::level, Alert::message).containsExactly(tuple(
                "batteryhealth:hub", Level.NOTICE, "The hub laptop's battery holds only 55% of its original charge."));

        assertThat(monitor.check(at(Duration.ofDays(1)), worn, null, null, "").log()).as("weekly").isEmpty();
        HubMonitor.Outcome week = monitor.check(at(Duration.ofDays(7)), worn, null, null, "");
        assertThat(week.log()).hasSize(1);
        assertThat(week.alerts()).as("mentioned once").isEmpty();
    }

    @Test
    void afterARestartHealthStaysWeekly() {
        monitor.seed(List.of(new Event(5L, "Hub battery", "batteryHealth", "80% of its design capacity", T0)));
        PowerSupply.Reading fine = new PowerSupply.Reading(true, 100, 80, 30.0);
        assertThat(monitor.check(at(Duration.ofDays(2)), fine, null, null, "").log()).isEmpty();
    }
}
