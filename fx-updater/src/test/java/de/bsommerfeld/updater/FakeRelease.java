package de.bsommerfeld.updater;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A GitHub release on localhost: {@code /repos/o/r/releases/latest} with a
 * manifest and a full archive, as the TinyUpdate action would publish them for
 * the unprefixed, platform-neutral stream.
 */
final class FakeRelease implements AutoCloseable {

    private final HttpServer server;

    FakeRelease(String tag, Map<String, String> files) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        Map<String, byte[]> contents = new LinkedHashMap<>();
        files.forEach((path, text) -> contents.put(path, text.getBytes(StandardCharsets.UTF_8)));

        serve("/repos/o/r/releases/latest", ("""
                {"tag_name": "%s", "assets": [
                  {"name": "update.json", "browser_download_url": "%s/update.json"},
                  {"name": "files.zip", "browser_download_url": "%s/files.zip"}
                ]}""").formatted(tag, base, base).getBytes(StandardCharsets.UTF_8));
        serve("/update.json", manifest(tag, contents));
        serve("/files.zip", zip(contents));
        server.start();
    }

    String apiBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void serve(String path, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private static byte[] manifest(String tag, Map<String, byte[]> contents) {
        StringJoiner entries = new StringJoiner(",\n");
        contents.forEach((path, bytes) -> entries.add("{ \"path\": \"%s\", \"sha256\": \"%s\", \"size\": %d }"
                .formatted(path, sha256(bytes), bytes.length)));
        return ("{\"version\": \"" + tag + "\", \"files\": [\n" + entries + "\n]}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> contents) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
