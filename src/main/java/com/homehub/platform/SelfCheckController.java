package com.homehub.platform;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/selfcheck}: the post-deploy check (#124). Answers only requests from the hub itself
 * (127.0.0.1 or ::1), since it writes and deletes a test event; anyone else gets 403.
 * 200 when every check passed, 503 when one failed, with the checks either way.
 */
@RestController
@Tag(name = "Self-check", description = "After a deploy: do alerts still work?")
public class SelfCheckController {

    private final SelfCheck selfCheck;

    public SelfCheckController(SelfCheck selfCheck) {
        this.selfCheck = selfCheck;
    }

    @PostMapping("/api/selfcheck")
    @Operation(summary = "Check that alerts still work (from the hub itself only)",
            description = "Saves and deletes a test event, checks it reached the live stream, runs a pretend leak "
                    + "through the rules without sending anything, and checks the Home Assistant connection. "
                    + "200 when all passed, 503 when one failed, 403 from anywhere but the hub itself.")
    public ResponseEntity<?> run(HttpServletRequest request) {
        if (!fromHub(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only from the hub itself."));
        }
        SelfCheck.Result result = selfCheck.run();
        return ResponseEntity.status(result.ok() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(result);
    }

    static boolean fromHub(String address) {
        if (address == null) return false;
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException | SecurityException ex) {
            return false;
        }
    }
}
