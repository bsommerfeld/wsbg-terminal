package de.bsommerfeld.wsbg.terminal.finanzennet;

import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.EstimateRow;
import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.PriceTarget;
import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.VenueQuote;
import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetResolver.InstrumentMatch;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live proof that the Akamai header set still opens finanzen.net and that the
 * server-rendered shapes still parse. The one thing fixtures cannot verify: the
 * wall is a moving target, and the day the header fingerprint stops being
 * accepted this is what says so.
 *
 * <pre>FN_SMOKE=true mvn test -pl finanzen-net -Dtest=FinanzenNetLiveIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "FN_SMOKE", matches = "true")
class FinanzenNetLiveIT {

    @Test
    void resolvesEveryDirectionAndReadsTheInstrumentPages() {
        FinanzenNetResolver resolver = new FinanzenNetResolver();
        FinanzenNetMarketClient market = new FinanzenNetMarketClient();

        for (String query : List.of("SAP", "DE0007164600", "716460", "artec technologies")) {
            List<InstrumentMatch> hits = resolver.resolve(query);
            assertFalse(hits.isEmpty(), "the wall answered nothing for " + query);
            InstrumentMatch m = hits.get(0);
            System.out.printf(Locale.ROOT, "%-22s → %s  wkn=%s isin=%s tickers=%s%n",
                    query, m.slug(), m.wkn(), m.isin(), m.tickers());
        }

        String slug = resolver.slugFor("SAP").orElseThrow();
        List<VenueQuote> venues = market.venueQuotes(slug);
        assertFalse(venues.isEmpty(), "the venue table is the EUR price leg");
        venues.forEach(q -> System.out.printf(Locale.ROOT, "  %-14s %-22s %s %s  vol=%s%n",
                q.segment(), q.venue(), q.last(), q.currency(), q.volume()));
        assertTrue(venues.stream().anyMatch(q -> q.venue().contains("Lang und Schwarz")));

        List<PriceTarget> targets = market.priceTargets(slug);
        targets.forEach(t -> System.out.println("  target " + t));
        assertFalse(targets.isEmpty());

        List<EstimateRow> estimates = market.estimates(slug);
        estimates.forEach(e -> System.out.println("  estimate " + e));
        assertFalse(estimates.isEmpty());

        market.upcomingDates(slug).forEach(d -> System.out.println("  date " + d));
        market.financials(slug).forEach(f ->
                System.out.println("  statement " + f.title() + " " + f.periods()));
    }

    @Test
    void readsTheWireAndOneAnalyserStudyInFull() {
        FinanzenNetNewsClient news = new FinanzenNetNewsClient();

        List<RawNewsItem> calls = news.analystCalls(10);
        assertFalse(calls.isEmpty(), "the dpa-AFX rating tape is silent");
        calls.forEach(c -> System.out.println("  call " + c.publishedAt() + "  " + c.title()));

        assertFalse(news.latest(10).isEmpty());
        assertFalse(news.newsFor("SAP", 5).isEmpty());
        assertFalse(news.newsForIsin("DE0007164600", 5).isEmpty());

        news.analysis(calls.get(0).link())
                .ifPresent(study -> System.out.println("  study " + study));
    }
}
