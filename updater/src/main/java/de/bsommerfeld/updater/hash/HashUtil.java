package de.bsommerfeld.updater.hash;

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

    /*
     * Not a free choice: the manifest publishes its file hashes as SHA-256, so
     * this has to be the algorithm the publishing side used.
    */
    private static final String ALGORITHM = "SHA-256";

    /*
     * Read granularity, not a limit on file size - the digest is fed in chunks
     * of this many bytes however large the file is. 8 KiB is a whole number of
     * disk blocks, so a read rarely straddles a block boundary.
    */
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

                /*
                 * Fixed-size buffer instead of Files.readAllBytes: verification
                 * runs over the extracted update files, and the memory ceiling
                 * has to stay independent of how large the largest of them is.
                */
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {

                    /*
                     * Only the bytes actually read are fed in. The final read is
                     * usually partial, and passing the whole buffer would hash
                     * the leftover tail of the previous chunk along with it -
                     * silently producing a hash that matches nothing.
                    */
                    digest.update(buffer, 0, read);
                }
            }

            /*
             * Hex-encoded because the manifest stores hashes as hex strings, so
             * the comparison at the call site is a plain string equality.
            */
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {

            /*
             * Every JVM is required to ship SHA-256, so this is not a condition
             * a caller could handle or recover from - it means the runtime is
             * broken, which is why it escalates instead of becoming an IOException.
            */
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

            /*
             * The array overload exists so a download can be checked before it
             * ever reaches the disk: a payload that fails verification is never
             * written anywhere the launcher could pick it up.
            */
            digest.update(data);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(ALGORITHM + " not available", e);
        }
    }
}
