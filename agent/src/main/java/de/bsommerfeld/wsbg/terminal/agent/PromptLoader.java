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

    /**
     * Include marker: a line {@code {{include:<name>}}} splices the named
     * prompt snippet (language-matched) in place. ONE canonical craft block
     * instead of five drifting copies (user mandate 2026-07-16 "stark
     * entwirren"): shared rules live once, each prompt keeps only its
     * role-specific deltas. One level deep - an include inside an included
     * snippet is a wiring error and throws at load time.
     */
    private static final java.util.regex.Pattern INCLUDE =
            java.util.regex.Pattern.compile("\\{\\{include:([a-z0-9-]+)}}");

    /** Returns the raw prompt template from {@code prompts/<name>.txt}. */
    static String load(String name) {
        return CACHE.computeIfAbsent(name,
                key -> resolveIncludes(readResource(key), null));
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
        // NOTE (2026-07-01): localization is ON for EVERY stage — extraction + vision
        // (extraction.de proven in run18) AND compose (EditorialAgent.composeUnit loads
        // via this method too). The compose stage used to be held to the English base
        // because a German scaffold on top of the old fat 9-field JSON output
        // whitespace-looped the 4B model; now that the output is slimmed the model
        // commits cleanly on the localized scaffold too (0 whiffs in run27).
        // The output language is still named explicitly by {{LANGUAGE}} regardless of scaffold.
        if (langCode == null || langCode.isBlank() || "en".equalsIgnoreCase(langCode)) {
            return load(name);
        }
        String lang = langCode.toLowerCase();
        return CACHE.computeIfAbsent(name + "@" + lang, key -> {
            String localized = tryReadResource("prompts/" + name + "." + lang + ".txt");
            return localized != null ? resolveIncludes(localized, lang) : load(name);
        });
    }

    /** Splices {@code {{include:<name>}}} lines with the language-matched snippet. */
    private static String resolveIncludes(String template, String lang) {
        java.util.regex.Matcher m = INCLUDE.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 2048);
        while (m.find()) {
            String inc = m.group(1);
            String body = lang != null
                    ? tryReadResource("prompts/" + inc + "." + lang + ".txt") : null;
            if (body == null) body = tryReadResource("prompts/" + inc + ".txt");
            if (body == null) {
                throw new IllegalStateException("Included prompt snippet not found: " + inc);
            }
            if (INCLUDE.matcher(body).find()) {
                throw new IllegalStateException("Nested include in snippet: " + inc);
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(body.trim()));
        }
        m.appendTail(out);
        return out.toString();
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
