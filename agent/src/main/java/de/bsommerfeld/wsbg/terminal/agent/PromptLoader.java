package de.bsommerfeld.wsbg.terminal.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches prompt templates from classpath resource files.
 * Supports placeholder substitution via {@link #load(String, Map)}.
 */
final class PromptLoader {

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptLoader() {
    }

    /** Returns the raw prompt template from {@code prompts/<name>.txt}. */
    static String load(String name) {
        return CACHE.computeIfAbsent(name, PromptLoader::readResource);
    }

    /**
     * Returns the language-localised prompt: {@code prompts/<name>.<lang>.txt}
     * when that file exists, otherwise the base {@code prompts/<name>.txt}.
     * <p>
     * The base file is the English original and doubles as the universal
     * fallback — an unsupported language (or {@code "en"}) lands there, where the
     * {@code {{LANGUAGE}}} placeholder still names the requested output language.
     * A localised file is the prompt written natively in that language, which is
     * what stops a 4B model code-switching English structure into a German line.
     */
    static String loadLocalized(String name, String langCode) {
        // NOTE (2026-06-30): localization is ON for extraction + vision (they handle German
        // fine — extraction.de proven in run18). The COMPOSE stage deliberately bypasses this
        // and loads the English base directly (see EditorialAgent.composeUnit/composeTheme):
        // a German compose scaffold whitespace-loops the 4B model in JSON mode. Output is still
        // German via {{LANGUAGE}}. Re-enable the German compose prompt once it's shortened.
        if (langCode == null || langCode.isBlank() || "en".equalsIgnoreCase(langCode)) {
            return load(name);
        }
        String lang = langCode.toLowerCase();
        return CACHE.computeIfAbsent(name + "@" + lang, key -> {
            String localized = tryReadResource("prompts/" + name + "." + lang + ".txt");
            return localized != null ? localized : load(name);
        });
    }

    /**
     * Returns the prompt with all {@code {{KEY}}} placeholders replaced
     * by the corresponding values in the map.
     */
    static String load(String name, Map<String, String> vars) {
        String template = load(name);
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return template;
    }

    private static String readResource(String name) {
        String path = "prompts/" + name + ".txt";
        String body = tryReadResource(path);
        if (body == null) {
            throw new IllegalStateException("Prompt resource not found: " + path);
        }
        return body;
    }

    /** Reads a classpath prompt resource, or {@code null} when it does not exist. */
    private static String tryReadResource(String path) {
        try (InputStream in = PromptLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + path, e);
        }
    }
}
