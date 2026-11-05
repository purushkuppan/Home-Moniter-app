package com.homehub;

import static org.assertj.core.api.Assertions.assertThat;

import com.homehub.homeassistant.HomeAssistantProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Checks that secrets are read from the external files named in spring.config.import. */
class SecretsConfigTests {

    private static ConfigurableApplicationContext start(Path home, String... extraArgs) {
        String[] base = {
                // Command-line args outrank the real user.home system property, so the test never reads your real secrets.
                "--user.home=" + home.toString().replace('\\', '/'),
                "--spring.datasource.url=jdbc:sqlite:" + home.resolve("test.db").toString().replace('\\', '/'),
                "--homehub.connectors.enabled=false"
        };
        String[] args = new String[base.length + extraArgs.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extraArgs, 0, args, base.length, extraArgs.length);
        return new SpringApplicationBuilder(HomehubApplication.class).web(WebApplicationType.NONE).run(args);
    }

    @Test
    void readsSecretsFromHomeFolderFile(@TempDir Path home) throws Exception {
        Path dir = Files.createDirectories(home.resolve(".homehub"));
        Files.writeString(dir.resolve("secrets.properties"), """
                homeassistant.url=http://ha.test:8123
                homeassistant.token=ha-from-file
                """);

        try (var ctx = start(home)) {
            HomeAssistantProperties ha = ctx.getBean(HomeAssistantProperties.class);
            assertThat(ha.url()).isEqualTo("http://ha.test:8123");
            assertThat(ha.token()).isEqualTo("ha-from-file");
            assertThat(ha.isConfigured()).isTrue();
            assertThat(ha.toString()).doesNotContain("ha-from-file").contains("******");
        }
    }

    @Test
    void readsSecretsFromCredentialsDirectory(@TempDir Path home) throws Exception {
        Path creds = Files.createDirectories(home.resolve("creds"));
        Files.writeString(creds.resolve("homeassistant.url"), "http://ha-from-credential:8123");
        Files.writeString(creds.resolve("homeassistant.token"), "ha-from-credential");

        try (var ctx = start(home, "--CREDENTIALS_DIRECTORY=" + creds.toString().replace('\\', '/'))) {
            HomeAssistantProperties ha = ctx.getBean(HomeAssistantProperties.class);
            assertThat(ha.url()).isEqualTo("http://ha-from-credential:8123");
            assertThat(ha.token()).isEqualTo("ha-from-credential");
        }
    }

    @Test
    void credentialsDirectoryOverridesHomeFolderFile(@TempDir Path home) throws Exception {
        Path dir = Files.createDirectories(home.resolve(".homehub"));
        Files.writeString(dir.resolve("secrets.properties"), "homeassistant.token=ha-from-file\n");
        Path creds = Files.createDirectories(home.resolve("creds"));
        Files.writeString(creds.resolve("homeassistant.token"), "ha-from-credential");

        try (var ctx = start(home, "--CREDENTIALS_DIRECTORY=" + creds.toString().replace('\\', '/'))) {
            assertThat(ctx.getBean(HomeAssistantProperties.class).token()).isEqualTo("ha-from-credential");
        }
    }

    @Test
    void startsWithoutAnySecrets(@TempDir Path home) {
        try (var ctx = start(home)) {
            HomeAssistantProperties ha = ctx.getBean(HomeAssistantProperties.class);
            assertThat(ha.isConfigured()).isFalse();
            assertThat(ha.toString()).contains("<not set>");
        }
    }
}
