package com.homehub.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.homehub.alerts.Alert.Level;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NextStepsTests {

    private static final Instant T = Instant.parse("2026-11-13T18:00:00Z");

    private static Alert alert(String key, Level level, String device, String message) {
        return new Alert(key, level, device, message, T);
    }

    /** Every alert kind Kudil raises today carries a next step (#161). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            leak:kitchen leak sensor      | PROBLEM | Kitchen leak sensor  | Turn off the water at the main valve
            smoke:hall smoke alarm        | PROBLEM | Hall smoke alarm     | Get everyone out now
            co:hall co alarm              | PROBLEM | Hall CO alarm        | Get everyone out into fresh air
            door:refrigerator/fridge      | PROBLEM | Refrigerator/Fridge  | nothing blocks the door
            door:freezer                  | PROBLEM | Freezer              | nothing blocks the door
            door:garage door              | NOTICE  | Garage door          | then close it
            door:bedroom window           | PROBLEM | Bedroom window       | Close it if no one opened it
            door:back door                | PROBLEM | Back door            | Check who is home
            warm:refrigerator/fridge      | PROBLEM | Refrigerator/Fridge  | Move milk and meat to a cooler
            warm:freezer                  | PROBLEM | Freezer              | Move food to another freezer
            warm:living room              | PROBLEM | Living room          | Check the thermostat
            lock:front door lock          | PROBLEM | Front door lock      | Lock it by hand
            battery:leak sensor           | NOTICE  | Leak sensor          | Replace the battery soon
            offline:refrigerator          | PROBLEM | Refrigerator         | Check that it has power
            connection:homeassistant      | PROBLEM | Home Assistant       | hub laptop is on
            stale:smartthings             | PROBLEM | SmartThings          | SmartThings app
            backup                        | PROBLEM | Backup               | backup disk
            finished:washer               | NOTICE  | Washer               | Move the clothes to the dryer
            finished:dryer                | NOTICE  | Dryer                | Take the clothes out
            weather:freeze                | NOTICE  | Weather/Freeze       | cover outdoor taps
            weather:rain                  | NOTICE  | Weather/Rain         | umbrella
            severe:tornado-warning        | PROBLEM | Tornado Warning      | Go to your safe room now.
            severe:flash-flood-warning    | PROBLEM | Flash Flood Warning  | Never drive through flooded roads.
            weather:winter-storm-warning  | PROBLEM | Winter Storm Warning | Follow what local officials say
            test:critical                 | PROBLEM | Kudil                | I'm on it
            """)
    void everyAlertKindSaysWhatToDoNext(String key, Level level, String device, String step) {
        assertThat(NextSteps.of(alert(key, level, device, "Something happened"))).hasValueSatisfying(s -> {
            assertThat(s).contains(step);
            assertThat(s).as("one short line").hasSizeLessThanOrEqualTo(110).doesNotContain("\n");
        });
    }

    @Test
    void anAllClearAndTheDailyBriefHaveNoNextStep() {
        assertThat(NextSteps.of(alert("leak:kitchen leak sensor", Level.CLEARED, "Kitchen leak sensor", "Dry again."))).isEmpty();
        assertThat(NextSteps.of(alert("brief:2026-11-13", Level.NOTICE, "Daily brief", "All quiet today."))).isEmpty();
    }

    @Test
    void phonesGetTheStepOnItsOwnLineAndSpokenAlertsAsASecondSentence() {
        Alert leak = alert("leak:kitchen leak sensor", Level.PROBLEM, "Kitchen leak sensor", "Water leak in the kitchen");
        assertThat(NextSteps.withStep(leak)).isEqualTo("Water leak in the kitchen\n"
                + "Turn off the water at the main valve, then dry the floor and find where it comes from.");
        assertThat(NextSteps.spoken(leak)).startsWith("Water leak in the kitchen. Turn off the water");

        Alert smoke = alert("smoke:hall", Level.PROBLEM, "Hall", "Smoke detected: Hall.");
        assertThat(NextSteps.spoken(smoke)).startsWith("Smoke detected: Hall. Get everyone out");

        Alert brief = alert("brief:2026-11-13", Level.NOTICE, "Daily brief", "All quiet today.");
        assertThat(NextSteps.withStep(brief)).isEqualTo("All quiet today.");
    }
}
