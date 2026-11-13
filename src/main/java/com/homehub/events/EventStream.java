package com.homehub.events;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * Pushes every newly saved {@link Event} to connected browsers using Server-Sent Events.
 * <p>
 * Sending happens on its own thread, so a slow or stuck browser never delays the code that saved the event
 * (for example the Home Assistant live connection).
 */
@Component
public class EventStream {

    /** Browsers reconnect automatically when a stream ends, so a finite timeout just recycles connections. */
    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final long RECONNECT_MS = 3_000;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private volatile long lastEventId = -1;   // the newest event handed to the stream, for the self-check (#124)
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "event-stream");
        t.setDaemon(true);
        return t;
    });

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        try {
            // Sent right away so the browser knows it is connected and how fast to reconnect.
            emitter.send(SseEmitter.event().comment("connected").reconnectTime(RECONNECT_MS));
            emitters.add(emitter);
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    /** Called by Spring whenever {@link EventRepository} saves an event. */
    @EventListener
    public void onEventSaved(Event event) {
        if (event.id() != null) lastEventId = event.id();
        broadcast(() -> SseEmitter.event()
                .id(String.valueOf(event.id()))
                .name("event")
                .data(event, MediaType.APPLICATION_JSON));
    }

    /** Keeps idle connections open and removes browsers that have gone away. */
    @Scheduled(fixedRate = 25_000, initialDelay = 25_000)
    public void heartbeat() {
        broadcast(() -> SseEmitter.event().comment("ping"));
    }

    /** The id of the newest event that reached the stream, or -1. */
    public long lastEventId() {
        return lastEventId;
    }

    int subscriberCount() {
        return emitters.size();
    }

    private void broadcast(Supplier<SseEventBuilder> message) {
        if (emitters.isEmpty()) return;
        sender.execute(() -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(message.get());   // a fresh builder per browser; builders are single-use
                } catch (IOException | IllegalStateException ex) {
                    emitters.remove(emitter);       // the browser disconnected
                }
            }
        });
    }

    /**
     * Ends all open streams as soon as the app starts shutting down. Otherwise the server's graceful shutdown waits
     * for these long-lived requests and Ctrl+C hangs for 30 seconds while a dashboard is open.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeStreams() {
        for (SseEmitter emitter : emitters) {
            emitters.remove(emitter);
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
    }

    @PreDestroy
    void shutdown() {
        closeStreams();
        sender.shutdownNow();
    }
}
