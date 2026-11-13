package com.homehub.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** US warnings from api.weather.gov (#106), with answers shaped like the real ones. */
class NwsWarningSourceTests {

    private static String feature(String event, String status, String messageType, String ends, String hail) {
        return """
                {"type":"Feature","properties":{"id":"urn:oid:%1$s","event":"%1$s","status":"%2$s","messageType":"%3$s",
                 "headline":"%1$s issued November 12 by NWS Fort Worth TX","ends":%4$s,"expires":"2026-11-13T03:00:00-06:00",
                 "parameters":{"NWSheadline":["X"]%5$s}}}""".formatted(event, status, messageType,
                ends == null ? "null" : "\"" + ends + "\"", hail == null ? "" : ",\"maxHailSize\":[\"" + hail + "\"]");
    }

    private static final String ACTIVE = """
            {"type":"FeatureCollection","features":[%s]}""".formatted(String.join(",",
            feature("Tornado Warning", "Actual", "Alert", "2026-11-12T19:45:00-06:00", null),
            feature("Severe Thunderstorm Warning", "Actual", "Update", "2026-11-12T20:15:00-06:00", "1.75"),
            feature("Freeze Watch", "Actual", "Alert", null, null),
            feature("Winter Storm Warning", "Actual", "Alert", "2026-11-14T06:00:00-06:00", "0.00"),
            feature("Tornado Warning", "Test", "Alert", "2026-11-12T19:45:00-06:00", null),       // a test: ignored
            feature("Flash Flood Warning", "Actual", "Cancel", "2026-11-12T19:45:00-06:00", null))); // cancelled: ignored

    @Test
    void readsTheWarningsInForceAtTheHomesPoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.weather.gov/alerts/active?point=33.21,-97.13"))
                .andExpect(header("User-Agent", "(Kudil home monitor)"))
                .andExpect(header("Accept", "application/geo+json"))
                .andRespond(withSuccess(ACTIVE, MediaType.APPLICATION_JSON));

        List<WeatherWarning> warnings = new NwsWarningSource(builder, "").active(33.21, -97.13);
        server.verify();

        assertThat(warnings).extracting(WeatherWarning::event, WeatherWarning::level, WeatherWarning::hailInches).containsExactly(
                tuple("Tornado Warning", WeatherWarning.Level.CRITICAL, null),
                tuple("Severe Thunderstorm Warning", WeatherWarning.Level.CRITICAL, 1.75),
                tuple("Freeze Watch", WeatherWarning.Level.NOTICE, null),
                tuple("Winter Storm Warning", WeatherWarning.Level.WARNING, null));
        assertThat(warnings.get(0).ends()).isEqualTo(Instant.parse("2026-11-13T01:45:00Z"));
        assertThat(warnings.get(2).ends()).as("no end: when it expires").isEqualTo(Instant.parse("2026-11-13T09:00:00Z"));
        assertThat(warnings.get(0).link()).isEqualTo("https://forecast.weather.gov/MapClick.php?lat=33.21&lon=-97.13");
    }

    @Test
    void aContactIsSentOnlyWhenTheHouseholdSetsOne() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.weather.gov/alerts/active?point=40.00,-75.00"))
                .andExpect(header("User-Agent", "(Kudil home monitor, family@example.org)"))
                .andRespond(withSuccess("{\"features\":[]}", MediaType.APPLICATION_JSON));

        assertThat(new NwsWarningSource(builder, " family@example.org ").active(40.0, -75.0)).isEmpty();
        server.verify();
    }

    @Test
    void coversUsHomesOnly() {
        NwsWarningSource nws = new NwsWarningSource(RestClient.builder(), "");
        assertThat(nws.covers("US")).isTrue();
        assertThat(nws.covers("us")).isTrue();
        assertThat(nws.covers("IN")).isFalse();
        assertThat(nws.covers("")).isFalse();
    }

    @Test
    void lifeThreateningWarningsAreCritical() {
        assertThat(NwsWarningSource.levelOf("Flash Flood Warning")).isEqualTo(WeatherWarning.Level.CRITICAL);
        assertThat(NwsWarningSource.levelOf("Extreme Wind Warning")).isEqualTo(WeatherWarning.Level.CRITICAL);
        assertThat(NwsWarningSource.levelOf("Ice Storm Warning")).isEqualTo(WeatherWarning.Level.WARNING);
        assertThat(NwsWarningSource.levelOf("Tornado Watch")).isEqualTo(WeatherWarning.Level.NOTICE);
        assertThat(NwsWarningSource.levelOf("Heat Advisory")).isEqualTo(WeatherWarning.Level.NOTICE);
        assertThat(NwsWarningSource.levelOf("Special Weather Statement")).isEqualTo(WeatherWarning.Level.NOTICE);
    }
}
