package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests AgentBrain's image processing utilities via reflection.
 * These methods are private instance methods containing non-trivial logic
 * (header sniffing, dimension math) that warrants direct testing.
 *
 * An uninitialized AgentBrain instance is created via Mockito to bypass
 * the constructor's Ollama connection, since only stateless utility methods
 * are tested here.
 */
class AgentBrainImageProcessingTest {

    private static AgentBrain brain;

    @BeforeAll
    static void setUp() {
        // Create instance without triggering Ollama connection
        brain = Mockito.mock(AgentBrain.class, Mockito.CALLS_REAL_METHODS);
    }

    // -- constrainAndAlign --

    @Test
    void constrainAndAlign_shouldNoOpForSmallImage() throws Exception {
        int[] result = invokeConstrainAndAlign(100, 100, 1024, 32);
        // 100 / 32 * 32 = 96
        assertEquals(96, result[0]);
        assertEquals(96, result[1]);
    }

    @Test
    void constrainAndAlign_shouldConstrainWideLandscape() throws Exception {
        int[] result = invokeConstrainAndAlign(2048, 1024, 1024, 32);
        assertEquals(1024, result[0]);
        assertEquals(512, result[1]);
    }

    @Test
    void constrainAndAlign_shouldConstrainTallPortrait() throws Exception {
        int[] result = invokeConstrainAndAlign(512, 2048, 1024, 32);
        assertEquals(256, result[0]);
        assertEquals(1024, result[1]);
    }

    @Test
    void constrainAndAlign_shouldAlignToMultiple() throws Exception {
        int[] result = invokeConstrainAndAlign(33, 33, 1024, 32);
        assertEquals(32, result[0]);
        assertEquals(32, result[1]);
    }

    @Test
    void constrainAndAlign_shouldGuaranteeMinimumAlignment() throws Exception {
        int[] result = invokeConstrainAndAlign(1, 1, 1024, 32);
        assertEquals(32, result[0]);
        assertEquals(32, result[1]);
    }

    @Test
    void constrainAndAlign_shouldHandleExactMaxDim() throws Exception {
        int[] result = invokeConstrainAndAlign(1024, 1024, 1024, 32);
        assertEquals(1024, result[0]);
        assertEquals(1024, result[1]);
    }

    @Test
    void constrainAndAlign_shouldHandleSquareOversize() throws Exception {
        int[] result = invokeConstrainAndAlign(2000, 2000, 1024, 32);
        assertEquals(1024, result[0]);
        assertEquals(1024, result[1]);
    }

    // -- isTextResponse --

    @Test
    void isTextResponse_shouldDetectHtml() throws Exception {
        assertTrue(invokeIsTextResponse("<html>test</html>".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void isTextResponse_shouldDetectDoctype() throws Exception {
        assertTrue(invokeIsTextResponse("<!DOCTYPE html>".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void isTextResponse_shouldDetectJson() throws Exception {
        assertTrue(invokeIsTextResponse("{\"error\": true}".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void isTextResponse_shouldDetectXml() throws Exception {
        assertTrue(invokeIsTextResponse("<?xml version=\"1.0\"?>".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void isTextResponse_shouldDetectAccessDenied() throws Exception {
        assertTrue(invokeIsTextResponse("Access denied for this resource".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void isTextResponse_shouldReturnFalseForBinaryData() throws Exception {
        byte[] jpegHeader = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10 };
        assertFalse(invokeIsTextResponse(jpegHeader));
    }

    @Test
    void isTextResponse_shouldReturnFalseForNull() throws Exception {
        assertFalse(invokeIsTextResponse(null));
    }

    @Test
    void isTextResponse_shouldReturnFalseForTooShort() throws Exception {
        assertFalse(invokeIsTextResponse(new byte[] { 1, 2, 3 }));
    }

    // -- detectMimeType --

    @Test
    void detectMimeType_shouldDetectJpeg() throws Exception {
        byte[] data = new byte[12];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xD8;
        assertEquals("image/jpeg", invokeDetectMimeType(data));
    }

    @Test
    void detectMimeType_shouldDetectPng() throws Exception {
        byte[] data = new byte[12];
        data[0] = (byte) 0x89;
        data[1] = 0x50;
        data[2] = 0x4E;
        data[3] = 0x47;
        assertEquals("image/png", invokeDetectMimeType(data));
    }

    @Test
    void detectMimeType_shouldDetectWebP() throws Exception {
        byte[] data = new byte[12];
        data[0] = 'R';
        data[1] = 'I';
        data[2] = 'F';
        data[3] = 'F';
        data[8] = 'W';
        data[9] = 'E';
        data[10] = 'B';
        data[11] = 'P';
        assertEquals("image/webp", invokeDetectMimeType(data));
    }

    @Test
    void detectMimeType_shouldReturnNullForUnknownFormat() throws Exception {
        assertNull(invokeDetectMimeType(new byte[12]));
    }

    @Test
    void detectMimeType_shouldReturnNullForNull() throws Exception {
        assertNull(invokeDetectMimeType(null));
    }

    @Test
    void detectMimeType_shouldReturnNullForTooShort() throws Exception {
        assertNull(invokeDetectMimeType(new byte[5]));
    }

    // -- Reflection Helpers (instance methods) --

    private int[] invokeConstrainAndAlign(int w, int h, int maxDim, int alignment) throws Exception {
        Method method = AgentBrain.class.getDeclaredMethod("constrainAndAlign",
                int.class, int.class, int.class, int.class);
        method.setAccessible(true);
        return (int[]) method.invoke(brain, w, h, maxDim, alignment);
    }

    private boolean invokeIsTextResponse(byte[] data) throws Exception {
        Method method = AgentBrain.class.getDeclaredMethod("isTextResponse", byte[].class);
        method.setAccessible(true);
        return (boolean) method.invoke(brain, (Object) data);
    }

    private String invokeDetectMimeType(byte[] data) throws Exception {
        Method method = AgentBrain.class.getDeclaredMethod("detectMimeType", byte[].class);
        method.setAccessible(true);
        return (String) method.invoke(brain, (Object) data);
    }
}
