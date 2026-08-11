package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptLoaderTest {

    @Test
    void load_shouldReturnNonEmptyContent() {
        String content = PromptLoader.load("subject-extraction");
        assertNotNull(content);
        assertFalse(content.isBlank());
    }

    /**
     * A shared canon lives ONCE and is spliced via {@code {{include:...}}}
     * (2026-07-16 "stark entwirren") - a loaded prompt carries the resolved
     * canon in its own language and never the raw include token.
     *
     * <p>Driven by the test-only fixture pair {@code include-probe} /
     * {@code include-probe-core} on the test classpath: the splice mechanism is
     * part of the loader, so it stays pinned even while no shipped prompt
     * happens to use an include.
     */
    @Test
    void load_shouldResolveIncludesLanguageMatched() {
        String en = PromptLoader.load("include-probe");
        assertTrue(en.contains("SHARED CANON MARKER EN"), "EN core spliced");
        assertFalse(en.contains("{{include:"), "no raw include token");
        String de = PromptLoader.loadLocalized("include-probe", "de");
        assertTrue(de.contains("SHARED CANON MARKER DE"), "DE core spliced");
        assertFalse(de.contains("{{include:"), "no raw include token");
    }

    @Test
    void load_shouldCacheRepeatCalls() {
        String first = PromptLoader.load("subject-extraction");
        String second = PromptLoader.load("subject-extraction");
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
