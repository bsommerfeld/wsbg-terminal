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
        // Ariva forum: the German retail FORUM-SENTIMENT leg — one keyless
        // community RSS firehose with authoritative <isin> tags per post
        // (multi-listing threads tag both share classes; probed 2026-07-16).
        // The same forum backs the finanzen.net/onvista community white-labels,
        // so this one feed covers all three venues. ISIN-addressed ONLY — post
        // titles never name the company, so the name fan stays off. These are
        // user opinions, not articles (publisher says "Ariva-Forum (name)").
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.ariva.ArivaForumRssClient.class);
        // Ariva analysts: the sell-side RATINGS leg (dpa-AFX Analyser) — price
        // targets and up/downgrades for German/European names as a keyless RSS
        // firehose, a genre no other source carries as a feed (probed
        // 2026-07-16). Dual-addressed: exact via the link's utm_content ISIN,
        // name-fallback via the house title-precision filter.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.ariva.ArivaAnalystRssClient.class);
        // wallstreet-online board RSS: German retail FORUM SENTIMENT from the
        // four broad equity boards (hot stocks, Deutsche Aktien im Fokus,
        // Nebenwerte Deutschland, US hot stocks) — name-addressed against
        // THREAD titles (which name the company); the German counterpart to
        // the Ariva forum leg (which is ISIN-addressed). Board slugs are
        // pinned verbatim from the live /rss index: an unknown slug answers
        // 200 with a VALID default-board feed (probed 2026-07-16).
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.wallstreetonline.WsoBoardRssClient.class);
        // Bluesky post search (app.bsky.feed.searchPosts): global social
        // sentiment, keyless via api.bsky.app (the documented public.-host
        // WAF-403s from DE; probed 2026-07-16). SOCIAL posts, not articles:
        // cashtag search per symbol, name search with the house precision
        // filter against the post text; ISIN leg no-op.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.bluesky.BlueskyNewsClient.class);
        // TradingView Minds: per-symbol ticker talk as a sentiment leg
        // (social posts, not articles) — keyless JSON with cursor pagination
        // (probed 2026-07-16), .DE→XETR mapping, US NASDAQ→NYSE fallback
        // with venue memory. Symbol-addressed only.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.tradingview.TradingViewMindsClient.class);
        // Telegram publisher channels via the keyless t.me/s/ web preview:
        // the fast German push wire (finanzen.net, GodmodeTrader, MarketTwits)
        // — name-addressed firehose pool; channels with the preview opted out
        // go session-dead via the probe gate (probed 2026-07-16).
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.telegram.TelegramChannelClient.class);
        // Hacker News (Algolia search, keyless): the tech-salience signal —
        // a paper surfacing on HN means the nerd public noticed; points and
        // comment counts ride in the summary as weight. Name-addressed,
        // last 90 days (probed 2026-07-16).
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.hackernews.HackerNewsClient.class);
        // comdirect Community (Khoros forum): German investor echo as
        // sentiment evidence — service/tax threads name instruments. ONLY the
        // /rss/board endpoints pass the Cloudflare wall (pinned 2026-07-16);
        // name-addressed firehose pool over the four finance boards.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.comdirect.ComdirectCommunityClient.class);
        // Lemmy / Fediverse community echo: !finanzen@feddit.org (German,
        // small but real, ~1-2 posts/day) + !stocks@lemmy.world — a
        // discussion signal, not volume; name-addressed against title AND
        // body (keyless, no wall; probed 2026-07-16).
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.lemmy.LemmyClient.class);
        // 4chan /biz/: raw US retail sentiment (the closest cultural relative
        // to WSB — /smg/ and the ticker generals) via the official read-only
        // JSON API, ONE catalog fetch per 5-min TTL (the 1-req/s API rule is
        // trivially honoured). Unfiltered by design: the source delivers
        // evidence, the model judges (house principle).
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.fourchan.FourChanBizClient.class);
        // PR Newswire UK: the EMEA press-release desk — one keyless all-news
        // RSS firehose (minutes-fresh, probed 2026-07-14), name-addressed via
        // the google-news precision filter; links are direct release URLs the
        // digester reads. No ticker/ISIN tagging, so only newsForName answers.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.prnewswire.PrNewswireUkClient.class);
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
        // NASDAQ outbound RSS: the per-ticker US aggregation leg (Motley Fool,
        // Zacks, MarketBeat … pooled under one symbol query with a dedicated
        // <nasdaq:tickers> element) — keyless, answers a PLAIN client (unlike
        // api.nasdaq.com; probed 2026-07-14). Symbol-addressed, US shapes only.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.nasdaq.NasdaqNewsRssClient.class);
        // onvista articles finder: the multi-year press ARCHIVE leg - dated,
        // attributed history as plain JSON, ISIN-addressed; for small caps
        // and pennystocks the COMPLETE history sits inside the pagination cap
        // (probed 2026-07-16). Answers only the windowed archive fan
        // (newsForNameWindow), never the live-news queries.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.onvista.OnvistaClient.class);
    }
}
