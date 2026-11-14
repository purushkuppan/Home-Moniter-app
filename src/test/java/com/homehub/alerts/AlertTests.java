package com.homehub.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.homehub.alerts.Alert.Level;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AlertTests {

    private static final Alert DRYER = new Alert("finished:dryer", Level.NOTICE, "Dryer", "Dryer finished",
            Instant.parse("2026-11-14T18:02:00.123456Z"));

    /** Tapping a notification opens the dashboard on that alert (#184), not just the home page. */
    @Test
    void theLinkOpensTheDashboardOnThisAlert() {
        assertThat(DRYER.link("http://kudil:8080"))
                .isEqualTo("http://kudil:8080/#alert=finished%3Adryer&at=2026-11-14T18%3A02%3A00.123456Z");
        assertThat(DRYER.link(" http://kudil:8080/// ")).as("trailing slashes")
                .isEqualTo("http://kudil:8080/#alert=finished%3Adryer&at=2026-11-14T18%3A02%3A00.123456Z");
        assertThat(DRYER.link("http://kudil:8080/#devices")).as("a link that opened some other part of the dashboard")
                .startsWith("http://kudil:8080/#alert=finished%3Adryer&");
    }

    @Test
    void noDashboardLinkMeansNoLink() {
        assertThat(DRYER.link("")).isEmpty();
        assertThat(DRYER.link(null)).isEmpty();
        assertThat(DRYER.link("  ")).isEmpty();
    }

    @Test
    void theLevelComesBackFromTheStoredEventType() {
        assertThat(Alert.levelOf("notice")).contains(Level.NOTICE);
        assertThat(Alert.levelOf("alert")).contains(Level.PROBLEM);
        assertThat(Alert.levelOf("alertCleared")).contains(Level.CLEARED);
        assertThat(Alert.levelOf("temperature")).isEmpty();
    }
}
