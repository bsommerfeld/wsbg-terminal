package de.bsommerfeld.wsbg.terminal.agent.tagging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.instruments.AliasStore;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Prüfstand: every MEASURED failure class of the old any-word match is a
 * fixture here — growth, markets, technology, Trade Desk/World Trade Center,
 * Deutsche Bank, gold (sense split), SoFi (compound), the TUI attribution
 * case — plus the recall cases the strict all-words rule destroyed (Super
 * Micro) and the edges (empty basin, unknown instrument, no teaser,
 * non-Latin script). The instrument universe is a REAL corpus subset
 * ({@code tagging/corpus-fixture.jsonl}), so the entity-DF thresholds are
 * exercised against production-shaped data, not friendly numbers.
 */
class LuceneArticleTaggerTest {

    private static List<InstrumentEntry> universe;
    private LuceneArticleTagger tagger;
    private final AtomicInteger seq = new AtomicInteger();

    private static List<InstrumentEntry> loadUniverse() throws Exception {
        if (universe != null) return universe;
        List<InstrumentEntry> out = new ArrayList<>();
        ObjectMapper json = new ObjectMapper();
        try (InputStream in = LuceneArticleTaggerTest.class.getClassLoader()
                .getResourceAsStream("tagging/corpus-fixture.jsonl");
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n = json.readTree(line);
                out.add(new InstrumentEntry(n.path("symbol").asText(""), n.path("name").asText(""),
                        n.path("isin").isMissingNode() || n.path("isin").isNull() ? null : n.path("isin").asText(),
                        n.path("exchange").asText(""), n.path("type").asText(""), n.path("source").asText("")));
            }
        }
        universe = out;
        return out;
    }

    @BeforeEach
    void setUp() throws Exception {
        List<InstrumentEntry> u = loadUniverse();
        tagger = new LuceneArticleTagger(() -> u, null);
        background();
    }

    /**
     * Neutral background noise, so basin statistics (NPMI, ratios) have a
     * denominator like the real world's — a basin of eight articles where one
     * word appears in all eight tells the statistics nothing. Avoids every
     * token the assertions query for.
     */
    private void background() {
        List<Article> filler = new ArrayList<>();
        String[] titles = {
                "Bundesliga: Heimsieg im Spitzenspiel",
                "Wetterdienst warnt vor Gewittern am Wochenende",
                "Stadtrat beschließt neuen Radweg",
                "Museum zeigt Ausstellung zur Antike",
                "Zugverkehr nach Störung wieder normal",
                "Neuer Fahrplan für den Nahverkehr vorgestellt",
                "Festival lockt tausende Besucher",
                "Feuerwehr übt den Ernstfall im Tunnel",
                "Council approves new housing project",
                "Local team wins championship opener",
                "Heavy rain expected across the region",
                "Voters head to polls in local election",
                "Airport reopens after runway repairs",
                "New library opens downtown",
                "Farmers report strong harvest season",
                "University announces research grant",
                "Hospital expands emergency ward",
                "Ferry service resumes after storm",
                "Filmfestival vergibt Hauptpreis",
                "Umfrage sieht knappes Rennen vor der Wahl",
                "Zoo freut sich über Nachwuchs bei den Pinguinen",
                "Marathonstrecke führt durch die Altstadt",
                "Theaterpremiere begeistert das Publikum",
                "Handwerk klagt über Nachwuchsmangel"};
        String[] leads = {
                "Die Stadt und die Region ziehen eine positive Bilanz.",
                "Die Veranstalter und die Besucher zeigten sich zufrieden.",
                "Die Behörden und die Anwohner suchen nach einer Lösung.",
                "The council and the residents discussed the plans.",
                "The organisers and the fans praised the event.",
                "Die Polizei und die Feuerwehr waren im Einsatz."};
        for (int i = 0; i < titles.length; i++) {
            filler.add(article(titles[i], leads[i % leads.length]));
        }
        pour(filler.toArray(Article[]::new));
    }

    @AfterEach
    void tearDown() throws Exception {
        tagger.close();
    }

    // ------------------------------------------------------------------ helpers

    private Article article(String title, String summary) {
        String id = "a" + seq.incrementAndGet();
        return new Article(id, title, "Pub", "https://x.example/" + id, Instant.now(),
                List.of(), null, summary, false);
    }

    private Article article(String title) {
        return article(title, null);
    }

    private void pour(Article... articles) {
        tagger.ingest(List.of(articles));
    }

    private Set<String> hits(ResolvedInstrument instrument) {
        return tagger.articlesFor(instrument);
    }

    private static ResolvedInstrument byName(String name) {
        return ResolvedInstrument.ofName(name);
    }

    private static ResolvedInstrument byTicker(String symbol, String name) {
        return new ResolvedInstrument(Optional.empty(), Ticker.parse(symbol), name);
    }

    // ------------------------------------------------------------------ the measured failure cases

    @Test
    void canopyGrowthNoLongerCatchesGrowthStories() {
        Article russia = article("Russian Economy Returns to Growth",
                "GDP expanded for the first time in two years.");
        Article canopy = article("Canopy Growth kündigt Kapitalerhöhung an",
                "Der kanadische Cannabis-Konzern braucht frisches Geld.");
        pour(russia, canopy);
        Set<String> got = hits(byName("Canopy Growth Corp"));
        assertEquals(Set.of(canopy.uuid()), got);
    }

    @Test
    void robinhoodMarketsNoLongerCatchesMarketRoundups() {
        Article roundup = article("European markets close higher after Fed minutes");
        Article hood = article("Robinhood startet Krypto-Handel in Europa");
        pour(roundup, hood);
        assertEquals(Set.of(hood.uuid()), hits(byName("Robinhood Markets Inc")));
    }

    @Test
    void micronNoLongerCatchesTechnologyStories() {
        Article tech = article("Technology stocks rally on rate cut hopes");
        Article micron = article("Micron übertrifft die Erwartungen deutlich");
        pour(tech, micron);
        assertEquals(Set.of(micron.uuid()), hits(byName("Micron Technology Inc")));
    }

    @Test
    void tradeDeskNoLongerCatchesTheWorldTradeCenter() {
        Article wtc = article("World Trade Center anniversary ceremony held in New York");
        // Basin background: "trade" as the common word it is — lower-case,
        // mid-sentence. This is what the case statistic reads.
        Article t1 = article("US and EU reach deal in trade dispute",
                "The trade agreement ends months of tariff threats.");
        Article t2 = article("China trade surplus widens",
                "Analysts see trade flows normalising.");
        Article t3 = article("Trump escalates trade war rhetoric",
                "Global trade volumes could suffer, economists warn.");
        Article ttd = article("The Trade Desk stock jumps after earnings beat");
        pour(wtc, t1, t2, t3, ttd);
        Set<String> got = hits(byName("Trade Desk, Inc."));
        assertTrue(got.contains(ttd.uuid()), "the real Trade Desk article must match");
        assertFalse(got.contains(wtc.uuid()), "World Trade Center must not match");
        assertFalse(got.contains(t1.uuid()) || got.contains(t2.uuid()) || got.contains(t3.uuid()),
                "trade-war stories must not match");
    }

    @Test
    void deutscheBankNeedsBothWordsTogether() {
        Article economy = article("Deutsche Wirtschaft schrumpft im zweiten Quartal");
        Article boe = article("Bank of England hebt die Zinsen an");
        Article db = article("Deutsche Bank verdoppelt den Gewinn");
        pour(economy, boe, db);
        assertEquals(Set.of(db.uuid()), hits(byName("Deutsche Bank AG")));
    }

    @Test
    void goldSenseSplitsIntoMarketAndMedals_deterministicallyExcludedWithoutArbiter() {
        List<Article> market = List.of(
                article("Goldpreis steigt auf Rekordhoch", "Silber und Gold legen zu, die Unze kostet mehr."),
                article("Gold und Silber gefragt", "Anleger kaufen die Unze Gold als sicheren Hafen."),
                article("Gold bleibt teuer", "Die Feinunze Gold notiert fest, Silber zieht nach."),
                article("Zentralbanken kaufen Gold", "Die Unze Gold verteuert sich, Silber ebenso."));
        List<Article> sport = List.of(
                article("Schwimm-EM: Gold für deutsche Staffel", "Die Medaille war der Höhepunkt der EM."),
                article("Gold und Silbermedaille bei der Schwimm-EM", "Die Staffel holt die Medaille."),
                article("EM-Gold im Becken", "Nach der Medaille von gestern holt die Staffel erneut Gold bei der EM."),
                article("Wieder Gold für die Staffel", "Die EM bringt die nächste Medaille."));
        List<Article> all = new ArrayList<>(market);
        all.addAll(sport);
        pour(all.toArray(Article[]::new));

        // Without an arbiter the ambiguous name yields NOTHING — the measured
        // wrong answer (swimming medals as gold-market news) is gone, and the
        // open question is marked PENDING, not guessed.
        Set<String> got = hits(byName("Gold"));
        assertTrue(got.isEmpty(), "ambiguous single-word name must not guess: " + got);
        Map<String, TagVerdict> verdicts = tagger.judgments(byName("Gold"));
        assertTrue(verdicts.values().stream()
                        .anyMatch(v -> v.role() == TagVerdict.Role.PENDING),
                "the open sense question must be visible as PENDING");
    }

    @Test
    void goldSenseArbiterAdmitsTheMarketClusterAndKeepsTheMedalsOut() throws Exception {
        goldBasin();
        // The arbiter sees each usage cluster ONCE: metals yes, medals no.
        tagger.setArbiter((line, terms, titles) -> {
            boolean metals = terms.contains("silber") || terms.contains("unze");
            boolean medals = terms.contains("medaille") || terms.contains("staffel");
            if (metals && !medals) return Optional.of(true);
            if (medals) return Optional.of(false);
            return Optional.empty();
        });
        // First query queues the sense questions, the verdicts land async.
        hits(byName("Gold"));
        Set<String> got = Set.of();
        for (int i = 0; i < 100; i++) {
            got = hits(byName("Gold"));
            if (!got.isEmpty()) break;
            Thread.sleep(20);
        }
        assertFalse(got.isEmpty(), "market-cluster gold articles must match once arbitrated");
        Map<String, TagVerdict> verdicts = tagger.judgments(byName("Gold"));
        for (Map.Entry<String, TagVerdict> e : verdicts.entrySet()) {
            if (e.getValue().role() == TagVerdict.Role.SUBJECT) {
                assertTrue(goldMarketIds.contains(e.getKey()),
                        "a medal article slipped through: " + e.getKey());
            }
        }
    }

    private Set<String> goldMarketIds;

    private void goldBasin() {
        Article m1 = article("Goldpreis steigt auf Rekordhoch", "Silber und Gold legen zu, die Unze kostet mehr.");
        Article m2 = article("Gold und Silber gefragt", "Anleger kaufen die Unze Gold als sicheren Hafen.");
        Article m3 = article("Gold bleibt teuer", "Die Feinunze Gold notiert fest, Silber zieht nach.");
        Article m4 = article("Zentralbanken kaufen Gold", "Die Unze Gold verteuert sich, Silber ebenso.");
        Article s1 = article("Schwimm-EM: Gold für deutsche Staffel", "Die Medaille war der Höhepunkt der EM.");
        Article s2 = article("Gold und Silbermedaille bei der Schwimm-EM", "Die Staffel holt die Medaille.");
        Article s3 = article("EM-Gold im Becken", "Nach der Medaille von gestern holt die Staffel erneut Gold bei der EM.");
        Article s4 = article("Wieder Gold für die Staffel", "Die EM bringt die nächste Medaille.");
        pour(m1, m2, m3, m4, s1, s2, s3, s4);
        goldMarketIds = Set.of(m1.uuid(), m2.uuid(), m3.uuid(), m4.uuid());
    }

    @Test
    void sofiEclipseGlassesNeverReachTheFintech() {
        // The measured case: German colloquial "Sofi" = Sonnenfinsternis, and
        // the compound "Sofi-Brille" dominated the basin usage. One of these
        // falsified a published headline.
        Article b1 = article("Sofi-Brillen jetzt kaufen", "Wo es die Sofi-Brille für die Sonnenfinsternis gibt.");
        Article b2 = article("Sonnenfinsternis: Sofi-Brille richtig nutzen", "Die Sofi-Brille schützt die Augen.");
        Article b3 = article("Sofi-Brillen sind ausverkauft", "Vor der Sonnenfinsternis boomt die Sofi-Brille.");
        Article standalone = article("Die Sofi am Samstag", "Wo die Sonnenfinsternis am besten zu sehen ist.");
        Article fintech = article("SoFi meldet Rekordquartal $SOFI", "Das Fintech wächst weiter.");
        pour(b1, b2, b3, standalone, fintech);
        Set<String> got = hits(byTicker("SOFI", "SoFi Technologies, Inc."));
        assertTrue(got.contains(fintech.uuid()), "the cashtagged SoFi article must match");
        assertFalse(got.contains(b1.uuid()) || got.contains(b2.uuid()) || got.contains(b3.uuid()),
                "Sofi-Brille compounds must never match");
        assertFalse(got.contains(standalone.uuid()),
                "a bare eclipse 'Sofi' must not match without sense confirmation");
    }

    @Test
    void analystAttributionIsMentionedNotSubject() {
        // Eight analyst notes — the learned attribution pattern — plus one
        // genuine Deutsche Bank story.
        String[] targets = {"Tui", "Nvidia", "Bayer", "Rheinmetall", "Tesla", "Apple", "Micron", "Marvell"};
        List<Article> notes = new ArrayList<>();
        for (String t : targets) {
            notes.add(article("Deutsche Bank Research belässt " + t + " auf 'Buy'",
                    "Das Kursziel bleibt unverändert."));
        }
        Article own = article("Deutsche Bank verdoppelt den Gewinn",
                "Das Institut übertrifft die Erwartungen.");
        pour(notes.toArray(Article[]::new));
        pour(own);

        Set<String> db = hits(byName("Deutsche Bank AG"));
        assertTrue(db.contains(own.uuid()), "the genuine DB story stays SUBJECT");
        for (Article note : notes) {
            assertFalse(db.contains(note.uuid()),
                    "an analyst note about another company is not DB news: " + note.title());
        }
        Map<String, TagVerdict> verdicts = tagger.judgments(byName("Deutsche Bank AG"));
        assertEquals(TagVerdict.Role.MENTIONED, verdicts.get(notes.get(0).uuid()).role());

        // And the note IS the target's news: the TUI case.
        Set<String> tui = hits(byName("TUI AG"));
        assertTrue(tui.contains(notes.get(0).uuid()), "the Tui note is a Tui story");
    }

    // ------------------------------------------------------------------ recall the old rule had (and the strict rule killed)

    @Test
    void superMicroMatchesWithoutTheWordComputer() {
        Article smci = article("Super Micro hebt die Prognose an",
                "Der Serverhersteller profitiert vom KI-Boom.");
        pour(smci);
        assertEquals(Set.of(smci.uuid()), hits(byName("Super Micro Computer Inc")));
    }

    @Test
    void appleMatchesOnItsBareName() {
        Article apple = article("Apple erhöht die Prognose", "Der iPhone-Konzern erwartet mehr Umsatz.");
        pour(apple);
        assertEquals(Set.of(apple.uuid()), hits(byName("Apple Inc.")));
    }

    @Test
    void hardKeysAlwaysCarry() {
        Article byIsin = article("Ad-hoc Mitteilung", "Kapitalerhöhung, ISIN US0378331005 betroffen.");
        Article byTag = new Article("tagged", "Chip stocks slide", "Wire", "https://x.example/tagged",
                Instant.now(), List.of("AAPL"), null, null, false);
        pour(byIsin, byTag);
        Set<String> got = hits(new ResolvedInstrument(Isin.parse("US0378331005"),
                Ticker.parse("AAPL"), "Apple Inc."));
        assertTrue(got.contains(byIsin.uuid()));
        assertTrue(got.contains("tagged"));
    }

    @Test
    void learnedAliasSpellingCarriesTheMatch(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path ledger = dir.resolve("aliases.jsonl");
        long now = System.currentTimeMillis() / 1000;
        Files.writeString(ledger,
                "{\"name\":\"telekom\",\"symbol\":\"DTE\",\"at\":" + now + ",\"w\":5,\"n\":5}\n");
        AliasStore aliases = new AliasStore(ledger);
        List<InstrumentEntry> u = loadUniverse();
        try (LuceneArticleTagger withAliases = new LuceneArticleTagger(() -> u, aliases)) {
            Article a = article("Telekom erhöht die Dividende", "Der Bonner Konzern zahlt mehr.");
            withAliases.ingest(List.of(a));
            Set<String> got = withAliases.articlesFor(byTicker("DTE", "Deutsche Telekom AG"));
            assertEquals(Set.of(a.uuid()), got);
        }
    }

    // ------------------------------------------------------------------ edges

    @Test
    void emptyBasinAnswersEmpty() {
        assertTrue(hits(byName("Apple Inc.")).isEmpty());
    }

    @Test
    void instrumentWithoutAnyKeyAnswersEmpty() {
        pour(article("Anything at all"));
        assertTrue(hits(ResolvedInstrument.ofName("")).isEmpty());
    }

    @Test
    void nonLatinTitleNeitherMatchesNorCrashes() {
        pour(article("Газпром повышает дивиденды", "Совет директоров утвердил выплату."));
        assertTrue(hits(byName("Apple Inc.")).isEmpty());
    }

    @Test
    void newlyKnownInstrumentFindsAlreadyPooledArticles() {
        // Universe change: articles first, the instrument becomes interesting
        // later — the first query IS the retro pass, no replay machinery.
        Article old = article("Marvell stellt neuen Chip vor", "Der Halbleiterhersteller wächst.");
        pour(old);
        assertEquals(Set.of(old.uuid()), hits(byName("Marvell Technology Inc")));
    }

    @Test
    void forgottenArticlesLeaveTheAnswers() throws Exception {
        Article a = article("Nvidia zeigt neue KI-Chips");
        pour(a);
        assertEquals(Set.of(a.uuid()), hits(byName("Nvidia Corp")));
        tagger.forget(List.of(a.uuid()));
        assertTrue(hits(byName("Nvidia Corp")).isEmpty());
    }

    @Test
    void concurrentPourAndQueryStaysConsistent() throws Exception {
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int worker = t;
            threads.add(new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    String id = "c" + worker + "-" + i;
                    tagger.ingest(List.of(new Article(id, "Chipmeldung " + id, "Wire",
                            "https://x.example/" + id, Instant.now(), List.of("NVDA"),
                            null, null, false)));
                    hits(byTicker("NVDA", "Nvidia Corp"));
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join(30_000);
        assertEquals(200, hits(byTicker("NVDA", "Nvidia Corp")).size());
    }
}
