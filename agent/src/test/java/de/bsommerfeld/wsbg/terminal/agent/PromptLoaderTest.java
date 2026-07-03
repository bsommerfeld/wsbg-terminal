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
        String result = PromptLoader.load("headline-compose-unit", Map.of("LANGUAGE", "German"));

        assertNotNull(result);
        assertFalse(result.contains("{{LANGUAGE}}"), "Placeholder should be replaced");
        assertTrue(result.contains("German"));
    }

    @Test
    void loadWithVariables_shouldPreserveUnmatchedPlaceholders() {
        String raw = PromptLoader.load("headline-compose-unit");
        assertTrue(raw.contains("{{"), "Raw template should still contain placeholders");
    }

    @Test
    void loadLocalized_shouldReturnGermanVariantForDe() {
        String de = PromptLoader.loadLocalized("headline-compose-unit", "de");
        assertTrue(de.contains("auf Deutsch"), "the German prompt is written natively in German");
        assertFalse(de.contains("Reply with ONE JSON object"), "not the English base");
    }

    @Test
    void loadLocalized_shouldFallBackToBaseForEnglishAndUnknownLanguages() {
        String base = PromptLoader.load("headline-compose-unit");
        assertSame(base, PromptLoader.loadLocalized("headline-compose-unit", "en"),
                "English maps straight to the base file");
        assertSame(base, PromptLoader.loadLocalized("headline-compose-unit", "fr"),
                "an unsupported language falls back to the English base (with {{LANGUAGE}})");
        assertSame(base, PromptLoader.loadLocalized("headline-compose-unit", null),
                "null language falls back to the base");
    }

    @Test
    void loadLocalized_germanPromptsExistForEveryLanguageSensitiveStage() {
        for (String name : new String[] {"headline-compose-unit", "subject-extraction"}) {
            String de = PromptLoader.loadLocalized(name, "de");
            assertNotSame(PromptLoader.load(name), de, name + " has a distinct German variant");
            assertFalse(de.isBlank());
        }
    }
}
