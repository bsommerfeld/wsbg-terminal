package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The article-digest pre-stage: reads a news item's FULL article (via
 * {@link ArticleReader}) and distills it into a few key-fact sentences with one
 * dedicated gemma4 call, so the compose model receives substance instead of a bare
 * title — without carrying the article's length (or its HTML leftovers) into the
 * compose prompt. Source-neutral: keyed by {@link RawNewsItem#link()}, so every
 * {@code NewsSource} (Yahoo, wallstreet-online, future legs) rides the same lane.
 *
 * <p>Follows the proven vision pattern exactly: a per-URL session cache (failures
 * cached too — a paywalled/empty article is not re-fetched), a single background
 * worker that warms the cache asynchronously, and a lookup-only read
 * ({@link #ifCached}) on the compose path — composing never blocks on a cold
 * article. The model call funnels through the shared {@link ChatGateway}
 * (semaphore-gated, NUM_PARALLEL=2), so digesting can never over-subscribe Ollama.
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
     * Single background worker, mirroring the vision pool's sizing rationale: the
     * digest shares the one gemma4 model with extraction + compose, so one worker
     * leaves the latency-sensitive editorial calls their slots — digesting is
     * cache-warming, serialising it is the right trade.
     */
    private final ExecutorService digestExecutor = Executors.newFixedThreadPool(1, r -> {
        Thread t = new Thread(r, "news-digest");
        t.setDaemon(true);
        return t;
    });

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
     * Queues every not-yet-digested item for background fetch + digest.
     * Fire-and-forget: the compose brief reads cache-only ({@link #ifCached}), so a
     * cold article simply isn't reflected this compose and enriches the next one.
     */
    void prefetch(Collection<RawNewsItem> items) {
        if (items == null || items.isEmpty() || articleReader == null || !readArticles()) return;
        for (RawNewsItem n : items) {
            if (n == null || n.link() == null || n.link().isBlank()) continue;
            String link = n.link().trim();
            if (byLink.containsKey(link)) continue;
            digestExecutor.submit(() -> digest(link));
        }
    }

    /**
     * Lookup-only read for the brief renderer: the digest if this article has
     * already been read + distilled, otherwise empty — never triggers work.
     */
    String ifCached(String link) {
        if (link == null || link.isBlank()) return "";
        return byLink.getOrDefault(link.trim(), "");
    }

    /** Fetch + distill one article; every outcome (including failure) is cached. */
    private void digest(String link) {
        if (byLink.containsKey(link) || !readArticles()) return;
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
            LOG.info("[NEWS] shell page detected (identical body as {}) — no article: {}", firstLink, link);
            return;
        }
        var model = brain.getAgentModel();
        if (model == null) return; // brain not ready — leave uncached so a later pass retries
        try {
            String sys = PromptLoader.loadLocalized("article-extract", brain.getUserLanguage().code());
            String reply = chatGateway.chat(model, sys, text);
            String digest = reply == null ? "" : reply.strip();
            // The prompt's declared "nothing here" verdict — and any fragment too
            // short to be a real fact extract — caches as a miss (title fallback).
            if (digest.equalsIgnoreCase("EMPTY") || digest.length() < MIN_DIGEST_CHARS) digest = "";
            byLink.put(link, digest);
            if (!digest.isEmpty()) {
                LOG.info("[NEWS] digested article ({} chars → {} chars): {}",
                        text.length(), digest.length(), link);
            }
        } catch (Exception e) {
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
}
