package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift guard for the localized prompt twins: every {@code <name>.de.txt} must stay
 * structurally in sync with its English base {@code <name>.txt}. The two files are
 * maintained BY HAND in parallel (there is no generation step), which is exactly how
 * the English unit prompt once grew a duplicated rule sentence the German twin never
 * had. This test pins the contract surface — placeholders, section count, JSON keys,
 * enum tokens — so an edit to one language that forgets the other fails fast.
 *
 * <p>Deliberately NOT checked: prose length or wording — the variants are native
 * texts, not translations of record.
 */
class PromptParityTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");
    private static final Pattern JSON_KEY = Pattern.compile("\"([a-zA-Z]+)\"\\s*:");

    /**
     * The closed contract vocabulary the Java side parses or the schema enforces.
     * Presence must match between the twins; prose may differ freely.
     */
    private static final List<String> CONTRACT_TOKENS = List.of(
            "NORMAL", "IMPORTANT", "NONE", "RUNNER", "SQUEEZE", "BREAKOUT",
            "HARD_CATALYST", "POOLED_CALL", "EXTREME_DIRECTION",
            "BULLISH", "BEARISH", "MIXED", "FOMO", "CAPITULATION",
            "REVERSAL", "NEUTRAL", "NEW", "UPDATE");

    @Test
    void localizedPromptsMatchTheirBaseStructurally() throws Exception {
        List<Path> localized = promptFiles().filter(p -> p.getFileName().toString().endsWith(".de.txt")).toList();
        assertFalse(localized.isEmpty(), "no .de.txt prompts found — resource path broken?");

        for (Path de : localized) {
            String name = de.getFileName().toString().replace(".de.txt", "");
            Path base = de.resolveSibling(name + ".txt");
            assertTrue(Files.exists(base), name + ".de.txt has no English base " + name + ".txt");

            String baseText = Files.readString(base, StandardCharsets.UTF_8);
            String deText = Files.readString(de, StandardCharsets.UTF_8);

            // Placeholders: identical, except {{LANGUAGE}} — the base doubles as the
            // universal fallback and names the output language; a native variant
            // already IS that language and may omit it.
            Set<String> basePh = matches(PLACEHOLDER, baseText);
            Set<String> dePh = matches(PLACEHOLDER, deText);
            Set<String> missingInDe = new TreeSet<>(basePh);
            missingInDe.removeAll(dePh);
            missingInDe.remove("LANGUAGE");
            assertEquals(Set.of(), missingInDe, name + ": placeholders missing in the .de twin");
            Set<String> extraInDe = new TreeSet<>(dePh);
            extraInDe.removeAll(basePh);
            assertEquals(Set.of(), extraInDe, name + ": placeholders only in the .de twin");

            // Section structure: same number of "## " headings.
            assertEquals(count(baseText, "\n## "), count(deText, "\n## "),
                    name + ": '## ' section count differs between base and .de");

            // Output contract: same JSON keys in the sample objects.
            assertEquals(matches(JSON_KEY, baseText), matches(JSON_KEY, deText),
                    name + ": JSON keys in the output contract differ");

            // Closed enum vocabulary: same presence/absence per token.
            for (String token : CONTRACT_TOKENS) {
                assertEquals(baseText.contains(token), deText.contains(token),
                        name + ": contract token '" + token + "' present in one twin only");
            }
        }
    }

    private static Stream<Path> promptFiles() throws Exception {
        URL url = PromptParityTest.class.getClassLoader().getResource("prompts");
        assertTrue(url != null && "file".equals(url.getProtocol()),
                "prompts resource dir not resolvable as a file path (running from a jar?)");
        return Files.list(Path.of(url.toURI())).filter(p -> p.toString().endsWith(".txt"));
    }

    private static Set<String> matches(Pattern p, String text) {
        Set<String> out = new HashSet<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }
}
