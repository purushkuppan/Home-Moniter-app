package com.homehub.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/controller.db",
        "homehub.connectors.enabled=false",
        "homehub.alerts.enabled=false"   // alerts would add events of their own
})
class EventControllerTests {

    @LocalServerPort int port;
    @Autowired EventRepository repository;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM events");
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    @Test
    void dashboardMustBeRevalidatedSoPhonesDontShowAnOldCopy() throws Exception {
        HttpResponse<String> page = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-cache"));
        // Users see the product name; HomeHub is only the code name.
        assertThat(page.body()).contains("<title>Kudil</title>", "<h1>Kudil</h1>").doesNotContain("HomeHub is not reachable");
        // The product is "Kudil: Family Home Monitor".
        assertThat(page.body()).contains("<p>Family Home Monitor<span id=\"appVersion\"></span></p>")
                .doesNotContain("Home appliance monitor");
        // Every file in the jar is dated 1980; with a Last-Modified header, browsers holding an old copy would be told
        // "not modified" after every deploy.
        assertThat(page.headers().firstValue("Last-Modified")).isEmpty();

        HttpResponse<String> revalidated = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/"))
                        .header("If-Modified-Since", "Fri, 01 Feb 1980 00:00:00 GMT").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(revalidated.statusCode()).isEqualTo(200);
    }

    @Test
    void dashboardHasATemperatureUnitSwitchThatIsRememberedPerBrowser() throws Exception {
        String page = get("/");
        assertThat(page).contains("id=\"unitSwitch\"", "aria-label=\"Temperature unit\"",
                "data-unit=\"F\"", "data-unit=\"C\"", ">°F</button>", ">°C</button>");
        // Remembered in the browser only, and every access is guarded (private mode throws).
        assertThat(page).contains("const UNIT_KEY = 'homehub.tempUnit'",
                "try { localStorage.setItem(UNIT_KEY, unit); } catch");
        // Controls keep the device's own number as the value that is sent; only the label is converted.
        assertThat(page).contains("o.value = v;", "sendCommand(c, select.value)");
    }

    @Test
    void dashboardShowsTheRunningVersion() throws Exception {
        String page = get("/");
        assertThat(page).contains("<span id=\"appVersion\"></span>", "<span id=\"footerVersion\"></span>",
                "const { started, version } = await res.json();", "showVersion(version);");
    }

    @Test
    void homeShowsStatusQuickControlsAppliancesAndToday() throws Exception {
        String page = get("/");
        String home = page.substring(page.indexOf("<div class=\"tab-panel\" data-tab=\"status\">"),
                page.indexOf("<div class=\"tab-panel\" data-tab=\"controls\">"));
        // In this order: is the house OK, then what is changed most, then each appliance, then today.
        assertThat(home).containsSubsequence("id=\"statusCard\"", "id=\"weather\"", "id=\"quick\"", "id=\"cards\"",
                "id=\"today\"");
        assertThat(home).contains(">Quick controls<", ">Appliances<", ">Today<", "id=\"seeAll\"");
        assertThat(page).contains("title = 'All good'", "'Needs attention'", "' door is open'",
                "'Nothing has happened yet today.'");
        // Phones switch sections from a bottom bar; a red dot on Home while something needs attention.
        String nav = page.substring(page.indexOf("<nav class=\"tabs\" id=\"tabs\""));
        assertThat(nav).containsSubsequence("data-tab=\"status\"", ">Home<", "id=\"homeBadge\"",
                "data-tab=\"controls\"", ">Controls<", "data-tab=\"activity\"", ">Activity<");
        // Quick controls change devices only through the PIN and a confirmation, like the Controls section.
        assertThat(page).contains("if (confirm(CYCLE_VERBS[next] + ' the ' + c.device.toLowerCase() + '?')) sendCommand(c, next);");
    }

    @Test
    void settingsOfferLightDarkOrAutoSavedOnThisDevice() throws Exception {
        String page = get("/");
        assertThat(page).contains("id=\"settingsBtn\"", "aria-label=\"Settings\"", "<dialog id=\"settings\"",
                "name=\"theme\" value=\"light\"", "name=\"theme\" value=\"dark\"", "name=\"theme\" value=\"auto\"",
                "name=\"themeAuto\" value=\"system\"", "name=\"themeAuto\" value=\"sun\"",
                ">Match my phone<", ">Sunset to sunrise<");
        // "Night" is the security mode (#20); the theme only ever says Light and Dark.
        String settings = page.substring(page.indexOf("<dialog id=\"settings\""), page.indexOf("</dialog>", page.indexOf("<dialog id=\"settings\"")));
        assertThat(settings).doesNotContain("Night").contains("id=\"unitSwitch\"", "id=\"aboutVersion\"");
        // Applied before the first paint from what this browser saved, and kept up to date at sunrise and sunset.
        String head = page.substring(0, page.indexOf("</head>"));
        assertThat(head).contains("localStorage.getItem(key)", "read('homehub.theme')", "read('homehub.themeAuto') === 'sun'",
                "phoneDark.addEventListener('change', kudilTheme)", "setInterval(kudilTheme, 60000)");
    }

    @Test
    void kudilLogoInTheHeaderTabAndOnThePhoneHomeScreen() throws Exception {
        String page = get("/");
        String head = page.substring(0, page.indexOf("</head>"));
        assertThat(head).contains("<link rel=\"icon\" href=\"brand/kudil-favicon.svg\" type=\"image/svg+xml\">",
                "<link rel=\"apple-touch-icon\" href=\"icons/apple-touch-icon.png\">",
                "<link rel=\"manifest\" href=\"manifest.json\">", "<meta name=\"apple-mobile-web-app-title\" content=\"Kudil\">",
                "<meta name=\"theme-color\" content=\"#0F3D3A\">");
        // The header shows the brand mark (brand/kudil-mark.svg): the K-house strokes and the light at its heart, in
        // theme colors so it is teal on light and light on dark.
        assertThat(page).contains("<path d=\"M26 72 L64 34 L102 72\"/>", "<circle class=\"lamp\" cx=\"64\" cy=\"60\" r=\"5.5\"/>")
                .doesNotContain("class=\"door\"", "M12 3 2 11h3v9h5v-6h4v6h5v-9h3z");

        // The brand files are served as they were chosen.
        for (String file : new String[] {"kudil-mark.svg", "kudil-mark-on-dark.svg", "kudil-mark-mono.svg", "kudil-logo.svg",
                "kudil-logo-on-dark.svg", "kudil-app-icon.svg", "kudil-favicon.svg", "kudil-favicon-16.svg"}) {
            assertThat(get("/brand/" + file)).as(file).contains("<svg", "aria-label=\"Kudil");
        }
        assertThat(get("/brand/kudil-app-icon.svg")).contains("#0F3D3A", "#D9901A");

        // "Add to Home screen" gets the name Kudil, the icons, and opens full screen.
        String manifest = get("/manifest.json");
        assertThat(manifest).contains("\"short_name\": \"Kudil\"", "\"display\": \"standalone\"", "\"theme_color\": \"#0F3D3A\"",
                "\"purpose\": \"maskable\"");
        for (String icon : new String[] {"icon-192.png", "icon-512.png", "icon-maskable-192.png", "icon-maskable-512.png",
                "apple-touch-icon.png", "favicon-32.png", "favicon-16.png"}) {
            HttpResponse<byte[]> png = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/icons/" + icon)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(png.statusCode()).as(icon).isEqualTo(200);
            assertThat(png.body()).as(icon).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        }
    }

    @Test
    void fontsAreServedByKudilAndKeptByBrowsersForAYear() throws Exception {
        for (String font : new String[] {"inter-latin-wght-normal.woff2", "space-grotesk-latin-wght-normal.woff2"}) {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/fonts/" + font)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).as(font).isEqualTo(200);
            assertThat(new String(response.body(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("wOF2");
            assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v ->
                    assertThat(v).contains("max-age=31536000", "immutable"));
        }
        // The page itself is still checked on every visit.
        HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-cache"));
    }

    @Test
    void aHiddenDashboardMakesNoPeriodicRequests() throws Exception {
        String page = get("/");
        assertThat(page).contains("const watched = () => !paused && !document.hidden;",
                "setInterval(() => { if (watched()) { checkVersion(); load(); } }, SAFETY_RELOAD_MS);",
                "document.addEventListener('visibilitychange', () => { if (watched()) { checkVersion(); load(); } });");
        // Loaded once on start: by the live stream opening, or after 3 s if it doesn't.
        assertThat(page).contains("stream.onopen = () => { checkVersion(); load(); };",
                "setTimeout(() => { if (!everLoaded) load(); }, 3000);");
    }

    @Test
    void temperaturesStayInHomeAssistantsUnitInTheApi() throws Exception {
        repository.add("Refrigerator/Freezer", "temperature", "0 F");
        // Conversion is display only: the API and the database keep the reading as it came in.
        assertThat(get("/api/events/latest")).contains("\"value\":\"0 F\"");
    }

    @Test
    void versionIdentifiesTheRunningServer() throws Exception {
        String version = get("/api/version");
        assertThat(version).contains("\"started\":\"").contains("\"version\":\"");
        assertThat(get("/api/version")).isEqualTo(version);   // stable until the next restart
    }

    @Test
    void latestReturnsTheLastValueOfEveryReading() throws Exception {
        repository.add("Dryer", "machineState", "stop");
        for (int i = 0; i < 55; i++) repository.add("Refrigerator/Fridge", "contact", i % 2 == 0 ? "open" : "closed");

        String latest = get("/api/events/latest");
        assertThat(latest).contains("\"device\":\"Dryer\"").contains("\"value\":\"stop\"");
        assertThat(latest).contains("\"device\":\"Refrigerator/Fridge\"").contains("\"value\":\"open\"");

        // The existing API is unchanged: the latest 50 events, which no longer include the dryer.
        String page = get("/api/events");
        assertThat(page).doesNotContain("Dryer");
        assertThat(page.split("\"id\":")).hasSize(51);
    }
}
