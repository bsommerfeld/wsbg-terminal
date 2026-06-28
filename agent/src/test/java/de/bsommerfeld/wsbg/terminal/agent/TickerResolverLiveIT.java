package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.RelatedInstrument;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Locale;

/**
 * Live proof that the relatedTickers second-hop + deeper news surface causal
 * evidence for person/macro subjects. Prints the resolved news and the
 * instruments that news is about, with live moves.
 *
 * <pre>RESOLVER_LIVE=true mvn test -pl agent -Dtest=TickerResolverLiveIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RESOLVER_LIVE", matches = "true")
class TickerResolverLiveIT {

    @Test
    void surfacesRelatedInstrumentsForThemes() {
        TickerResolver resolver = new TickerResolver(new YahooFinanceClient());
        for (String subject : List.of("Trump", "oil", "Nvidia", "Hormuz")) {
            ResolvedSubject r = resolver.resolve(subject);
            System.out.println("\n=== " + subject + " → " + r.canonicalName()
                    + (r.isInstrument() ? " [" + r.ticker() + "]" : " [theme/person]") + " ===");
            for (RawNewsItem n : r.news()) {
                System.out.println("  news: " + n.title()
                        + (n.relatedTickers().isEmpty() ? "" : "  {" + String.join(",", n.relatedTickers()) + "}"));
            }
            for (RelatedInstrument ri : r.related()) {
                MarketSnapshot s = ri.snapshot();
                System.out.printf(Locale.ROOT, "  related: %-10s %+.2f%% today%n",
                        ri.ticker(), s == null ? Double.NaN : s.dayChangePercent());
                for (RawNewsItem rn : ri.news()) {
                    System.out.println("      └ news: " + rn.title());
                }
            }
        }
    }
}
