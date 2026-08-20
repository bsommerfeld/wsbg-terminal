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
 * FIXED port — the browser is the only consumer.
 *
 * <p>
 * The port is fixed and must stay that way. It used to be ephemeral
 * ({@code port 0}), which looks harmless for a loopback-only server but
 * silently threw away everything the page keeps: the browser scopes
 * {@code localStorage} to the ORIGIN, the origin contains the port, and a
 * new port every start means a new, empty store every start. The theme
 * choice and the read-headline marks were gone on every restart, and the
 * app came up dark no matter what had been set.
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

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry(".html", "text/html; charset=utf-8"),
            Map.entry(".css", "text/css; charset=utf-8"),
            Map.entry(".js", "application/javascript; charset=utf-8"),
            Map.entry(".json", "application/json; charset=utf-8"),
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".png", "image/png"),
            Map.entry(".woff2", "font/woff2"),
            Map.entry(".ico", "image/x-icon"),
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".webp", "image/webp"),
            Map.entry(".gif", "image/gif"));

    /**
     * Fixed loopback port, one above {@code SingleInstance.PORT} and in the
     * same untravelled range. Anything the page persists is bound to it (see
     * the class comment), so this value is effectively part of the stored
     * state - changing it wipes every user's theme and read marks once.
     */
    private static final int PORT = 19338;

    private HttpServer server;
    private int port = -1;

    @Inject
    public AssetServer() {}

    public void start() throws IOException {
        server = bindFixedOrAny();
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

    /**
     * Binds the fixed port, or any free one if something else holds it. The
     * fallback keeps the terminal starting at all costs - a page that loads is
     * worth more than a page that remembers - but it does cost this run's
     * stored state, so it says so out loud.
     */
    private static HttpServer bindFixedOrAny() throws IOException {
        try {
            return HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 32);
        } catch (IOException e) {
            HttpServer any = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
            LOG.warn("Port {} is taken ({}) — serving on {} instead. The page's stored state "
                            + "(theme, read headlines) is scoped to the origin and will not be found this run.",
                    PORT, e.getMessage(), any.getAddress().getPort());
            return any;
        }
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
