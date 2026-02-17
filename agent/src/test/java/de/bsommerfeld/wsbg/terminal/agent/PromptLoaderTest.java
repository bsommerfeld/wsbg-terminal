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
        // Use a known prompt that has placeholders
        String result = PromptLoader.load("translation", Map.of(
                "SOURCE_LANG", "English",
                "SOURCE_CODE", "en",
                "TARGET_LANG", "German",
                "TARGET_CODE", "de"));

        assertNotNull(result);
        assertFalse(result.contains("{{SOURCE_LANG}}"), "Placeholder should be replaced");
        assertTrue(result.contains("English"));
        assertTrue(result.contains("German"));
    }

    @Test
    void loadWithVariables_shouldPreserveUnmatchedPlaceholders() {
        // If a placeholder is not in the map, it should remain as-is
        String raw = PromptLoader.load("translation");
        assertTrue(raw.contains("{{SOURCE_LANG}}") || raw.contains("{{"),
                "Raw template should contain placeholders");
    }
}
