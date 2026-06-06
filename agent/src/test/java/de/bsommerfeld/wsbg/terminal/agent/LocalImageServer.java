package de.bsommerfeld.wsbg.terminal.agent;

import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.InetSocketAddress;

/**
 * Serves the static test images (classpath {@code /vision/*.png}) over a loopback
 * HTTP server, so the vision pipeline can be tested against fixed pictures — Reddit
 * threads get deleted, these never do. {@code AgentBrain.describeImage} fetches over
 * HTTP, so this exercises the real fetch → optimise → vision path unchanged.
 */
final class LocalImageServer implements AutoCloseable {

    private final HttpServer server;
    private final int port;

    LocalImageServer() throws Exception {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String name = exchange.getRequestURI().getPath().replaceFirst("^/", "");
            byte[] body;
            try (InputStream in = LocalImageServer.class.getResourceAsStream("/vision/" + name)) {
                body = in == null ? null : in.readAllBytes();
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    /** HTTP URL for a fixture image, e.g. {@code url("watchlist-red.png")}. */
    String url(String name) {
        return "http://127.0.0.1:" + port + "/" + name;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
