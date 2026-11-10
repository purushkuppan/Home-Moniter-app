package com.homehub;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;

/** Loads the configuration the way Spring Boot does at startup (without starting the app) and checks the prod profile. */
class ProdProfileTests {

    private static StandardEnvironment load(Path home, Path credentials, String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        // Point the secrets imports at test folders instead of the real home folder / systemd directory.
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "user.home", home.toString().replace('\\', '/'),
                "CREDENTIALS_DIRECTORY", credentials.toString().replace('\\', '/'))));
        ConfigDataEnvironmentPostProcessor.applyTo(env, new DefaultResourceLoader(), new DefaultBootstrapContext(), profiles);
        return env;
    }

    @Test
    void prodProfileConfiguresTheHub(@TempDir Path home) throws Exception {
        Path creds = Files.createDirectories(home.resolve("creds"));
        Files.writeString(creds.resolve("homeassistant.token"), "token-from-systemd-credential\n");   // as written by "paste, Enter, Ctrl+D"

        StandardEnvironment prod = load(home, creds, "prod");

        assertThat(prod.getProperty("spring.datasource.url")).isEqualTo("jdbc:sqlite:/opt/homehub/data/homehub.db");
        assertThat(prod.getProperty("homeassistant.url")).isEqualTo("http://127.0.0.1:8123");
        assertThat(prod.getProperty("homeassistant.token")).isEqualTo("token-from-systemd-credential");
        assertThat(prod.getProperty("homehub.connectors.homeassistant.enabled")).isEqualTo("true");
        assertThat(prod.getProperty("homehub.connectors.fake.enabled")).isEqualTo("false");
        // Alerts go to the phone on the hub only, and tapping one opens the dashboard through Tailscale (home and away).
        assertThat(prod.getProperty("homehub.alerts.enabled")).isEqualTo("true");
        assertThat(prod.getProperty("homehub.alerts.send-to")).isEqualTo("notify.mobile_app_purushphone");
        assertThat(prod.getProperty("homehub.alerts.link")).isEqualTo("http://kudil:8080");
        // The hub alerts when its nightly backup (deploy/backup.sh) stops; elsewhere there is no backup to watch.
        assertThat(prod.getProperty("homehub.alerts.backup.status-file")).isEqualTo("/var/lib/homehub-backup/last-success");
        // The SmartThings watchdog runs on the hub only, watching the fridge's power reports.
        assertThat(prod.getProperty("homehub.watchdog.entity")).isEqualTo("sensor.kitchen_refrigerator_power");
        assertThat(prod.getProperty("homehub.watchdog.stale-after")).isEqualTo("45m");

        // Same entity list as the default configuration.
        StandardEnvironment dev = load(home, creds);
        assertThat(prod.getProperty("homehub.connectors.homeassistant.include"))
                .isEqualTo(dev.getProperty("homehub.connectors.homeassistant.include"))
                .isNotBlank();
        // ...and the dev defaults are untouched by the prod file.
        assertThat(dev.getProperty("spring.datasource.url")).isEqualTo("jdbc:sqlite:data/homehub.db");
        assertThat(dev.getProperty("homehub.connectors.fake.enabled")).isEqualTo("true");
        assertThat(dev.getProperty("homehub.alerts.backup.status-file")).isEmpty();
        assertThat(dev.getProperty("homehub.watchdog.entity")).isEmpty();
    }

    @Test
    void prodFileContainsNoSecrets() throws Exception {
        String prodFile = new ClassPathResource("application-prod.properties").getContentAsString(StandardCharsets.UTF_8);
        assertThat(prodFile.lines().filter(l -> !l.strip().startsWith("#")))
                .noneMatch(l -> l.toLowerCase().matches(".*(token|secret|password)\\s*=.*"));
    }
}
