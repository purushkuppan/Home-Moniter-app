package com.homehub.zwave;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;

/**
 * A stand-in Z-Wave JS server on embedded Tomcat's WebSocket support (already on the classpath). It greets with
 * {@code version}, answers {@code set_api_schema} and {@code start_listening} like the real one, and sends the node
 * list in two pieces, as large messages can arrive. Records every message it gets.
 */
final class FakeZwaveServer implements AutoCloseable {

    final List<String> received = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger();
    final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final Tomcat tomcat = new Tomcat();

    FakeZwaveServer() throws Exception {
        tomcat.setBaseDir(java.nio.file.Files.createTempDirectory("fake-zwave").toString());
        tomcat.setPort(0);
        Context ctx = tomcat.addContext("", null);
        ctx.addServletContainerInitializer(new WsSci(), null);
        ctx.addApplicationListener(Registrar.class.getName());
        // A request needs some servlet to map to before Tomcat's WebSocket filter sees the upgrade.
        Tomcat.addServlet(ctx, "default", new jakarta.servlet.http.HttpServlet() { });
        ctx.addServletMappingDecoded("/", "default");
        Registrar.server = this;
        tomcat.start();
    }

    String url() {
        return "ws://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/";
    }

    /** Drops every connection, as when the zwave-js-ui container restarts. */
    void dropAll() throws IOException {
        for (Session s : sessions) s.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "restart"));
        sessions.clear();
    }

    @Override
    public void close() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    /** Registers the endpoint once Tomcat's WebSocket container exists. */
    public static final class Registrar implements jakarta.servlet.ServletContextListener {
        static FakeZwaveServer server;

        @Override
        public void contextInitialized(jakarta.servlet.ServletContextEvent event) {
            ServerContainer container = (ServerContainer) event.getServletContext().getAttribute(ServerContainer.class.getName());
            FakeZwaveServer fake = server;
            try {
                container.addEndpoint(ServerEndpointConfig.Builder.create(Zwave.class, "/")
                        .configurator(new ServerEndpointConfig.Configurator() {
                            @Override
                            public <T> T getEndpointInstance(Class<T> type) {
                                return type.cast(new Zwave(fake));
                            }
                        }).build());
            } catch (jakarta.websocket.DeploymentException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    static final class Zwave extends Endpoint {
        private final FakeZwaveServer fake;

        Zwave(FakeZwaveServer fake) {
            this.fake = fake;
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            fake.connections.incrementAndGet();
            fake.sessions.add(session);
            session.setMaxTextMessageBufferSize(1 << 20);
            session.addMessageHandler(String.class, (MessageHandler.Whole<String>) text -> answer(session, text));
            send(session, ZwaveSessionTests.VERSION, true);
        }

        private void answer(Session session, String text) {
            fake.received.add(text);
            if (text.contains("\"set_api_schema\"")) {
                send(session, "{\"type\":\"result\",\"messageId\":\"kudil-set-api-schema\",\"success\":true,\"result\":{}}", true);
            } else if (text.contains("\"start_listening\"")) {
                String nodes = ZwaveSessionTests.NODES;
                int half = nodes.length() / 2;
                synchronized (session) {
                    try {
                        session.getBasicRemote().sendText(nodes.substring(0, half), false);
                        session.getBasicRemote().sendText(nodes.substring(half), true);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }
            }
        }

        private static void send(Session session, String text, boolean last) {
            synchronized (session) {
                try {
                    session.getBasicRemote().sendText(text, last);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        }
    }
}
