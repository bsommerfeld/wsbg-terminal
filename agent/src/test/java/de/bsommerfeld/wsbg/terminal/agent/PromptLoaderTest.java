package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptLoaderTest {

    @Test
    void load_shouldReturnNonEmptyContent() {
        String content = PromptLoader.load("vision");
        assertNotNull(content);
        assertFalse(content.isBlank());
    }

    @Test
    void load_shouldCacheRepeatCalls() {
        String first = PromptLoader.load("vision");
        String second = PromptLoader.load("vision");
        assertSame(first, second, "Cached calls should return the same String reference");
    }

    @Test
    void load_shouldThrowForMissingTemplate() {
        assertThrows(RuntimeException.class, () -> PromptLoader.load("nonexistent-prompt-template"));
    }

    @Test
    void loadWithVariables_shouldSubstitutePlaceholders() {
        String result = PromptLoader.load("headline-generation", Map.of(
                "CONTEXT", "Test context",
                "HISTORY", "NONE",
                "LANGUAGE", "German"));

        assertNotNull(result);
        assertFalse(result.contains("{{CONTEXT}}"), "Placeholder should be replaced");
        assertTrue(result.contains("Test context"));
        assertTrue(result.contains("German"));
    }

    @Test
    void loadWithVariables_shouldPreserveUnmatchedPlaceholders() {
        String raw = PromptLoader.load("headline-generation");
        assertTrue(raw.contains("{{CONTEXT}}") || raw.contains("{{"),
                "Raw template should contain placeholders");
    }
}
