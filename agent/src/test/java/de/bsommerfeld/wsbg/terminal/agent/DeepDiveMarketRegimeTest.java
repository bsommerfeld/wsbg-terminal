package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.briefing.BundYieldClient;
import de.bsommerfeld.wsbg.terminal.briefing.CboePutCallClient;
import de.bsommerfeld.wsbg.terminal.briefing.CryptoDerivsClient;
import de.bsommerfeld.wsbg.terminal.briefing.PolymarketClient;
import de.bsommerfeld.wsbg.terminal.feargreed.CryptoFearGreedIndex;
import de.bsommerfeld.wsbg.terminal.feargreed.FearGreedIndex;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The market regime on the KI-DD's situation shelf (wired 2026-08-03). Every
 * gauge here fed the evening report and nothing else, which left the DD unable
 * to say whether a move happened in a panicking market or a calm one, at a 2%
 * risk-free rate or a 4% one.
 *
 * <p>The contract this test defends is the framing, not the formatting. These
 * are CONDITIONS, not statements about the subject - the heading has to say so,
 * every reading has to carry what it measures rather than what it means, and a
 * partial answer must still produce the block.
 */
class DeepDiveMarketRegimeTest {

    private static DeepDiveService.Material bare() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Rheinmetall AG";
        m.ticker = "RHM.DE";
        m.isin = "DE0007030009";
        return m;
    }

    private static String situation(DeepDiveService.Material m) {
        return DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];
    }

    @Test
    void everyGaugeReachesTheShelfWithWhatItMeasures() {
        DeepDiveService.Material m = bare();
        m.fearGreed = new FearGreedIndex(27.4, "Fear", 31.0, 44.0, 58.0, 51.0,
                Instant.parse("2026-08-03T06:00:00Z"), List.of(), List.of());
        m.cryptoFearGreed = new CryptoFearGreedIndex(62.0, "Greed", 58.0,
                Instant.parse("2026-08-03T06:00:00Z"), List.of());
        m.putCall = new CboePutCallClient.PutCallRatios("2026-08-01", 1.14, 0.68, 1.42,
                18.9, 5_420.0);
        m.bundYield = new BundYieldClient.YieldPoint("2026-08-01", 2.61, 2.55);
        m.eurUsdRate = 1.0842;
        m.dollarIndex = 98.31;
        m.cryptoDerivs = new CryptoDerivsClient.DerivsSnapshot(0.012, 512_340.0, 48.2);
        m.predictionMarkets = List.of(new PolymarketClient.PredictionMarket(
                "Fed cuts in September?", "Yes", 71.5, 2_400_000.0));

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("regime"));
        String shelf = situation(m);

        // The framing IS the safeguard - conditions, not a verdict on the name.
        assertContains(shelf, "THE TAPE THIS READING WAS TAKEN IN (verified");
        assertContains(shelf, "NOT statements about this subject");
        assertContains(shelf, "[" + nums.get("regime") + "]");

        // Every reading says what its scale means, so no number stands naked.
        assertContains(shelf, "0 = extreme fear, 100 = extreme greed): 27.40 (Fear)");
        assertContains(shelf, "a week ago 44.00, a month ago 58.00");
        assertContains(shelf, "crypto sentiment (alternative.me, same scale): 62.00 (Greed)");
        assertContains(shelf, "put/call total 1.14, equity 0.68, index 1.42");
        assertContains(shelf, "above 1 means more puts than calls traded");
        assertContains(shelf, "VIX 18.90");
        assertContains(shelf, "10-year Bund 2.61% (2026-08-01), previously 2.55%");
        assertContains(shelf, "EUR/USD 1.08, dollar index 98.31");
        assertContains(shelf, "every cross-border figure in this report passes through");
        assertContains(shelf, "funding rate +0.01%, open interest 512 340 BTC");
        assertContains(shelf, "what the crowd PRICES (prediction market");
        assertContains(shelf, "\"Fed cuts in September?\" - Yes at 71.50%");

        assertContains(DeepDiveService.sourcesSection(m, false),
                "[" + nums.get("regime") + "] Market regime");
    }

    /** Three of seven gauges is still a regime reading - the block is not all-or-nothing. */
    @Test
    void aPartialAnswerStillProducesTheBlock() {
        DeepDiveService.Material m = bare();
        m.bundYield = new BundYieldClient.YieldPoint("2026-08-01", 2.61, null);
        String shelf = situation(m);
        assertContains(shelf, "THE TAPE THIS READING WAS TAKEN IN");
        assertContains(shelf, "10-year Bund 2.61%");
        assertFalse(shelf.contains("previously"), shelf);
        assertFalse(shelf.contains("Fear & Greed"), shelf);
        assertFalse(shelf.contains("put/call"), shelf);
    }

    /** The prediction-market cap holds - this is context, not a betting board. */
    @Test
    void thePredictionMarketCapHolds() {
        DeepDiveService.Material m = bare();
        List<PolymarketClient.PredictionMarket> many = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            many.add(new PolymarketClient.PredictionMarket("Question " + i, "Yes",
                    50.0 + i, 1_000_000.0));
        }
        m.predictionMarkets = List.copyOf(many);
        String shelf = situation(m);
        int rows = 0;
        for (String line : shelf.split("\n")) {
            if (line.contains("what the crowd PRICES")) rows++;
        }
        assertTrue(rows == 3, "expected 3 prediction rows, got " + rows + " in:\n" + shelf);
    }

    @Test
    void withoutAnyGaugeThereIsNoBlock() {
        DeepDiveService.Material m = bare();
        assertFalse(DeepDiveService.sourceNumbers(m).containsKey("regime"));
        String shelf = situation(m);
        assertTrue(shelf == null || !shelf.contains("THE TAPE THIS READING WAS TAKEN IN"),
                String.valueOf(shelf));
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack != null && haystack.contains(needle),
                "expected to find:\n  " + needle + "\nin:\n" + haystack);
    }
}
