package de.bsommerfeld.tinyupdate.download;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Downloader's error handling and validation logic.
 * Live HTTP tests are impractical here — we verify failure modes
 * and the validateStatus contract.
 */
class DownloaderTest {

    @Test
    void toString_shouldThrowOnInvalidUrl() {
        assertThrows(Exception.class,
                () -> Downloader.toString("not-a-valid-url"));
    }

    @Test
    void toBytes_shouldThrowOnUnreachableHost() {
        assertThrows(Exception.class,
                () -> Downloader.toBytes("http://host.invalid.test/file", (_, _) -> {
                }));
    }

    @Test
    void toFile_shouldThrowOnUnreachableHost() {
        assertThrows(Exception.class,
                () -> Downloader.toFile("http://host.invalid.test/file",
                        java.nio.file.Path.of("/tmp/test-download.bin"), (_, _) -> {
                        }));
    }

    @Test
    void validateStatus_shouldThrowForNon2xxStatus() throws Exception {
        // Use reflection to test the private static method
        var method = Downloader.class.getDeclaredMethod("validateStatus", int.class, String.class);
        method.setAccessible(true);

        // 200 should not throw
        assertDoesNotThrow(() -> method.invoke(null, 200, "http://ok"));

        // 404 should throw
        var ex = assertThrows(Exception.class, () -> method.invoke(null, 404, "http://fail"));
        assertTrue(ex.getCause() instanceof IOException);

        // 500 should throw
        assertThrows(Exception.class, () -> method.invoke(null, 500, "http://error"));
    }

    @Test
    void validateStatus_shouldAcceptAll2xxCodes() throws Exception {
        var method = Downloader.class.getDeclaredMethod("validateStatus", int.class, String.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(null, 200, "url"));
        assertDoesNotThrow(() -> method.invoke(null, 201, "url"));
        assertDoesNotThrow(() -> method.invoke(null, 204, "url"));
        assertDoesNotThrow(() -> method.invoke(null, 299, "url"));
    }

    @Test
    void validateStatus_shouldRejectBoundaryValues() throws Exception {
        var method = Downloader.class.getDeclaredMethod("validateStatus", int.class, String.class);
        method.setAccessible(true);

        assertThrows(Exception.class, () -> method.invoke(null, 199, "url"));
        assertThrows(Exception.class, () -> method.invoke(null, 300, "url"));
    }

    /*
     * A server that ignores the Range header answers every chunk request with
     * 200 and the whole file. The download must still come out byte-exact -
     * over one connection instead of eight.
     */
    @Test
    void toBytes_shouldFallBackToOneConnectionWhenRangeIsIgnored() throws Exception {
        byte[] payload = new byte[3_000_000];
        new Random(7).nextBytes(payload);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            assertArrayEquals(payload, Downloader.toBytes(url, (_, _) -> {
            }));
        } finally {
            server.stop(0);
        }
    }
}
