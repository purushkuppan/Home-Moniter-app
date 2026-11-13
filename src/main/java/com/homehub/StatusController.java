package com.homehub;

import com.homehub.zwave.ZwaveJsClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How Kudil's own connections are doing, for checking the hub: {@code GET /api/status}. Starts with the Z-Wave JS
 * server (#134); more connections join it as Kudil talks to devices directly (ADR 12).
 */
@RestController
@Tag(name = "Status", description = "Kudil's own connections to devices and protocol servers")
public class StatusController {

    private final ZwaveJsClient zwave;

    public StatusController(ZwaveJsClient zwave) {
        this.zwave = zwave;
    }

    @GetMapping("/api/status")
    @Operation(summary = "Kudil's connections: the Z-Wave JS server",
            description = "For Z-Wave: whether it is switched on and connected, since when, the server and driver "
                    + "versions, the API schema Kudil speaks, and the nodes (ID, name, room, alive/asleep/dead, ready).")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("zwave", zwave.status());
        return status;
    }
}
