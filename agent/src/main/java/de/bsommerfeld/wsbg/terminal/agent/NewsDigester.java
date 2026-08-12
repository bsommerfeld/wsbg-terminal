package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.util.BackgroundThreads;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The article-digest pre-stage: reads a news item's FULL article (via
 * {@link ArticleReader}) and distills it into a few key-fact sentences with one
 * dedicated gemma4 call, so the compose model receives substance instead of a bare
 * title — without carrying the article's length (or its HTML leftovers) into the
 * compose prompt. Source-neutral: keyed by {@link Article#link()}, so every
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
     * Raw-article-text cache for full-text reads (link → readable text;
     * "" = attempted and failed). Separate from the digest cache: a full-text
     * caller takes the WHOLE article, the wire keeps its compact digest lane.
     */
    private final Map<String, String> textByLink = new ConcurrentHashMap<>();

    /** The full-article cap - chunked downstream, never summarized. */
    static final int FULL_ARTICLE_MAX_CHARS = 24_000;

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

    /**
     * How deep the digest backlog may grow. Digesting is cache-warming: an item
     * that has waited behind two hundred others is long past the compose it was
     * meant to enrich, so the OLDEST waiting job is dropped to make room. The
     * unbounded queue this replaces was the pipeline's real ceiling — with a
     * full article pool the worker fell hours behind, and because every digest
     * spends a slot of the shared gemma4 gate, the compose workers starved
     * behind cache-warming for stories nobody was writing about anymore.
     */
    private static final int DIGEST_QUEUE_DEPTH = 200;

    /**
     * Single background worker, mirroring the vision pool's sizing rationale: the
     * digest shares the one gemma4 model with extraction + compose, so one worker
     * leaves the latency-sensitive editorial calls their slots — digesting is
     * cache-warming, serialising it is the right trade.
     */
    private final ExecutorService digestExecutor = newDigestExecutor();

    private static ExecutorService newDigestExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(DIGEST_QUEUE_DEPTH),
                BackgroundThreads.single("news-digest"),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        return pool;
    }

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
     * How many of one call's items are worth warming. The caller hands them
     * newest-first, and a brief that renders at most a dozen articles gains
     * little from the tail — while each extra job costs a fetch plus a slot of
     * the shared model gate. Taking the freshest few keeps the backlog short
     * enough that a digest still lands before the compose it belongs to.
     */
    private static final int PREFETCH_PER_CALL = 3;

    /**
     * Queues the freshest not-yet-digested items for background fetch + digest.
     * Fire-and-forget: the compose brief reads cache-only ({@link #ifCached}), so a
     * cold article simply isn't reflected this compose and enriches the next one.
     */
    void prefetch(Collection<Article> items) {
        if (items == null || items.isEmpty() || articleReader == null || !readArticles()) return;
        int queued = 0;
        for (Article n : items) {
            if (queued >= PREFETCH_PER_CALL) break;
            if (n == null || n.link() == null || n.link().isBlank()) continue;
            String link = n.link().trim();
            if (byLink.containsKey(link)) continue;
            digestExecutor.submit(() -> digest(link));
            queued++;
        }
    }

    /**
     * Stops the background digest worker — called from the app shutdown sequence
     * BEFORE Ollama goes down, so a digest mid-flight is interrupted instead of
     * dying against the killed server and riding the retry machinery.
     */
    void shutdown() {
        digestExecutor.shutdownNow();
    }

    /**
     * Lookup-only read for the brief renderer: the digest if this article has
     * already been read + distilled, otherwise empty — never triggers work.
     */
    String ifCached(String link) {
        if (link == null || link.isBlank()) return "";
        return byLink.getOrDefault(link.trim(), "");
    }

    /**
     * Synchronous FULL-TEXT read for fact extraction: fetches the
     * article's readable body ({@link #FULL_ARTICLE_MAX_CHARS} cap) with NO
     * model call - the extraction downstream replaces the digest. Session-
     * cached including failures; consent-walled hosts are fast misses.
     */
    String articleTextNow(String link) {
        if (link == null || link.isBlank() || articleReader == null || !readArticles()) return "";
        String trimmed = link.trim();
        String cached = textByLink.get(trimmed);
        if (cached != null) return cached;
        String host = hostOf(trimmed);
        if (host != null && walledHosts.containsKey(host)) {
            textByLink.put(trimmed, "");
            return "";
        }
        String text = articleReader.fetchArticleText(trimmed, FULL_ARTICLE_MAX_CHARS).orElse("");
        if (text.length() < MIN_ARTICLE_CHARS) text = "";
        textByLink.put(trimmed, text);
        if (!text.isEmpty()) {
            LOG.info("[NEWS] article text read ({} chars): {}", text.length(), trimmed);
        }
        return text;
    }

    String digestNow(String link) {
        if (link == null || link.isBlank() || articleReader == null || !readArticles()) return "";
        String trimmed = link.trim();
        digest(trimmed);
        return byLink.getOrDefault(trimmed, "");
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
        var model = brain.getAgentModel();
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
            if (digestExecutor.isShutdown()) {
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
