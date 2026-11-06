package com.homehub.alerts;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/alerts}: problems going on right now (an empty list when alerts are switched off). */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final ObjectProvider<AlertService> alerts;

    public AlertController(ObjectProvider<AlertService> alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public List<Alert> active() {
        AlertService service = alerts.getIfAvailable();
        return service == null ? List.of() : service.active();
    }
}
