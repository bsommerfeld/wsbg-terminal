package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;

/**
 * The {@code Set<NewsSource>} multibinding (Guice multibindings) so
 * {@code NewsAggregator} can fan a query across every source; adding/dropping a
 * source is a binding change here, never a change in the aggregator. The resolver
 * consults the aggregator (forwarded via EditorialAgent), so the wire triangulates
 * news across providers instead of depending on Yahoo alone.
 */
final class NewsSourceModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder<NewsSource> newsSources =
                Multibinder.newSetBinder(binder(), NewsSource.class);
        newsSources.addBinding().to(YahooFinanceClient.class);
        // wallstreet-online closes the German-stock news GAP: Yahoo carries no
        // XETRA small-cap catalysts (Meta Wolf/CERAM TECH ran +25.8% with the news
        // only on the German venues). Name-addressed — it answers the aggregator's
        // newsForName() fan, not the symbol query.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.wallstreetonline.WsoNewsClient.class);
        // Google News RSS: the German financial PRESS layer (WELT, WiWo, Börse
        // Express, FinanzNachrichten …) — ~100 same-day items per name, keyless
        // (probed 2026-07-13). Name-addressed like WSO; title-relevance
        // filtered so a generic name never floods the pool. Rides the standard
        // browser-first chain — Google captchas bare clients, and a captcha page
        // is a 200 the chain would treat as definitive (user mandate 2026-07-13:
        // JCEF is the standard for Google).
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.googlenews.GoogleNewsClient.class);
    }
}
