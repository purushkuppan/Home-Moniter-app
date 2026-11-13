package com.homehub.controls;

import com.homehub.ApiDocs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device controls for the dashboard.
 * <ul>
 *   <li>{@code GET  /api/controls}: what can be controlled, with current values (no PIN needed to look).</li>
 *   <li>{@code POST /api/controls/unlock}: checks the PIN (header {@value #PIN_HEADER}).</li>
 *   <li>{@code POST /api/controls/{entityId}} with {@code {"value": "..."}}: changes a device (PIN required).</li>
 * </ul>
 * The PIN travels in a custom header, which browsers never add to cross-site requests on their own, so another
 * web page cannot send commands on a visitor's behalf.
 */
@RestController
@RequestMapping("/api/controls")
@Tag(name = "Controls", description = "Switch plugs, locks and thermostats through Home Assistant (household PIN)")
public class ControlController {

    static final String PIN_HEADER = "X-HomeHub-Pin";

    private final ObjectProvider<ControlService> service;
    private final ControlPin pin;

    public ControlController(ObjectProvider<ControlService> service, ControlPin pin) {
        this.service = service;
        this.pin = pin;
    }

    public record CommandRequest(String value) {}

    @GetMapping
    @Operation(summary = "What can be controlled, with current values",
            description = "No PIN needed to look. `enabled` is false, with a `reason`, when controls are off or no PIN is set.")
    public Map<String, Object> list() {
        Map<String, Object> view = new LinkedHashMap<>();
        ControlService controls = service.getIfAvailable();
        if (controls == null) {
            view.put("enabled", false);
            view.put("reason", "Device controls are turned off (homehub.controls.enabled).");
            view.put("controls", List.of());
            return view;
        }
        List<Control> list;
        try {
            list = controls.list();
        } catch (ControlException ex) {
            view.put("enabled", false);
            view.put("reason", ex.getMessage());
            view.put("controls", List.of());
            return view;
        }
        view.put("enabled", pin.isConfigured());
        if (!pin.isConfigured()) {
            view.put("reason", "Set a PIN (homehub.control.pin) to enable device controls.");
        }
        view.put("controls", list);
        return view;
    }

    @PostMapping("/unlock")
    @Operation(summary = "Check the household PIN",
            description = "401 wrong PIN, 429 too many wrong PINs (locked for a while), 403 no PIN set on the hub.",
            security = @SecurityRequirement(name = ApiDocs.PIN))
    public ResponseEntity<Map<String, Object>> unlock(@Parameter(hidden = true) @RequestHeader(value = PIN_HEADER, required = false) String attempt) {
        requirePin(attempt);
        return ResponseEntity.ok(Map.of("message", "Controls unlocked."));
    }

    @PostMapping("/{entityId}")
    @Operation(summary = "Change a device",
            description = "Body `{\"value\": \"...\"}`: a value the control offers (see GET /api/controls). Needs the "
                    + "household PIN; 404 when controls are turned off.",
            security = @SecurityRequirement(name = ApiDocs.PIN))
    public ResponseEntity<Map<String, Object>> command(@PathVariable String entityId,
                                                       @Parameter(hidden = true) @RequestHeader(value = PIN_HEADER, required = false) String attempt,
                                                       @RequestBody(required = false) CommandRequest request) {
        ControlService controls = service.getIfAvailable();
        if (controls == null) {
            throw new ControlException(HttpStatus.NOT_FOUND, "Device controls are turned off.");
        }
        requirePin(attempt);   // before anything reaches Home Assistant
        String message = controls.apply(entityId, request == null ? null : request.value());
        return ResponseEntity.ok(Map.of("message", message));
    }

    private void requirePin(String attempt) {
        switch (pin.check(attempt)) {
            case OK -> { }
            case WRONG -> throw new ControlException(HttpStatus.UNAUTHORIZED, "Wrong PIN.");
            case LOCKED -> throw new ControlException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many wrong PINs. Try again in " + Math.max(1, (pin.lockedSeconds() + 59) / 60) + " minutes.");
            case NOT_CONFIGURED -> throw new ControlException(HttpStatus.FORBIDDEN,
                    "Device controls are disabled until a PIN is set (homehub.control.pin).");
        }
    }

    @ExceptionHandler(ControlException.class)
    ResponseEntity<Map<String, Object>> onControlError(ControlException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("message", ex.getMessage()));
    }
}
