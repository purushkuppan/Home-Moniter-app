package com.homehub.zwave;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ZwaveJsClientTests {

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) throw new AssertionError("timed out waiting for: " + what);
            Thread.sleep(50);
        }
    }

    @Test
    void handshakeNodeListDropAndReconnect(CapturedOutput output) throws Exception {
        try (FakeZwaveServer server = new FakeZwaveServer()) {
            ZwaveJsClient client = new ZwaveJsClient(new ZwaveProperties(server.url()));
            client.start();
            try {
                await("connected", () -> client.status().connected());
                ZwaveJsClient.Status status = client.status();
                assertThat(status.enabled()).isTrue();
                assertThat(status.serverVersion()).isEqualTo("3.1.0");
                assertThat(status.driverVersion()).isEqualTo("15.3.0");
                assertThat(status.schemaVersion()).isEqualTo(35);
                assertThat(status.nodeCount()).as("node list put together from two pieces").isEqualTo(4);
                assertThat(status.since()).isNotNull();
                assertThat(status.problem()).isNull();
                assertThat(server.received).as("read-only: only the handshake is sent").hasSize(2);

                server.dropAll();   // the zwave-js-ui container restarts
                await("noticed the drop", () -> !client.status().connected());
                assertThat(client.status().problem()).startsWith("Not connected");
                await("reconnected", () -> client.status().connected() && server.connections.get() == 2);
                assertThat(client.status().nodeCount()).isEqualTo(4);
            } finally {
                client.stop();
            }
        }
        assertThat(output.getAll()).contains("Z-Wave JS: connected to server 3.1.0 (driver 15.3.0), 4 nodes")
                .doesNotContain("SECRET").doesNotContain("3967834353");
    }

    @Test
    void keepsTryingWhileTheServerIsDown() throws Exception {
        ZwaveJsClient client = new ZwaveJsClient(new ZwaveProperties("ws://127.0.0.1:1/"));
        client.start();
        try {
            await("a failed attempt", () -> client.status().problem() != null && client.status().problem().contains("Not connected:"));
            assertThat(client.status().connected()).isFalse();
            assertThat(client.isRunning()).isTrue();
        } finally {
            client.stop();
        }
    }

    @Test
    void offWithoutAUrlOrWithABadOne() {
        ZwaveJsClient off = new ZwaveJsClient(new ZwaveProperties(""));
        off.start();
        assertThat(off.isRunning()).isFalse();
        assertThat(off.status().enabled()).isFalse();

        ZwaveJsClient bad = new ZwaveJsClient(new ZwaveProperties("http://127.0.0.1:3000"));
        bad.start();
        assertThat(bad.isRunning()).isFalse();
    }
}
