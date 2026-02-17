package de.bsommerfeld.updater.hash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HashUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void sha256File_shouldReturnConsistentHash() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");

        String hash1 = HashUtil.sha256(file);
        String hash2 = HashUtil.sha256(file);
        assertEquals(hash1, hash2);
    }

    @Test
    void sha256File_shouldReturn64CharHex() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "test content");

        String hash = HashUtil.sha256(file);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
    }

    @Test
    void sha256File_shouldMatchKnownValue() throws IOException {
        // SHA-256 of empty string:
        // e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                HashUtil.sha256(file));
    }

    @Test
    void sha256File_shouldDifferForDifferentContent() throws IOException {
        Path a = tempDir.resolve("a.txt");
        Path b = tempDir.resolve("b.txt");
        Files.writeString(a, "content A");
        Files.writeString(b, "content B");

        assertNotEquals(HashUtil.sha256(a), HashUtil.sha256(b));
    }

    @Test
    void sha256File_shouldThrowForNonexistentFile() {
        Path nonexistent = tempDir.resolve("ghost.txt");
        assertThrows(IOException.class, () -> HashUtil.sha256(nonexistent));
    }

    @Test
    void sha256Bytes_shouldReturnConsistentHash() {
        byte[] data = "hello world".getBytes();
        String hash1 = HashUtil.sha256(data);
        String hash2 = HashUtil.sha256(data);
        assertEquals(hash1, hash2);
    }

    @Test
    void sha256Bytes_shouldReturn64CharHex() {
        String hash = HashUtil.sha256("test".getBytes());
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
    }

    @Test
    void sha256Bytes_shouldMatchFileHash() throws IOException {
        String content = "identical content";
        Path file = tempDir.resolve("match.txt");
        Files.writeString(file, content);

        assertEquals(HashUtil.sha256(file), HashUtil.sha256(content.getBytes()));
    }

    @Test
    void sha256Bytes_shouldHandleEmptyArray() {
        String hash = HashUtil.sha256(new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
    }
}
