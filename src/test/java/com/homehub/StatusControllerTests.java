package com.homehub;

import static org.assertj.core.api.Assertions.assertThat;

import com.homehub.zwave.ZwaveJsClient;
import com.homehub.zwave.ZwaveProperties;
import org.junit.jupiter.api.Test;

class StatusControllerTests {

    @Test
    void zwaveIsListedAndSaysWhenItIsSwitchedOff() {
        var status = new StatusController(new ZwaveJsClient(new ZwaveProperties(""))).status();
        assertThat(status).containsOnlyKeys("zwave");
        ZwaveJsClient.Status zwave = (ZwaveJsClient.Status) status.get("zwave");
        assertThat(zwave.enabled()).isFalse();
        assertThat(zwave.connected()).isFalse();
        assertThat(zwave.nodeCount()).isZero();
    }

    @Test
    void beforeTheFirstConnectionItSaysSo() {
        ZwaveJsClient.Status zwave = new ZwaveJsClient(new ZwaveProperties("ws://127.0.0.1:3000")).status();
        assertThat(zwave.enabled()).isTrue();
        assertThat(zwave.connected()).isFalse();
        assertThat(zwave.problem()).isEqualTo("Not connected yet.");
    }
}
