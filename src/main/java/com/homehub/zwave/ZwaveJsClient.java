package com.homehub.zwave;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Kudil's own read-only connection to the Z-Wave JS server (#134, ADR 12), next to Home Assistant's: the server takes
 * several clients at once. Built on the JDK's WebSocket client, so no new dependency.
 * <p>
 * Connects at start, follows {@link ZwaveSession}'s handshake, and keeps the node list in memory for
 * {@code /api/status}. When the connection drops (the container restarts, the hub reboots), it reconnects after 1 s,
 * then 2, 4 … up to {@value #MAX_BACKOFF_SECONDS} s, and starts over at 1 s once a handshake succeeds.
 * <p>
 * Only the server and driver versions and the node count are logged: never a message, since the server's state
 * holds network details such as DSKs.
 */
@Component
public class ZwaveJsClient implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ZwaveJsClient.class);
    static final int MAX_BACKOFF_SECONDS = 60;

    private final ZwaveProperties settings;
    private final ZwaveSession session = new ZwaveSession();
    private final Clock clock;
    private final AtomicBoolean reconnectPending = new AtomicBoolean();
    private final Object sendLock = new Object();
    private CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);
    private volatile boolean running;
    private volatile boolean connected;
    private volatile WebSocket socket;
    private volatile String lastError;
    private ScheduledExecutorService worker;
    private HttpClient http;
    private int backoffSeconds = 1;

    @Autowired
    public ZwaveJsClient(ZwaveProperties settings) {
        this(settings, Clock.systemUTC());
    }

    ZwaveJsClient(ZwaveProperties settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /** What {@code /api/status} shows. */
    public record Status(boolean enabled, boolean connected, String serverVersion, String driverVersion,
                         int schemaVersion, String since, int nodeCount, java.util.List<ZwaveNode> nodes,
                         String problem) {
    }

    public Status status() {
        if (!settings.isConfigured()) {
            return new Status(false, false, null, null, ZwaveSession.SCHEMA_VERSION, null, 0, java.util.List.of(), null);
        }
        ZwaveSession.State s = session.state();
        boolean up = connected && s.ready();
        String problem = s.problem() != null ? s.problem()
                : !connected ? (lastError == null ? "Not connected yet." : "Not connected: " + lastError)
                : !s.ready() ? "Connected, waiting for the node list." : null;
        return new Status(true, up, blank(s.serverVersion()), blank(s.driverVersion()), ZwaveSession.SCHEMA_VERSION,
                s.since() == null ? null : s.since().toString(), s.nodes().size(), s.nodes(), problem);
    }

    @Override
    public void start() {
        if (!settings.isConfigured()) return;
        URI uri;
        try {
            uri = URI.create(settings.url());
            if (!"ws".equals(uri.getScheme()) && !"wss".equals(uri.getScheme())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            log.warn("homehub.zwave.url must look like ws://127.0.0.1:3000; Z-Wave stays off.");
            return;
        }
        running = true;
        http = HttpClient.newHttpClient();
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zwave-js");
            t.setDaemon(true);
            return t;
        });
        worker.execute(() -> connect(uri));
    }

    @Override
    public void stop() {
        running = false;
        WebSocket ws = socket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        if (worker != null) worker.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void connect(URI uri) {
        if (!running) return;
        reconnectPending.set(false);
        session.reset();
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, new Listener(uri))
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        lastError = reason(error);
                        log.warn("Z-Wave JS: can't connect to {} ({}); retrying in {} s", uri, lastError, backoffSeconds);
                        reconnect(uri);
                    } else {
                        socket = ws;
                        connected = true;
                        lastError = null;
                    }
                });
    }

    private void reconnect(URI uri) {
        connected = false;
        socket = null;
        if (!running || !reconnectPending.compareAndSet(false, true)) return;
        int delay = backoffSeconds;
        backoffSeconds = Math.min(backoffSeconds * 2, MAX_BACKOFF_SECONDS);
        try {
            worker.schedule(() -> connect(uri), delay, TimeUnit.SECONDS);
        } catch (RuntimeException ex) {
            // shutting down
        }
    }

    private void handle(WebSocket ws, String text) {
        boolean wasReady = session.isReady();
        for (String reply : session.onMessage(text, clock.instant())) send(ws, reply);
        if (!wasReady && session.isReady()) {
            backoffSeconds = 1;
            ZwaveSession.State s = session.state();
            log.info("Z-Wave JS: connected to server {} (driver {}), {} nodes", s.serverVersion(), s.driverVersion(),
                    s.nodes().size());
        }
        String problem = session.state().problem();
        if (problem != null && !problem.equals(lastError)) {
            lastError = problem;
            log.warn("Z-Wave JS: {}", problem);
        }
    }

    /** Sends one message after the previous one went out (a WebSocket sends one message at a time). */
    private void send(WebSocket ws, String text) {
        synchronized (sendLock) {
            lastSend = lastSend.handle((done, error) -> null).thenCompose(x -> {
                CompletableFuture<WebSocket> sent = ws.sendText(text, true);
                return sent != null ? sent : CompletableFuture.completedFuture(ws);
            });
        }
    }

    /** Puts a message together from its parts (large ones, such as the node list, arrive in pieces). */
    private final class Listener implements WebSocket.Listener {
        private final URI uri;
        private final StringBuilder parts = new StringBuilder();

        Listener(URI uri) {
            this.uri = uri;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            parts.append(data);
            if (last) {
                String text = parts.toString();
                parts.setLength(0);
                try {
                    handle(ws, text);
                } catch (RuntimeException ex) {
                    log.warn("Z-Wave JS: couldn't handle a message ({})", ex.getClass().getSimpleName());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            if (running) {
                lastError = "the server closed the connection";
                log.warn("Z-Wave JS: the connection closed ({}); reconnecting in {} s", code, backoffSeconds);
                reconnect(uri);
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (running) {
                lastError = reason(error);
                log.warn("Z-Wave JS: connection lost ({}); reconnecting in {} s", lastError, backoffSeconds);
                reconnect(uri);
            }
        }
    }

    private static String reason(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String blank(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
