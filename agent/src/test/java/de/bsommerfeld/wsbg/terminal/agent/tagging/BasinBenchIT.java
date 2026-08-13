package de.bsommerfeld.wsbg.terminal.agent.tagging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.text.TextMatch;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The full-basin measurement stand: replays a REAL captured basin (107 feed
 * dumps, ~5000 articles) against the new judgment engine AND against the old
 * any-word rule, side by side, for the subjects whose failure rates were
 * measured before the rebuild. Not a pass/fail test of exact counts — the
 * basin snapshot is external data — but the instrument that makes every
 * future change to the engine measurable instead of anecdotal.
 *
 * <p>Run with:
 * <pre>
 * mvn test -pl agent -Dtest=BasinBenchIT -Dtest.excludedGroups= \
 *   -Dtagging.basin=/path/to/basin \
 *   -Dtagging.instruments=$HOME/'Library/Application Support/wsbg-terminal/instruments/instruments.jsonl'
 * </pre>
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "tagging.basin", matches = ".+")
class BasinBenchIT {

    @Test
    void measureBasin() throws Exception {
        Path basinDir = Path.of(System.getProperty("tagging.basin"));
        Path corpusFile = Path.of(System.getProperty("tagging.instruments",
                System.getProperty("user.home")
                        + "/Library/Application Support/wsbg-terminal/instruments/instruments.jsonl"));

        List<Article> basin = loadBasin(basinDir);
        List<InstrumentEntry> corpus = loadCorpus(corpusFile);
        System.out.printf("basin: %d articles, corpus: %d instruments%n", basin.size(), corpus.size());

        try (LuceneArticleTagger tagger = new LuceneArticleTagger(() -> corpus, null)) {
            tagger.ingest(basin);

            Map<String, Article> byId = new LinkedHashMap<>();
            for (Article a : basin) byId.putIfAbsent(a.identity(), a);

            // The measured failure subjects + recall subjects.
            String[] subjects = {
                    "Canopy Growth Corp", "Robinhood Markets Inc", "Trade Desk, Inc.",
                    "Micron Technology Inc", "Marvell Technology", "Deutsche Bank AG",
                    "Gold", "SoFi Technologies, Inc.", "TUI AG", "Super Micro Computer",
                    "Nvidia Corp", "Apple Inc.", "Tesla, Inc.", "Rheinmetall AG",
                    "Deutsche Telekom AG", "Bayer AG", "Volkswagen AG", "Siemens Energy",
            };
            System.out.printf("%n%-28s %8s %8s%n", "subject", "old", "new");
            for (String name : subjects) {
                Set<String> words = TextMatch.significantWords(name);
                long oldHits = basin.stream()
                        .filter(a -> TextMatch.matchesAny(haystack(a), words))
                        .count();
                ResolvedInstrument instrument = ResolvedInstrument.ofName(name);
                Set<String> newHits = tagger.articlesFor(instrument);
                Set<String> mentions = tagger.mentionsFor(instrument);
                System.out.printf("%-28s %8d %8d  (+%d MENTIONED)%n",
                        name, oldHits, newHits.size(), mentions.size());
                int shown = 0;
                for (String id : newHits) {
                    Article a = byId.get(id);
                    if (a != null && shown++ < 6) {
                        System.out.println("    + " + a.title());
                    }
                }
                if (Boolean.getBoolean("tagging.showOld")) {
                    AtomicInteger n = new AtomicInteger();
                    basin.stream().filter(a -> TextMatch.matchesAny(haystack(a), words))
                            .filter(a -> !newHits.contains(a.identity()))
                            .limit(6)
                            .forEach(a -> System.out.println("    - " + a.title()));
                }
            }
        }
    }

    private static String haystack(Article a) {
        String title = a.title() == null ? "" : a.title();
        String summary = a.summary() == null ? "" : a.summary();
        return summary.isEmpty() ? title : title + ' ' + summary;
    }

    private static List<Article> loadBasin(Path dir) throws Exception {
        List<Article> out = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".xml")).sorted().toList()) {
                try {
                    Document doc = dbf.newDocumentBuilder().parse(p.toFile());
                    NodeList items = doc.getElementsByTagName("*");
                    for (int i = 0; i < items.getLength(); i++) {
                        Node n = items.item(i);
                        String local = n.getNodeName().contains(":")
                                ? n.getNodeName().substring(n.getNodeName().indexOf(':') + 1)
                                : n.getNodeName();
                        if (!local.equals("item") && !local.equals("entry")) continue;
                        String title = "";
                        String summary = "";
                        NodeList children = n.getChildNodes();
                        for (int c = 0; c < children.getLength(); c++) {
                            Node ch = children.item(c);
                            if (!(ch instanceof Element el)) continue;
                            String tag = el.getTagName().contains(":")
                                    ? el.getTagName().substring(el.getTagName().indexOf(':') + 1)
                                    : el.getTagName();
                            if (tag.equals("title")) title = text(el);
                            else if (tag.equals("description") || tag.equals("summary")) summary = text(el);
                        }
                        if (title.isBlank()) continue;
                        String id = "b" + seq.incrementAndGet();
                        out.add(new Article(id, strip(title), p.getFileName().toString(),
                                "https://basin.example/" + id, Instant.now(), List.of(),
                                null, strip(summary), false));
                    }
                } catch (Exception e) {
                    System.out.println("skip " + p.getFileName() + ": " + e.getMessage());
                }
            }
        }
        return out;
    }

    private static String text(Element el) {
        String t = el.getTextContent();
        return t == null ? "" : t.trim();
    }

    /** Feed teasers carry markup; the pool sources strip it, the bench does too. */
    private static String strip(String s) {
        return s.replaceAll("<[^>]+>", " ").replaceAll("&[a-zA-Z]+;", " ").trim();
    }

    private static List<InstrumentEntry> loadCorpus(Path file) throws Exception {
        List<InstrumentEntry> out = new ArrayList<>();
        ObjectMapper json = new ObjectMapper();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n = json.readTree(line);
                out.add(new InstrumentEntry(n.path("symbol").asText(""), n.path("name").asText(""),
                        n.path("isin").isMissingNode() || n.path("isin").isNull() ? null : n.path("isin").asText(),
                        n.path("exchange").asText(""), n.path("type").asText(""), n.path("source").asText("")));
            }
        }
        return out;
    }
}
