package com.homehub.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.homehub.controls.ControlPin.Result;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ControlPinTests {

    /** A clock the test can move forward. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-05T18:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void noPinOrTooShortPinKeepsControlsDisabled() {
        assertThat(new ControlPin("", new TestClock()).check("1234")).isEqualTo(Result.NOT_CONFIGURED);
        assertThat(new ControlPin("123", new TestClock()).isConfigured()).isFalse();
        assertThat(new ControlPin(null, new TestClock()).isConfigured()).isFalse();
    }

    @Test
    void acceptsTheRightPinOnly() {
        ControlPin pin = new ControlPin(" 4821 ", new TestClock());
        assertThat(pin.check("4821")).isEqualTo(Result.OK);
        assertThat(pin.check("4822")).isEqualTo(Result.WRONG);
        assertThat(pin.check(null)).isEqualTo(Result.WRONG);
        assertThat(pin.check("")).isEqualTo(Result.WRONG);
    }

    @Test
    void locksForFiveMinutesAfterFiveWrongAttempts() {
        TestClock clock = new TestClock();
        ControlPin pin = new ControlPin("4821", clock);

        for (int i = 1; i < ControlPin.MAX_FAILURES; i++) assertThat(pin.check("0000")).isEqualTo(Result.WRONG);
        assertThat(pin.check("0000")).isEqualTo(Result.LOCKED);
        // Even the right PIN is refused while locked, so guessing gets nowhere.
        assertThat(pin.check("4821")).isEqualTo(Result.LOCKED);
        assertThat(pin.lockedSeconds()).isEqualTo(ControlPin.LOCKOUT.toSeconds());

        clock.now = clock.now.plus(ControlPin.LOCKOUT).plus(Duration.ofSeconds(1));
        assertThat(pin.lockedSeconds()).isZero();
        assertThat(pin.check("4821")).isEqualTo(Result.OK);
    }

    @Test
    void aCorrectPinResetsTheFailureCount() {
        ControlPin pin = new ControlPin("4821", new TestClock());
        for (int i = 1; i < ControlPin.MAX_FAILURES; i++) pin.check("0000");
        assertThat(pin.check("4821")).isEqualTo(Result.OK);
        assertThat(pin.check("0000")).isEqualTo(Result.WRONG);   // counting starts again, no lockout
    }
}
