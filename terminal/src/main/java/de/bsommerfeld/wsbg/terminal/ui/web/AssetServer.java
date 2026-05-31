package de.bsommerfeld.wsbg.terminal.ui.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server that streams the static web assets from
 * {@code /web/...} on the classpath. Bound to {@code 127.0.0.1} on a
 * random free port — the browser is the only consumer.
 *
 * <p>
 * Using the built-in {@link HttpServer} keeps the dependency footprint
 * minimal. The server is not exposed externally and serves a fixed
 * resource tree, so the standard XSS / path traversal hardening is
 * sufficient.
 */
@Singleton
public final class AssetServer {

    private static final Logger LOG = LoggerFactory.getLogger(AssetServer.class);

    private static final Map<String, String> MIME = Map.of(
            ".html", "text/html; charset=utf-8",
            ".css", "text/css; charset=utf-8",
            ".js", "application/javascript; charset=utf-8",
            ".json", "application/json; charset=utf-8",
            ".svg", "image/svg+xml",
            ".png", "image/png",
            ".woff2", "font/woff2",
            ".ico", "image/x-icon");

    private HttpServer server;
    private int port = -1;

    @Inject
    public AssetServer() {}

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "asset-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        port = server.getAddress().getPort();
        LOG.info("AssetServer listening on http://127.0.0.1:{}", port);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    public int port() {
        return port;
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..")) { send(ex, 400, "text/plain", "bad path".getBytes()); return; }

        // /fonts/* is installed at runtime by the setup script (setup.sh /
        // setup.ps1) into the app data directory. Serving from there (instead of
        // bundling fonts in the JAR) keeps the artifact lean and lets
        // font upgrades happen outside the release cycle.
        if (path.startsWith("/fonts/")) {
            Path fontFile = StorageUtils.getAppDataDir().resolve("fonts")
                    .resolve(path.substring("/fonts/".length()));
            if (Files.isRegularFile(fontFile)) {
                byte[] data = Files.readAllBytes(fontFile);
                send(ex, 200, mime(path), data);
                return;
            }
            send(ex, 404, "text/plain", ("not found: " + path).getBytes());
            return;
        }

        String resource = "/web" + path;
        try (InputStream in = AssetServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                send(ex, 404, "text/plain", ("not found: " + path).getBytes());
                return;
            }
            byte[] data = in.readAllBytes();
            send(ex, 200, mime(path), data);
        }
    }

    private static String mime(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        return MIME.getOrDefault(path.substring(dot).toLowerCase(), "application/octet-stream");
    }

    private static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
