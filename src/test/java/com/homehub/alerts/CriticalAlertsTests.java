package com.homehub.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.homehub.alerts.Alert.Level;
import com.homehub.alerts.CriticalAlerts.Step;
import com.homehub.alerts.CriticalAlerts.StepKind;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Critical alerts (#101): repeat until someone says "I'm on it", escalate, give up. */
class CriticalAlertsTests {

    private static final Instant T0 = Instant.parse("2026-11-12T09:00:00Z");   // 3 AM in Denton
    private static final Alert LEAK = new Alert("leak:kitchen leak sensor", Level.PROBLEM, "Kitchen leak sensor",
            "Water leak in the kitchen", T0);

    private static CriticalAlerts critical(List<String> escalateTo, int maxRepeats) {
        return new CriticalAlerts(new CriticalAlertProperties(List.of("leak"), Duration.ofMinutes(5), maxRepeats,
                Duration.ofMinutes(10), escalateTo, List.of()));
    }

    private static Instant at(int minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }

    @Test
    void onlyProblemsOfACriticalKindAreCritical() {
        CriticalAlerts critical = critical(List.of(), 24);
        assertThat(critical.isCritical(LEAK)).isTrue();
        assertThat(critical.isCritical(new Alert("leak:kitchen leak sensor", Level.CLEARED, "x", "dry", T0))).isFalse();
        assertThat(critical.isCritical(new Alert("door:refrigerator/fridge", Level.PROBLEM, "x", "open", T0))).isFalse();
        assertThat(critical.isCritical(new Alert("leakage", Level.PROBLEM, "x", "no kind", T0))).isFalse();
        assertThat(critical.isCritical(new Alert(CriticalAlerts.TEST_KEY, Level.PROBLEM, "Kudil", "test", T0))).isTrue();
    }

    @Test
    void anUnansweredAlertIsRepeatedEveryFiveMinutes() {
        CriticalAlerts critical = critical(List.of(), 24);
        critical.sent(LEAK, at(0));

        assertThat(critical.due(at(4))).isEmpty();
        assertThat(critical.due(at(5))).extracting(Step::kind, Step::count).containsExactly(tuple(StepKind.REPEAT, 1));
        assertThat(critical.due(at(6))).as("not again until 5 minutes after the repeat").isEmpty();
        assertThat(critical.due(at(10))).extracting(Step::kind, Step::count).containsExactly(tuple(StepKind.REPEAT, 2));
        assertThat(critical.waiting(LEAK.key())).isTrue();
    }

    @Test
    void sendingTheSameAlertAgainKeepsTheFirstTime() {
        CriticalAlerts critical = critical(List.of(), 24);
        critical.sent(LEAK, at(0));
        critical.sent(LEAK, at(3));
        assertThat(critical.due(at(5))).hasSize(1);
    }

    @Test
    void iAmOnItStopsTheRepeatsAndIsRecordedOnce() {
        CriticalAlerts critical = critical(List.of("mobile_app_amma"), 24);
        critical.sent(LEAK, at(0));

        assertThat(critical.acknowledge(LEAK.key(), "Purushphone", at(2))).contains(LEAK);
        assertThat(critical.acknowledge(LEAK.key(), "dashboard", at(3))).as("only the first answer counts").isEmpty();
        assertThat(critical.ackOf(LEAK.key())).contains(new CriticalAlerts.Ack("Purushphone", at(2)));
        assertThat(critical.waiting(LEAK.key())).isFalse();
        assertThat(critical.due(at(30))).as("no repeats, no escalation").isEmpty();
    }

    @Test
    void anAlertThatIsOverIsNeitherRepeatedNorAnswered() {
        CriticalAlerts critical = critical(List.of(), 24);
        critical.sent(LEAK, at(0));
        critical.ended(LEAK.key());

        assertThat(critical.due(at(5))).isEmpty();
        assertThat(critical.acknowledge(LEAK.key(), "Purushphone", at(6))).isEmpty();
        assertThat(critical.ackOf(LEAK.key())).isEmpty();
    }

    @Test
    void afterTenMinutesItAlsoGoesToTheEscalationPhonesOnce() {
        CriticalAlerts critical = critical(List.of("mobile_app_amma"), 24);
        critical.sent(LEAK, at(0));

        assertThat(critical.due(at(5))).extracting(Step::kind).containsExactly(StepKind.REPEAT);
        assertThat(critical.due(at(10))).extracting(Step::kind).containsExactly(StepKind.ESCALATE, StepKind.REPEAT);
        assertThat(critical.due(at(15))).extracting(Step::kind).containsExactly(StepKind.REPEAT);
    }

    @Test
    void withoutEscalationPhonesThereIsNoEscalation() {
        CriticalAlerts critical = critical(List.of(), 24);
        critical.sent(LEAK, at(0));
        critical.due(at(5));
        assertThat(critical.due(at(10))).extracting(Step::kind).containsExactly(StepKind.REPEAT);
    }

    @Test
    void itGivesUpAfterTheLastRepeatButStaysAnswerable() {
        CriticalAlerts critical = critical(List.of(), 2);
        critical.sent(LEAK, at(0));

        assertThat(critical.due(at(5))).extracting(Step::kind).containsExactly(StepKind.REPEAT);
        assertThat(critical.due(at(10))).extracting(Step::kind).containsExactly(StepKind.REPEAT);
        assertThat(critical.due(at(15))).extracting(Step::kind, Step::count).containsExactly(tuple(StepKind.GAVE_UP, 2));
        assertThat(critical.due(at(60))).as("said once").isEmpty();
        assertThat(critical.acknowledge(LEAK.key(), "Purushphone", at(61))).contains(LEAK);
    }

    @Test
    void aTestAlertRingsOnceAndIsForgottenWhenAnsweredOrAfterAWhile() {
        CriticalAlerts critical = critical(List.of("mobile_app_amma"), 24);
        Alert test = new Alert(CriticalAlerts.TEST_KEY, Level.PROBLEM, "Kudil", "Test", at(0));
        critical.sent(test, at(0));

        assertThat(critical.due(at(10))).as("never repeated or escalated").isEmpty();
        assertThat(critical.acknowledge(test.key(), "Purushphone", at(11))).contains(test);
        assertThat(critical.ackOf(test.key())).as("gone once answered").isEmpty();

        critical.sent(test, at(20));
        critical.due(at(35));
        assertThat(critical.acknowledge(test.key(), "Purushphone", at(36))).as("gone after 15 minutes").isEmpty();
    }

    @Test
    void afterARestartAnAnsweredAlertStaysQuietAndAnUnansweredOneRepeats() {
        CriticalAlerts critical = critical(List.of(), 24);
        Alert other = new Alert("leak:laundry leak sensor", Level.PROBLEM, "Laundry leak sensor", "Water leak: Laundry", T0);
        critical.restore(LEAK, at(30), new CriticalAlerts.Ack("Purushphone", at(2)));
        critical.restore(other, at(30), null);

        assertThat(critical.due(at(35))).extracting(s -> s.alert().key()).containsExactly(other.key());
        assertThat(critical.ackOf(LEAK.key())).contains(new CriticalAlerts.Ack("Purushphone", at(2)));
    }
}
