package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bsommerfeld.wsbg.terminal.agent.tool.LookupTickerTool;
import de.bsommerfeld.wsbg.terminal.agent.tool.ToolContext;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LookupTickerToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private YahooFinanceClient yahoo;
    private ToolContext ctx;
    private LookupTickerTool tool;

    @BeforeEach
    void setUp() {
        yahoo = mock(YahooFinanceClient.class);
        ctx = new ToolContext(new ClusterRegistry(), new AgentRepository(), null, null,
                new ApplicationEventBus(), null, yahoo);
        tool = new LookupTickerTool();
    }

    private ObjectNode args(String query) {
        ObjectNode o = JSON.createObjectNode();
        o.put("query", query);
        return o;
    }

    @Test
    void recordsEverySymbolReturnedByYahooAsValidated() {
        when(yahoo.searchQuotes("Rheinmetall", 3)).thenReturn(List.of(
                quote("RHM.DE", "Rheinmetall AG", "XETRA"),
                quote("RHM.F", "Rheinmetall AG", "Frankfurt"),
                quote("RHMD.XC", "Rheinmetall AG", "CXE")));

        String result = tool.execute(args("Rheinmetall"), ctx);

        assertTrue(result.contains("RHM.DE"));
        assertTrue(result.contains("Rheinmetall AG"));
        // All three full forms validated.
        assertTrue(ctx.isTickerValidated("RHM.DE"));
        assertTrue(ctx.isTickerValidated("RHM.F"));
        assertTrue(ctx.isTickerValidated("RHMD.XC"));
        // Base form (before the dot) also accepted — headlines often
        // strip the exchange suffix.
        assertTrue(ctx.isTickerValidated("RHM"));
        assertTrue(ctx.isTickerValidated("RHMD"));
    }

    @Test
    void caseInsensitiveLookupAgainstValidatedSet() {
        when(yahoo.searchQuotes("nvidia", 3)).thenReturn(List.of(
                quote("NVDA", "NVIDIA Corporation", "NASDAQ")));

        tool.execute(args("nvidia"), ctx);

        assertTrue(ctx.isTickerValidated("NVDA"));
        assertTrue(ctx.isTickerValidated("nvda"));
        assertTrue(ctx.isTickerValidated(" Nvda "));
    }

    @Test
    void zeroMatchesReturnsNoMatchHint() {
        when(yahoo.searchQuotes(anyString(), anyInt())).thenReturn(List.of());

        String result = tool.execute(args("blubfoo"), ctx);

        assertTrue(result.startsWith("No Yahoo Finance match"));
        assertFalse(ctx.isTickerValidated("BLUBFOO"));
    }

    @Test
    void rejectsEmptyQuery() {
        String result = tool.execute(args("  "), ctx);
        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(yahoo);
    }

    @Test
    void weakNameMatchesAreFlaggedAndNotValidated() {
        // The Rheiner→RMO failure from the 2026-05-28 smoke test: Yahoo
        // fuzzy-matches WSBG slang against unrelated firms whose names
        // share letters but not tokens. None of these should be validated.
        when(yahoo.searchQuotes("Rheiner", 3)).thenReturn(List.of(
                quote("E87.DU", "RheinErden AG", "Düsseldorf"),
                quote("RMO", "Romeo Power Inc.", "NYSE")));

        String result = tool.execute(args("Rheiner"), ctx);

        assertTrue(result.contains("WEAK NAME MATCH"));
        assertTrue(result.contains("None of the returned names confidently matches"));
        assertFalse(ctx.isTickerValidated("E87.DU"));
        assertFalse(ctx.isTickerValidated("RMO"));
    }

    @Test
    void singleTokenQueryStrictModeFiltersExtraBrandWords() {
        when(yahoo.searchQuotes("Apple", 3)).thenReturn(List.of(
                quote("AAPL", "Apple Inc.", "NASDAQ"),
                quote("APLE", "Apple Hospitality REIT, Inc.", "NYSE"),
                quote("2788.T", "APPLE INTERNATIONAL CO LTD", "Tokyo")));

        String result = tool.execute(args("Apple"), ctx);

        // AAPL „Apple Inc." → tokens {apple} (Inc stopword filtered) ==
        // query {apple} → passes strict 1-token rule.
        assertTrue(ctx.isTickerValidated("AAPL"));
        // APLE „Apple Hospitality REIT" introduces extra brand words →
        // strict mode flags as weak.
        assertFalse(ctx.isTickerValidated("APLE"));
        // 2788.T „APPLE INTERNATIONAL CO LTD" — adds „international",
        // a real content token. Different company sharing the brand,
        // strict mode filters it.
        assertFalse(ctx.isTickerValidated("2788.T"));
        assertTrue(result.contains("WEAK NAME MATCH"));
    }

    @Test
    void singleTokenQueryAcceptsExactExpansion() {
        // „Snowflake" → „Snowflake Inc." is the canonical happy path —
        // stop-word „Inc" filters away, the token sets are equal, the
        // 1-token strict mode lets it through.
        when(yahoo.searchQuotes("Snowflake", 3)).thenReturn(List.of(
                quote("SNOW", "Snowflake Inc.", "NYSE")));

        tool.execute(args("Snowflake"), ctx);

        assertTrue(ctx.isTickerValidated("SNOW"));
    }

    @Test
    void singleTokenQueryRejectsRheinerLikeFuzzyMatch() {
        // The 2026-05-28 smoke-test bug: „Rheiner" (WSBG slang) plus a
        // tiny asset-management firm sharing the token slip through
        // plain Jaccard. Strict 1-token mode catches it — „Management"
        // and „RM" are extras the brand „Rheiner" doesn't carry, so
        // RMO.DU is left as weak.
        when(yahoo.searchQuotes("Rheiner", 3)).thenReturn(List.of(
                quote("E87.DU", "RheinErden AG", "Düsseldorf"),
                quote("RMO.DU", "RM Rheiner Management AG", "Düsseldorf")));

        String result = tool.execute(args("Rheiner"), ctx);

        assertFalse(ctx.isTickerValidated("E87.DU"));
        assertFalse(ctx.isTickerValidated("RMO.DU"));
        assertTrue(result.contains("None of the returned names confidently matches"));
    }

    @Test
    void companySuffixesDoNotInflateOrPenaliseTheMatch() {
        // „Outlook Therapeutics" should strongly match „Outlook
        // Therapeutics, Inc." — the „Inc" stop-token must not throw the
        // similarity off, and the real shared tokens carry the match.
        when(yahoo.searchQuotes("Outlook Therapeutics", 3)).thenReturn(List.of(
                quote("OTLK", "Outlook Therapeutics, Inc.", "NASDAQ")));

        tool.execute(args("Outlook Therapeutics"), ctx);

        assertTrue(ctx.isTickerValidated("OTLK"));
    }

    private static YahooQuote quote(String symbol, String name, String exchange) {
        return new YahooQuote(symbol, name, name, "EQUITY", exchange, exchange,
                "Industrials", "Aerospace & Defense", Double.NaN, Double.NaN);
    }
}
