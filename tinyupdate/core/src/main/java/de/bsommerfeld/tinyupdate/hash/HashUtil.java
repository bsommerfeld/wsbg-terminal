package de.bsommerfeld.tinyupdate.hash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hashing utility. Uses streaming I/O to handle arbitrarily large files
 * without loading them entirely into memory.
 */
public final class HashUtil {

    private static final String ALGORITHM = "SHA-256";
    private static final int BUFFER_SIZE = 8192;

    private HashUtil() {}

    /**
     * Computes the hex-encoded SHA-256 hash of the given file.
     *
     * @throws IOException if the file cannot be read
     */
    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec — unreachable
            throw new AssertionError(ALGORITHM + " not available", e);
        }
    }

    /**
     * Computes the hex-encoded SHA-256 hash of a raw byte array.
     * Used for verifying in-memory buffers (e.g. downloaded zip content before extraction).
     */
    public static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            digest.update(data);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(ALGORITHM + " not available", e);
        }
    }
}
