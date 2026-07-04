package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link ImageFetcher}'s image-processing utilities (header sniffing,
 * dimension math) directly. These were extracted from {@code AgentBrain}; the
 * {@link ImageFetcher} carries no Ollama coupling, so it is instantiated plainly
 * and the package-private helpers are called without reflection.
 */
class AgentBrainImageProcessingTest {

    private static ImageFetcher fetcher;

    @BeforeAll
    static void setUp() {
        fetcher = new ImageFetcher();
    }

    // -- constrainAndAlign --

    @Test
    void constrainAndAlign_shouldNoOpForSmallImage() {
        int[] result = fetcher.constrainAndAlign(100, 100, 1024, 32);
        // 100 / 32 * 32 = 96
        assertEquals(96, result[0]);
        assertEquals(96, result[1]);
    }

    @Test
    void constrainAndAlign_shouldConstrainWideLandscape() {
        int[] result = fetcher.constrainAndAlign(2048, 1024, 1024, 32);
        assertEquals(1024, result[0]);
        assertEquals(512, result[1]);
    }

    @Test
    void constrainAndAlign_shouldConstrainTallPortrait() {
        int[] result = fetcher.constrainAndAlign(512, 2048, 1024, 32);
        assertEquals(256, result[0]);
        assertEquals(1024, result[1]);
    }

    @Test
    void constrainAndAlign_shouldAlignToMultiple() {
        int[] result = fetcher.constrainAndAlign(33, 33, 1024, 32);
        assertEquals(32, result[0]);
        assertEquals(32, result[1]);
    }

    @Test
    void constrainAndAlign_shouldGuaranteeMinimumAlignment() {
        int[] result = fetcher.constrainAndAlign(1, 1, 1024, 32);
        assertEquals(32, result[0]);
        assertEquals(32, result[1]);
    }

    @Test
    void constrainAndAlign_shouldHandleExactMaxDim() {
        int[] result = fetcher.constrainAndAlign(1024, 1024, 1024, 32);
        assertEquals(1024, result[0]);
        assertEquals(1024, result[1]);
    }

    @Test
    void constrainAndAlign_shouldHandleSquareOversize() {
        int[] result = fetcher.constrainAndAlign(2000, 2000, 1024, 32);
        assertEquals(1024, result[0]);
        assertEquals(1024, result[1]);
    }

    // -- isTextResponse --

    @Test
    void isTextResponse_shouldDetectHtml() {
        assertTrue(fetcher.isTextResponse("<html>test</html>".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void isTextResponse_shouldDetectDoctype() {
        assertTrue(fetcher.isTextResponse("<!DOCTYPE html>".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void isTextResponse_shouldDetectJson() {
        assertTrue(fetcher.isTextResponse("{\"error\": true}".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void isTextResponse_shouldDetectXml() {
        assertTrue(fetcher.isTextResponse("<?xml version=\"1.0\"?>".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void isTextResponse_shouldDetectAccessDenied() {
        assertTrue(fetcher.isTextResponse("Access denied for this resource".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void isTextResponse_shouldReturnFalseForBinaryData() {
        byte[] jpegHeader = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10 };
        assertFalse(fetcher.isTextResponse(jpegHeader));
    }

    @Test
    void isTextResponse_shouldReturnFalseForNull() {
        assertFalse(fetcher.isTextResponse(null));
    }

    @Test
    void isTextResponse_shouldReturnFalseForTooShort() {
        assertFalse(fetcher.isTextResponse(new byte[] { 1, 2, 3 }));
    }

    // -- detectMimeType --

    @Test
    void detectMimeType_shouldDetectJpeg() {
        byte[] data = new byte[12];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xD8;
        assertEquals("image/jpeg", fetcher.detectMimeType(data));
    }

    @Test
    void detectMimeType_shouldDetectPng() {
        byte[] data = new byte[12];
        data[0] = (byte) 0x89;
        data[1] = 0x50;
        data[2] = 0x4E;
        data[3] = 0x47;
        assertEquals("image/png", fetcher.detectMimeType(data));
    }

    @Test
    void detectMimeType_shouldDetectWebP() {
        byte[] data = new byte[12];
        data[0] = 'R';
        data[1] = 'I';
        data[2] = 'F';
        data[3] = 'F';
        data[8] = 'W';
        data[9] = 'E';
        data[10] = 'B';
        data[11] = 'P';
        assertEquals("image/webp", fetcher.detectMimeType(data));
    }

    @Test
    void detectMimeType_shouldReturnNullForUnknownFormat() {
        assertNull(fetcher.detectMimeType(new byte[12]));
    }

    @Test
    void detectMimeType_shouldReturnNullForNull() {
        assertNull(fetcher.detectMimeType(null));
    }

    @Test
    void detectMimeType_shouldReturnNullForTooShort() {
        assertNull(fetcher.detectMimeType(new byte[5]));
    }
}
