package com.homehub.hub;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Whether the home's internet works: a plain TCP connection to a few well-known hosts (two public DNS resolvers and
 * the Tailscale coordination server), up while any of them answers. Nothing is sent over the connection.
 */
public interface InternetCheck {

    boolean reachable();

    static InternetCheck tcp(List<String> hosts, int timeoutMillis) {
        return () -> {
            for (String host : hosts) {
                int colon = host.lastIndexOf(':');
                if (colon <= 0) continue;
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host.substring(0, colon),
                            Integer.parseInt(host.substring(colon + 1))), timeoutMillis);
                    return true;
                } catch (IOException | RuntimeException ex) {
                    // try the next one
                }
            }
            return false;
        };
    }
}
