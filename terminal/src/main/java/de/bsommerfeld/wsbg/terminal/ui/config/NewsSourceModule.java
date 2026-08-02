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
        // CNBC: the US financial PRESS leg - keyless on every surface (probed
        // 2026-08-02). Two legs: twelve SECTION RSS feeds as the live pool
        // (the ~24 show franchises answer 200 with an empty channel and are
        // deliberately not wired), plus queryly full-text search as the
        // instrument door AND the multi-year ARCHIVE (15.795 hits for one
        // name, pages back to 2016 - so newsForNameWindow answers too).
        // Name-addressed only: CNBC tags neither tickers nor ISINs, and a bare
        // symbol in a full-text query would drag in every passing mention.
        // CNBC Pro pieces answer 200 with an EMPTY body, so they are marked
        // pre-fetch via cn:contentClassification and published as "CNBC Pro" -
        // the headline survives, a body reader skips them.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.cnbc.CnbcNewsClient.class);
        // WELT: the German PRESS leg with a real full-text search API
        // (welt.de/api/search) reaching back to 2007 - the deepest press
        // archive of the 2026-08-02 wave. Two gotchas live in the client:
        // Akamai fingerprints HEADER COMPLETENESS (the sec-fetch trio AND
        // Accept-Encoding are both load-bearing; a JDK client omits the
        // latter by default and earns a 403), and the search is padded with
        // same-day filler - a nonsense term answers 200 with 200 unrelated
        // hits, so the TITLE-precision cut is what makes the source usable at
        // all. WELT Plus pieces are marked pre-fetch (feed flag AND /plus URL).
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.welt.WeltNewsClient.class);
        // Handelsblatt + WirtschaftsWoche: one client, two brands on the same
        // keyless JSON API (content.www.*, which answers without any headers
        // at all). The precise instrument door is the TOPIC page machinery -
        // 1411 HB / 937 WiWo editorially maintained entity slugs beat any
        // full-text guess - and articles carry hand-tagged ISIN links.
        // HOUSE RULE, deliberate: METERED counts as walled. The metering API
        // would hand over the full body, but that is client-side metering we
        // do not circumvent; headline, lead and ISINs only. Do not "fix" it.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.handelsblatt.HandelsblattNewsClient.class);
        // Kapitalmarktexperten: an open wp-json with 19.460 full texts, an
        // ISIN as a TOP-LEVEL field per post (so the ISIN fan answers exactly,
        // not fuzzily) and date windows server-side - a genuine archive leg
        // for the German small-cap long tail. BUT the house generates ~60
        // machine-written pieces a day from the very wires we tap directly,
        // so every item ships under the publisher "Kapitalmarktexperten
        // (KI-generiert)": it may inform a dossier, it must never pass as a
        // first source. The archive fan is the main road, the name fan the
        // side door.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.kapitalmarktexperten.KapitalmarktexpertenClient.class);
        // boerse.de: the cleanest source of the 2026-08-02 wave - robots
        // "Allow: /", no wall, and no terms clause against automated access at
        // all. ISIN-addressed with the slug ignored (/aktien/x/<ISIN>), which
        // makes it the one German venue that needs no resolver. Beyond news it
        // carries three things nobody else gives away: directors' dealings
        // with real names and volumes, a ~340-entry analyst recommendation
        // archive, and eight fiscal years of P&L. Pagination gotcha: the
        // /x/<ISIN> form silently 301s any _seite,N back to page 1, so the
        // client follows rel="next" instead of building page URLs.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.boersede.BoerseDeNewsClient.class);
        // Börse Online + Der Aktionär: one client, because it is one CMS -
        // both quote endpoints answer byte-identically and the article IDs
        // share a global space (the same dpa-AFX piece is …-542040.html under
        // both roofs), so dedupe runs on ID, not URL. Four ISIN-addressed
        // legs; Der Aktionär reaches back to 2003 via its 131 sitemaps.
        // Pagination gotcha: the lists never run empty, they CLAMP - page
        // 5000 returns page 50's items forever, so the walk also breaks on a
        // repeated first article ID.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.boersenmedien.BoersenmedienNewsClient.class);
        // Traders Union: taken in under the fishing-net rule - the source is
        // technically viable (open robots, full text, structured tickers, a
        // keyless movers API), so the sorting happens at the gate, not at the
        // net. Its own path segments do the sorting: /news/stocks/ and
        // /news/companies/ are 94% price-tick-generated, /news/central-banks/
        // and /news/editors-picks/ are 0%. Tick pieces are MARKED
        // ("Traders Union (Kursbewegung)"), never dropped - they still carry
        // GAAP figures and buyback volumes, and the model decides. Paid
        // broker PR rides the sponsored flag. German copy is machine-
        // translated from English; URLs come only from sitemaps, never built.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.tradersunion.TradersUnionNewsClient.class);
        // finanzen.net: the dpa-AFX ANALYSER leg (a global feed of every
        // single analyst action plus the full study text with house, old→new
        // target and reasoning) and per-instrument RSS. Behind it sits the
        // best keyless instrument resolver of the wave - one call maps
        // name/ISIN/WKN/ticker in every direction and yields the slug every
        // sub-page is built from, small caps included.
        // Akamai gotcha: the wall falls to HEADER COMPLETENESS, not to a
        // browser - but the JDK client must ask for identity encoding, or it
        // gets a 200 full of compressed noise and every parser reads empty.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetNewsClient.class);
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
        // Stocktwits: the one social venue with a MACHINE-READABLE mood label
        // (users tag their posts Bullish/Bearish). Keyless but Cloudflare-
        // walled — rides the standard BROWSER-FIRST chain (the hidden CEF tab
        // solves the challenge during warmup; probed 2026-07-16). Symbol-only,
        // US shapes only (an exchange suffix is never cut).
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.stocktwits.StocktwitsClient.class);
        // Yahoo Conversations (OpenWeb): the per-ticker comment board — one
        // stable board per instrument incl. the German venue listings
        // (SAP.DE/RHM.DE), covering what Stocktwits' US-only gate skips. Two
        // steps: board id from the quote page (browser-first), conversation
        // via the @OpenWebConversations handshake fetcher (page-context POSTs
        // in a hidden tab; plain HTTP 403s — probed 2026-07-16). Shape caveat:
        // the conversation JSON re-pins against the first live answer.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.yahooconversations.YahooConversationsClient.class);
        // sharedeals.de: German retail OPINION - daily chart analyses and
        // "Kurspotenzial" pieces from a promoter-adjacent community venue,
        // via the site's own open wp-json (keyless, no wall, full-text
        // search + exact date windows; probed 2026-07-17). Coverage cadence
        // IS the signal; NOT a facts desk, so it rides the sentiment fan.
        // Name-addressed with the house TITLE-precision cut (the server
        // search matches full text and over-returns passing mentions).
        // Doubles as a multi-year ARCHIVE leg: 18.9k posts back to 2010 -
        // deep German small-cap opinion history (newsForNameWindow).
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.sharedeals.SharedealsClient.class);
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
        // Reuters: the flagship global newswire via its Arc news-sitemap —
        // the ONE keyless door left (classic RSS is dead, articles are
        // bot-walled; probed 2026-07-16). Name-addressed firehose pool over
        // the freshest ~50 headlines; no teaser, the headline is the value.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.reuters.ReutersNewsClient.class);
        // Bloomberg: the flagship US newswire via the keyless vertical RSS
        // feeds (markets/economics/technology/politics/wealth; probed
        // 2026-07-16). Name-addressed against title AND teaser — headlines
        // abbreviate ("Citi"), the teaser prints the legal name ("Citigroup").
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.bloomberg.BloombergNewsClient.class);
        // Benzinga newsdesk (/news + /markets category feeds + the
        // "Why Is It Moving?" topic feed with its small-cap mover
        // explanations, NOT the evergreen /feed money-blog; probed
        // 2026-07-16): the fast US retail-facing wire with MACHINE-READABLE
        // ticker tags in the article HTML (data-ticker + /quote anchors) —
        // so it answers the symbol fan by exact tag match besides the name
        // fan.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.benzinga.BenzingaNewsClient.class);
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
        // EQS disclosure ARCHIVE (wp-json, keyless, ISIN-filtered): the DGAP
        // legacy back beyond 2018 even for pennystocks (probed 2026-07-16:
        // Mutares = 654 records). Archive fan only.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.briefing.EqsNewsArchiveClient.class);
        // GDELT DOC 2.0: keyless world-press full text from 2017 on - the
        // breadth source of the multi-year history. HARD rate gate inside
        // (8s global, bursts earn multi-minute IP blocks). Archive fan only.
        newsSources.addBinding().to(
                de.bsommerfeld.wsbg.terminal.websearch.GdeltDocClient.class);
    }
}
