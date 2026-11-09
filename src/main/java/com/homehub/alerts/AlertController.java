package com.homehub.alerts;

import java.util.ArrayList;
import java.util.Comparator;
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
    private final ObjectProvider<SmartThingsWatchdog> watchdog;

    public AlertController(ObjectProvider<AlertService> alerts, ObjectProvider<SmartThingsWatchdog> watchdog) {
        this.alerts = alerts;
        this.watchdog = watchdog;
    }

    @GetMapping
    public List<Alert> active() {
        List<Alert> active = new ArrayList<>();
        alerts.ifAvailable(service -> active.addAll(service.active()));
        watchdog.ifAvailable(w -> active.addAll(w.active()));
        active.sort(Comparator.comparing(Alert::time));
        return active;
    }
}
