package com.homehub.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.homehub.homeassistant.HomeAssistantProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class WeatherServiceTests {

    private static final String URL = "http://ha.test:8123";
    private static final HomeAssistantProperties HA = new HomeAssistantProperties(URL, "secret");
    private static final String STATE_URL = URL + "/api/states/weather.home";
    private static final String FORECAST_URL = URL + "/api/services/weather/get_forecasts?return_response";

    // As Home Assistant 2026.11 answers (met.no), shortened.
    private static final String STATE = """
            {"entity_id":"weather.home","state":"cloudy","attributes":{"temperature":61,"temperature_unit":"°F",
             "humidity":74,"wind_speed":12.55,"wind_speed_unit":"mph","precipitation_unit":"in",
             "attribution":"Weather forecast from met.no, delivered by the Norwegian Meteorological Institute.",
             "friendly_name":"Home"}}""";
    private static final String FORECAST = """
            {"changed_states":[],"service_response":{"weather.home":{"forecast":[
             {"condition":"cloudy","datetime":"2026-11-06T18:00:00+00:00","temperature":61,"templow":59,"precipitation":0.0},
             {"condition":"cloudy","datetime":"2026-11-07T18:00:00+00:00","temperature":67,"templow":54,"precipitation":0.0},
             {"condition":"rainy","datetime":"2026-11-08T18:00:00+00:00","temperature":69,"templow":52,"precipitation":0.31},
             {"condition":"sunny","datetime":"2026-11-09T18:00:00+00:00","temperature":70,"templow":50,"precipitation":0.0}]}}}""";

    /** A clock the test moves forward by hand. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-06T22:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final TestClock clock = new TestClock();
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private WeatherService service(String entity) {
        return new WeatherService(builder, HA, entity, Duration.ofMinutes(10), clock);
    }

    private void expectState() {
        server.expect(ExpectedCount.once(), requestTo(STATE_URL))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess(STATE, MediaType.APPLICATION_JSON));
    }

    private void expectForecast() {
        server.expect(ExpectedCount.once(), requestTo(FORECAST_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"entity_id\":\"weather.home\",\"type\":\"daily\"}"))
                .andRespond(withSuccess(FORECAST, MediaType.APPLICATION_JSON));
    }

    @Test
    void readsCurrentWeatherAndThreeDaysOfForecast() {
        expectState();
        expectForecast();

        Weather w = service("weather.home").current().orElseThrow();

        server.verify();
        assertThat(w.condition()).isEqualTo("cloudy");
        assertThat(w.temperature()).isEqualTo(61.0);
        assertThat(w.temperatureUnit()).isEqualTo("°F");
        assertThat(w.humidity()).isEqualTo(74.0);
        assertThat(w.windSpeed()).isEqualTo(12.55);
        assertThat(w.windSpeedUnit()).isEqualTo("mph");
        assertThat(w.precipitationUnit()).isEqualTo("in");
        assertThat(w.attribution()).startsWith("Weather forecast from met.no");
        assertThat(w.stale()).isFalse();
        assertThat(w.days()).extracting(Weather.Day::condition, Weather.Day::high, Weather.Day::low, Weather.Day::precipitation)
                .containsExactly(
                        tuple("cloudy", 61.0, 59.0, 0.0),
                        tuple("cloudy", 67.0, 54.0, 0.0),
                        tuple("rainy", 69.0, 52.0, 0.31));
        assertThat(w.days().get(0).time()).isEqualTo("2026-11-06T18:00:00+00:00");
    }

    @Test
    void keepsTheResultForTenMinutes() {
        expectState();
        expectForecast();
        WeatherService weather = service("weather.home");

        weather.current();
        clock.now = clock.now.plus(Duration.ofMinutes(9));
        assertThat(weather.current()).isPresent();   // no second request: verify() would fail on an extra one

        server.verify();
    }

    @Test
    void withoutAForecastTheCurrentWeatherIsStillShown() {
        expectState();
        server.expect(requestTo(FORECAST_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        Weather w = service("weather.home").current().orElseThrow();

        assertThat(w.temperature()).isEqualTo(61.0);
        assertThat(w.days()).isEmpty();
    }

    @Test
    void whenHomeAssistantIsDownTheLastResultIsShownAsStaleAndRetriedAfterAMinute() {
        expectState();
        expectForecast();
        server.expect(ExpectedCount.once(), requestTo(STATE_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        expectState();
        expectForecast();
        WeatherService weather = service("weather.home");

        assertThat(weather.current().orElseThrow().stale()).isFalse();

        clock.now = clock.now.plus(Duration.ofMinutes(11));
        Weather old = weather.current().orElseThrow();
        assertThat(old.stale()).isTrue();
        assertThat(old.temperature()).isEqualTo(61.0);

        clock.now = clock.now.plus(Duration.ofSeconds(30));
        assertThat(weather.current().orElseThrow().stale()).as("no retry within a minute").isTrue();

        clock.now = clock.now.plus(Duration.ofSeconds(31));
        assertThat(weather.current().orElseThrow().stale()).as("retried and back").isFalse();
        server.verify();
    }

    @Test
    void nothingWhenSwitchedOffNotConfiguredOrNotAWeatherEntity() {
        // MockRestServiceServer fails on any request: none may be made.
        assertThat(service("").current()).isEmpty();
        assertThat(service("light.kitchen").current()).isEmpty();
        assertThat(service("weather.home/../../config").current()).isEmpty();
        assertThat(new WeatherService(builder, new HomeAssistantProperties(null, null), "weather.home",
                Duration.ofMinutes(10), clock).current()).isEmpty();
        server.verify();
    }

    @Test
    void apiAnswersWithTheWeatherOrNoContent() throws Exception {
        expectState();
        expectForecast();
        var withWeather = MockMvcBuilders.standaloneSetup(new WeatherController(service("weather.home"))).build();
        withWeather.perform(get("/api/weather"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condition").value("cloudy"))
                .andExpect(jsonPath("$.days.length()").value(3));

        var without = MockMvcBuilders.standaloneSetup(new WeatherController(service(""))).build();
        without.perform(get("/api/weather")).andExpect(status().isNoContent());
    }

    // sun.sun as Home Assistant 2026.11 answers, shortened: evening in Denton, the sun has set.
    private static final String SUN_URL = URL + "/api/states/sun.sun";
    private static final String SUN_DOWN = """
            {"entity_id":"sun.sun","state":"below_horizon","attributes":{
             "next_dawn":"2026-11-07T12:30:10.211504+00:00","next_rising":"2026-11-07T12:56:41.630198+00:00",
             "next_setting":"2026-11-07T23:24:02.004377+00:00","elevation":-12.4,"rising":false,
             "friendly_name":"Sun"}}""";

    @Test
    void readsSunriseAndSunsetFromHomeAssistantsSunEntity() {
        server.expect(ExpectedCount.once(), requestTo(SUN_URL))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess(SUN_DOWN, MediaType.APPLICATION_JSON));
        WeatherService weather = service("weather.home");

        assertThat(weather.sun()).hasValue(new Sun(false,
                Instant.parse("2026-11-07T12:56:41.630198Z"), Instant.parse("2026-11-07T23:24:02.004377Z")));
        clock.now = clock.now.plus(Duration.ofMinutes(9));
        assertThat(weather.sun()).as("kept for the refresh time").isPresent();
        server.verify();
    }

    @Test
    void noSunWithoutASunEntityAndTheLastTimesAreKeptWhenHomeAssistantIsDown() {
        server.expect(ExpectedCount.once(), requestTo(SUN_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(service("weather.home").sun()).isEmpty();
        server.verify();

        server.reset();
        server.expect(ExpectedCount.once(), requestTo(SUN_URL)).andRespond(withSuccess(SUN_DOWN, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(SUN_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        WeatherService weather = service("weather.home");
        assertThat(weather.sun()).isPresent();
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        assertThat(weather.sun()).as("last known times").isPresent();
        server.verify();

        assertThat(service("").sun()).as("weather switched off").isEmpty();
    }

    @Test
    void apiAnswersWithTheSunOrNoContent() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(SUN_URL)).andRespond(withSuccess(SUN_DOWN, MediaType.APPLICATION_JSON));
        MockMvcBuilders.standaloneSetup(new WeatherController(service("weather.home"))).build()
                .perform(get("/api/sun"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.up").value(false))
                .andExpect(jsonPath("$.nextRising").value("2026-11-07T12:56:41.630198Z"))
                .andExpect(jsonPath("$.nextSetting").value("2026-11-07T23:24:02.004377Z"));
        MockMvcBuilders.standaloneSetup(new WeatherController(service(""))).build()
                .perform(get("/api/sun")).andExpect(status().isNoContent());
    }
}
