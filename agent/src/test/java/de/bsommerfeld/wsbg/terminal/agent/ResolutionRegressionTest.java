package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.instruments.AliasStore;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.yahoofinance.YahooMarketClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.yahoofinance.YahooMarketClient.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The 2026-08-13 resolution regressions, pinned:
 * <ul>
 *   <li><b>A1</b> — the learned memory never writes itself: a claim the
 *       {@link AliasMemoryMatcher} answered from the ledger is not booked back into
 *       the ledger (the 'Vontobel' self-reinforcement loop).</li>
 *   <li><b>A4</b> — the extraction name gate: OCR salad, pure digits and
 *       over-long transcriptions never become subject names.</li>
 *   <li><b>B</b> — the speakable-name predicate guarding the repair stage.</li>
 *   <li><b>E1</b> — the parser survives the schema-less runners' loose JSON
 *       (bare [N2] ordinals, stray parens, string ordinals).</li>
 * </ul>
 */
class ResolutionRegressionTest {

    @TempDir
    Path dir;

    // ---- A1: the memory never writes itself ----

    private AliasStore trustedStore(String name, String symbol, int postings) throws Exception {
        Path f = dir.resolve("aliases.jsonl");
        long base = System.currentTimeMillis() / 1000 - (long) postings * 3600;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < postings; i++) {
            lines.add("{\"name\":\"" + name + "\",\"symbol\":\"" + symbol
                    + "\",\"at\":" + (base + i * 3601L) + ",\"tier\":\"DeskMatcher\"}");
        }
        Files.write(f, lines);
        return new AliasStore(f);
    }

    @Test
    void aMemoryReplayIsNotBookedBackIntoTheMemory() throws Exception {
        // A clean venue symbol — the historical case was the WKN 675054, whose bare
        // numeric shape the store's hygiene now mutes on load (a separate lesson).
        AliasStore store = trustedStore("vontobel", "VONN.SW", 5);
        int versionBefore = store.version();
        double strengthBefore = store.candidatesFor("vontobel").get(0).strength();

        YahooMarketClient yahoo = mock(YahooMarketClient.class);
        when(yahoo.search(anyString(), anyInt(), anyInt())).thenReturn(SearchResult.empty());
        when(yahoo.fetchCharts(org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.Map.of());
        TickerResolver resolver = new TickerResolver(yahoo);
        resolver.setAliasStore(store);

        TickerResolver.ResolvedSubject rs = resolver.resolve("Vontobel", 0);

        assertEquals("VONN.SW", rs.ticker(), "the memory still answers");
        assertEquals(versionBefore, store.version(),
                "but the replay books NOTHING — the loop that re-earned a wrong "
                        + "verdict on every mention is closed");
        assertEquals(strengthBefore, store.candidatesFor("vontobel").get(0).strength(), 0.01,
                "strength only decays now unless a stage that investigates re-earns it");
    }

    @Test
    void anInvestigatedClaimStillBooksItsPosting() throws Exception {
        AliasStore store = new AliasStore(dir.resolve("fresh-aliases.jsonl"));
        YahooMarketClient yahoo = mock(YahooMarketClient.class);
        when(yahoo.search(anyString(), anyInt(), anyInt())).thenReturn(SearchResult.empty());
        when(yahoo.fetchCharts(org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.Map.of());
        TickerResolver resolver = new TickerResolver(yahoo);
        resolver.setAliasStore(store);

        // "Gold" is claimed by the curated CommodityMatcher — an investigating stage.
        TickerResolver.ResolvedSubject rs = resolver.resolve("Gold", 0);

        assertEquals("GC=F", rs.ticker());
        assertFalse(store.candidatesFor("gold").isEmpty(),
                "a catalogue claim is still learned — only the memory's own echo is not");
    }

    // ---- A4: the extraction name gate ----

    @Test
    void ocrSaladAndIdentifierShapesAreNoSubjectNames() {
        assertEquals("", SubjectExtractor.cleanSubjectName("Wealth Cash A} Brokerage"),
                "OCR artefact with brace salad is dropped, not cleaned");
        assertEquals("", SubjectExtractor.cleanSubjectName("675054"), "pure digits are no name");
        assertEquals("", SubjectExtractor.cleanSubjectName("X"), "one character is no name");
        assertEquals("", SubjectExtractor.cleanSubjectName(
                "Der gesamte transkribierte Absatz aus dem Screenshot der hier niemals ein "
                        + "Subjektname sein kann weil er ein ganzer Satz ist und mehr"),
                "a transcribed sentence is no name");
    }

    @Test
    void realNamesSurviveTheGate() {
        assertEquals("S&P 500", SubjectExtractor.cleanSubjectName("S&P 500"));
        assertEquals("3M", SubjectExtractor.cleanSubjectName("3M"));
        assertEquals("Krispy Kreme, Inc.", SubjectExtractor.cleanSubjectName("Krispy Kreme, Inc."));
        assertEquals("Münchener Rück", SubjectExtractor.cleanSubjectName("Münchener  Rück"),
                "whitespace collapses, umlauts survive");
        assertEquals("BYD", SubjectExtractor.cleanSubjectName("BYD"));
    }

    // ---- B: the speakable-name predicate ----

    @Test
    void speakableNamesAreOnesASentenceCanCarry() {
        assertTrue(EditorialAgent.speakableName("Rheinmetall AG"));
        assertTrue(EditorialAgent.speakableName("Krispy Kreme, Inc."));
        assertTrue(EditorialAgent.speakableName("SK hynix"));
        assertTrue(EditorialAgent.speakableName("SAP"), "single-token acronym brands pass");
        assertTrue(EditorialAgent.speakableName("Winter USD"),
                "borderline — cased word present; the unit itself is prevented upstream");
    }

    @Test
    void unspeakableNamesNeverGetForcedIntoTheLine() {
        assertFalse(EditorialAgent.speakableName("WTI-QU.COM. DLA"),
                "venue abbreviation shouting — the live repair-breaker");
        assertFalse(EditorialAgent.speakableName("675054"), "a WKN is no sentence subject");
        assertFalse(EditorialAgent.speakableName("0O8X.IL"));
        assertFalse(EditorialAgent.speakableName("WINTER-USD"));
        assertFalse(EditorialAgent.speakableName("BE"), "two letters are no name");
        assertFalse(EditorialAgent.speakableName(""));
        assertFalse(EditorialAgent.speakableName(null));
    }

    // ---- E1: loose-JSON hardening (the schema-less MLX runner shapes) ----

    @Test
    void bareOrdinalTokensParseAfterSanitizing() {
        String reply = "{\"headline\":\"Die Affen kaufen Nvidia, weil die Zahlen stimmen\","
                + "\"sentiment\":\"BULLISH\",\"highlight\":\"NORMAL\",\"trigger\":\"NONE\","
                + "\"derivedFrom\":[],\"newsUsed\":[N2]}";
        ComposeReplyParser.ParsedCompose pc = ComposeReplyParser.parse(reply, false);
        assertTrue(pc.draft() != null, "the bare [N2] no longer costs the whole reply");
        assertEquals(List.of(2), pc.newsUsed());
    }

    @Test
    void strayParensAndStringOrdinalsAreRecovered() {
        String reply = "{\"headline\":\"Zeile\",\"sentiment\":\"NEUTRAL\",\"highlight\":\"NORMAL\","
                + "\"trigger\":\"NONE\",\"derivedFrom\":[],\"newsUsed\":[\"[N2]\")]}";
        ComposeReplyParser.ParsedCompose pc = ComposeReplyParser.parse(reply, false);
        assertTrue(pc.draft() != null);
        assertEquals(List.of(2), pc.newsUsed(), "the string ordinal still counts as a citation");
    }

    @Test
    void quotedTextIsNeverRewrittenBySanitizing() {
        String reply = "{\"headline\":\"N2 und (Klammern) bleiben im Satz erhalten\","
                + "\"sentiment\":\"NEUTRAL\",\"highlight\":\"NORMAL\",\"trigger\":\"NONE\","
                + "\"derivedFrom\":[],\"newsUsed\":[N1]}";
        ComposeReplyParser.ParsedCompose pc = ComposeReplyParser.parse(reply, false);
        assertEquals("N2 und (Klammern) bleiben im Satz erhalten", pc.draft().headline());
        assertEquals(List.of(1), pc.newsUsed());
    }
}
