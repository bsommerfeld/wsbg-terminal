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
        try (InputStream in = PromptLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Prompt resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + path, e);
        }
    }
}
