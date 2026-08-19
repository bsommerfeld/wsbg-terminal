package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The article-digest stage: reads a news item's FULL article (via
 * {@link ArticleReader}) and distills it into a few key-fact sentences with one
 * dedicated model call, so the compose model receives substance instead of a bare
 * title — without carrying the article's length (or its HTML leftovers) into the
 * compose prompt. Source-neutral: keyed by {@link Article#link()}, so every
 * {@code NewsSource} (Yahoo, wallstreet-online, future legs) rides the same lane.
 *
 * <p>On-demand, on the compose path: the compose worker digests the few articles
 * its unit's brief will actually render ({@link #digestNow}), synchronously,
 * right before composing — wire work at wire priority. The former background
 * prefetch worker is gone: it warmed articles nobody composed from and its calls
 * competed with the compose workers for the shared model gate, halving the
 * wire's capacity whenever it ran. Results are session-cached per URL (failures
 * too — a paywalled/empty article is not re-fetched), so each article costs its
 * fetch + call exactly once; the brief still reads cache-only via
 * {@link #ifCached}.
 *
 * <p>Opt-out via {@code headlines.read-articles} (read live, like
 * {@code analyze-images}); without a {@link WebFetcher} (tests, lab harness) the
 * digester is inert and briefs simply fall back to the source-provided teaser.
 */
final class NewsDigester {

    private static final Logger LOG = LoggerFactory.getLogger(NewsDigester.class);

    /**
     * Minimum extracted-body length worth a model call. Below this the "article"
     * is a stub (cookie wall, redirect shell) — the title alone carries as much.
     */
    private static final int MIN_ARTICLE_CHARS = 300;

    /**
     * Minimum digest length worth rendering. A real 2–4-sentence fact extract is
     * never this short; below it the model chewed on a shell page (the live run's
     * 2-char digests) and the item falls back to its title.
     */
    private static final int MIN_DIGEST_CHARS = 40;

    /** Digest cache by article link; "" = attempted and failed (not re-tried this session). */
    private final Map<String, String> byLink = new ConcurrentHashMap<>();

    /**
     * Boilerplate net: extracted-body hash → the first link it appeared under.
     * Real articles are never byte-identical across links, so the SAME body under
     * a second link is an interstitial shell (consent wall, error page) that
     * slipped past the length gate — both links are then treated as misses.
     */
    private final Map<Integer, String> bodySeen = new ConcurrentHashMap<>();

    /**
     * Host wall detection: this many consecutive shell pages from one host mark
     * the HOST as consent-walled for the session — every further link there is a
     * fast miss without a network fetch (live 2026-07-14: finance.yahoo.com
     * answered its EU consent shell for EVERY article URL; hundreds of links per
     * hour burned a GET + an INFO line each, yield zero).
     */
    private static final int HOST_WALL_STRIKES = 3;

    /** Host → consecutive shell strikes; a successful digest resets the host. */
    private final Map<String, Integer> shellStrikes = new ConcurrentHashMap<>();

    /** Hosts declared consent-walled for this session (announced once at WARN). */
    private final Map<String, Boolean> walledHosts = new ConcurrentHashMap<>();

    private final AgentBrain brain;
    private final ChatGateway chatGateway;
    private final GlobalConfig config;
    /** Set via optional injection ({@link EditorialAgent#setArticleFetcher}); null = inert. */
    private volatile ArticleReader articleReader;

    NewsDigester(AgentBrain brain, ChatGateway chatGateway, GlobalConfig config) {
        this.brain = brain;
        this.chatGateway = chatGateway;
        this.config = config;
    }

    /** Installs the shared web transport; until then the digester is inert. */
    void setFetcher(WebFetcher fetcher, String userAgent) {
        this.articleReader = fetcher == null ? null : new ArticleReader(fetcher, userAgent);
    }

    /**
     * Lookup-only read for the brief renderer: the digest if this article has
     * already been read + distilled, otherwise empty — never triggers work.
     */
    String ifCached(String link) {
        if (link == null || link.isBlank()) return "";
        return byLink.getOrDefault(link.trim(), "");
    }

    /** Whether this link was already digested this session (success OR cached miss). */
    boolean attempted(String link) {
        return link != null && byLink.containsKey(link.trim());
    }

    /**
     * Synchronous fetch + distill for one article — the compose worker's warm-up
     * call. Every outcome (including failure) is cached, so a link costs its
     * fetch + model call once per session; a repeat call is a cache hit.
     */
    void digestNow(String link) {
        if (link == null || link.isBlank() || articleReader == null || !readArticles()) return;
        digest(link.trim());
    }

    /** Fetch + distill one article; every outcome (including failure) is cached. */
    private void digest(String link) {
        if (byLink.containsKey(link) || !readArticles()) return;
        String host = hostOf(link);
        if (host != null && walledHosts.containsKey(host)) {
            // The whole host is consent-walled this session — fast miss, no GET.
            byLink.put(link, "");
            LOG.debug("[NEWS] skipping consent-walled host {}: {}", host, link);
            return;
        }
        String text = articleReader == null ? ""
                : articleReader.fetchArticleText(link).orElse("");
        if (text.length() < MIN_ARTICLE_CHARS) {
            byLink.put(link, "");
            return;
        }
        // Boilerplate net: an identical body under a DIFFERENT link is a shell page,
        // not an article — miss for this link, and retroactively for the first one
        // (whose digest was shell junk too).
        String firstLink = bodySeen.putIfAbsent(text.hashCode(), link);
        if (firstLink != null && !firstLink.equals(link)) {
            byLink.put(link, "");
            byLink.put(firstLink, "");
            if (host != null) {
                int strikes = shellStrikes.merge(host, 1, Integer::sum);
                if (strikes >= HOST_WALL_STRIKES && walledHosts.putIfAbsent(host, Boolean.TRUE) == null) {
                    LOG.warn("[NEWS] host {} answers a consent/interstitial shell for every "
                            + "article ({} in a row) — article reads there are OFF for this "
                            + "session, items fall back to their titles.", host, strikes);
                    return;
                }
            }
            LOG.debug("[NEWS] shell page detected (identical body as {}) — no article: {}",
                    firstLink, link);
            return;
        }
        // Prose handle, NOT the agent handle: article-extract asks for 2–4 German
        // sentences, and the agent handle carries JSON mode.
        var model = brain.getProseModel();
        if (model == null) return; // brain not ready — leave uncached so a later pass retries
        try {
            String sys = PromptLoader.loadLocalized("article-extract", brain.getUserLanguage().code());
            String reply = chatGateway.chat(model, sys, text);
            String digest = reply == null ? "" : reply.strip();
            // Anything shorter than a real fact extract caches as a miss (title
            // fallback) — this subsumes the prompt's declared "EMPTY" verdict
            // (5 chars, far under MIN_DIGEST_CHARS).
            if (digest.length() < MIN_DIGEST_CHARS) digest = "";
            byLink.put(link, digest);
            if (!digest.isEmpty()) {
                // A real article from this host — the wall strikes were noise.
                if (host != null) shellStrikes.remove(host);
                LOG.info("[NEWS] digested article ({} chars → {} chars): {}",
                        text.length(), digest.length(), link);
            }
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                // Interrupted by teardown, not a real miss — don't cache, don't warn.
                LOG.debug("[NEWS] digest aborted by shutdown: {}", link);
                return;
            }
            LOG.warn("[NEWS] digest failed for {}: {}", link, e.getMessage());
            byLink.put(link, "");
        }
    }

    /**
     * Live read of the {@code headlines.read-articles} opt-out — mirrors the
     * {@code analyze-images} pattern (SettingsBridge mutates the in-memory config,
     * so a toggle takes effect on the next poll without a restart).
     */
    private boolean readArticles() {
        return config.getHeadlines().isReadArticles();
    }

    /**
     * Whether this reader will actually fetch anything.
     *
     * <p>Exposed so a caller can SAY that the switch is off. With it off every
     * text comes back empty, every extraction yields nothing, and a whole
     * reading phase vanishes without one line in the log admitting it.
     */
    boolean readsArticles() {
        return articleReader != null && readArticles();
    }

    /** Lowercased host of a link, or null when it doesn't parse as a URL. */
    private static String hostOf(String link) {
        try {
            String host = java.net.URI.create(link).getHost();
            return host == null ? null : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
