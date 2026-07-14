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
        // The Motley Fool: the US news/analysis leg — ticker-addressed via the
        // news sitemap's <news:stock_tickers> tags (the symbol query Yahoo also
        // answers, but with Fool's editorial angle) plus the foolwatch firehose
        // with teasers; keyless, public feed key (probed 2026-07-13).
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.fool.FoolNewsClient.class);
        // (StockTitan was removed 2026-07-14 — its per-ticker RSS rate-limits
        // so aggressively that the wire's per-unit fan 429-locked the host
        // permanently; user verdict "useless". Recover from git history.)
        // finanznachrichten per-instrument feed: the ISIN-addressed German news
        // leg — the densest per-stock DE aggregate (dpa-AFX/EQS/IT-Times), URL
        // keys on the ISIN alone (dummy slug; probed 2026-07-13, no wall).
        // Answers the aggregator's newsForIsin fan, so it never chases a
        // same-named twin.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.fnnews.FnInstrumentNewsClient.class);
    }
}
