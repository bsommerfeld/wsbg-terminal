package de.bsommerfeld.wsbg.terminal.reddit;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.RedditConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.RedditHealthEvent;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anonymous Reddit data source backed by Reddit's Atom feeds on
 * {@code www.reddit.com}. The sibling of {@link RedditScraper}: same
 * {@link RedditSource} contract, same {@link RedditRepository}, same domain
 * objects — but no app registration, no OAuth token, no user login.
 *
 * <h3>Why feeds instead of {@code .json}</h3>
 * Anonymous {@code .json} access is often refused with a 403 for headless
 * clients, which is why {@link RedditScraper} prefers application-only OAuth. The
 * public Atom feeds ({@code …/new.rss}, {@code …/comments/<id>/.rss}) are served
 * to a plain {@link java.net.http.HttpClient} with a 200. They share the same
 * ~100 req / 10 min per-IP budget as the JSON endpoint, so the existing
 * {@link TokenBucketRateLimiter} (tuned for that budget) carries over unchanged.
 *
 * <h3>What the feed cannot carry</h3>
 * Atom has no scores, no comment counts, no poll data, and no comment-tree
 * structure (replies arrive as flat entries with no parent linkage). So
 * {@code score}/{@code numComments}/{@code upvoteRatio} are reported as zero,
 * {@code pollData} is {@code null}, and every comment's {@code parentId} points
 * at its thread. Comment feeds are capped at ~100 entries per post by Reddit.
 * The OAuth source provides full fidelity when reachable; this RSS source is
 * the always-anonymous fallback in the {@link FallbackRedditSource} chain.
 *
 * <h3>Change detection</h3>
 * Without {@code num_comments} we cannot diff comment counts the way the JSON
 * path does. Instead {@link #updateThreadsBatch} compares the comment IDs in
 * the feed against what the repository already holds — a fresh comment ID is
 * the "new activity" signal that re-surfaces a thread for the editorial agent.
 */
@Singleton
public final class RssRedditScraper implements RedditSource {

    private static final Logger LOG = LoggerFactory.getLogger(RssRedditScraper.class);

    private static final String REDDIT_HOST = "www.reddit.com";
    private static final int LISTING_LIMIT = 100;
    private static final int COMMENT_LIMIT = 100;
    private static final int MAX_IMAGES = 10;
    private static final String BLACKLISTED_THREAD_ID = "t3_nwvkto";

    /** Matches the URL in any {@code src="…"} or {@code href="…"} attribute. */
    private static final Pattern ATTR_URL = Pattern.compile("(?:src|href)=\"([^\"]+)\"");
    /** Matches HTML tags (incl. comments) for stripping content down to text. */
    private static final Pattern HTML_TAG = Pattern.compile("<!--.*?-->|<[^>]+>", Pattern.DOTALL);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Thread-safe factory configured once, reused for every parse. */
    private final XMLInputFactory xmlFactory;

    private final RedditRepository repository;
    private final RedditTransport transport;
    private final TokenBucketRateLimiter rateLimiter;
    private final ApplicationEventBus eventBus;

    private volatile boolean degraded = false;

    /**
     * Comment ingestion is the cold-start bottleneck: fetching one comment feed
     * per thread at the anonymous ~0.15 req/s budget turns a 100-thread listing
     * into an ~11-minute serial wait — and clustering doesn't even need
     * comments (the embedding is title+body+vision). So discovery enqueues each
     * new thread here and a single daemon worker drains the queue in the
     * background at the rate limit; the listing scan returns immediately and
     * clusters form within seconds. Comments backfill over the next minutes.
     */
    private final BlockingQueue<PendingComment> commentQueue = new LinkedBlockingQueue<>();

    /**
     * Threads that gained comments asynchronously since the last scan. The next
     * {@link #scanSubreddit} re-surfaces them in its {@link ScrapeStats} so the
     * editorial layer regenerates their headlines with the new evidence — the
     * one channel the {@link RedditSource} contract gives us back to the agent.
     */
    private final Set<String> pendingResurface = ConcurrentHashMap.newKeySet();

    private final Thread commentWorker;

    /** One queued comment-ingestion job. */
    private record PendingComment(String threadId, String permalink) {}

    @Inject
    public RssRedditScraper(RedditRepository repository, GlobalConfig config,
            ApplicationEventBus eventBus) {
        this.repository = repository;
        this.eventBus = eventBus;
        RedditConfig rc = config.getReddit();
        this.rateLimiter = new TokenBucketRateLimiter(rc.getRateLimitBurst(),
                rc.getRateLimitRequestsPerSecond());
        // Plain transport: hits www.reddit.com directly (no OAuth host rewrite,
        // no bearer token) — exactly what the public Atom feeds need.
        this.transport = new JdkRedditTransport();
        this.xmlFactory = XMLInputFactory.newFactory();
        // Harden against XXE — these are remote feeds.
        this.xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        this.xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        this.commentWorker = new Thread(this::runCommentWorker, "rss-comment-ingest");
        this.commentWorker.setDaemon(true);
        this.commentWorker.start();
    }

    /**
     * Drains the comment-ingestion queue in the background at the shared rate
     * limit. When a thread gains fresh comments, its repository copy is updated
     * with the real comment count (so {@code computeDeltas} upstream sees a
     * positive delta) and it is marked for re-surfacing on the next scan.
     */
    private void runCommentWorker() {
        while (true) {
            PendingComment pc;
            try {
                pc = commentQueue.take();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                int fresh = ingestComments(pc.threadId(), pc.permalink(), null);
                if (fresh <= 0) continue;
                RedditThread t = repository.getThread(pc.threadId());
                if (t == null) continue;
                int total = repository.getCommentsForThread(pc.threadId(), 0).size();
                repository.saveThread(withCommentCount(t, total));
                pendingResurface.add(pc.threadId());
            } catch (Exception e) {
                LOG.debug("RSS async comment ingest failed for {}: {}", pc.threadId(), e.getMessage());
            }
        }
    }

    // =====================================================================
    // RedditSource contract
    // =====================================================================

    @Override
    public ScrapeStats scanSubreddit(String subreddit) {
        return scanListing(subreddit, "new");
    }

    @Override
    public ScrapeStats scanSubredditHot(String subreddit) {
        return scanListing(subreddit, "hot");
    }

    private ScrapeStats scanListing(String subreddit, String listing) {
        LOG.info("Scanning r/{}/{} via RSS", subreddit, listing);
        ScrapeStats stats = new ScrapeStats();
        String url = redditUrl("/r/" + subreddit + "/" + listing + ".rss",
                "limit=" + LISTING_LIMIT);

        List<Entry> entries = fetchFeed(url);
        if (entries == null) {
            recordFetchOutcome(false);
            return stats;
        }
        recordFetchOutcome(true);

        List<RedditThread> batch = new ArrayList<>();
        for (Entry e : entries) {
            if (e.id == null || !e.id.startsWith("t3_")) continue;
            if (e.id.equals(BLACKLISTED_THREAD_ID) || e.id.contains("nwvkto")) continue;

            stats.scannedIds.add(e.id);
            RedditThread existing = repository.getThread(e.id);
            RedditThread thread = toThread(e, subreddit, existing);
            batch.add(thread);

            if (existing == null) {
                stats.newThreads++;
                stats.threadUpdates.add(thread);
                logNewThread(thread);
                // Defer the comment fetch to the background worker — fetching it
                // inline here serialises one rate-limited request per thread and
                // is what made cold start take ~11 min. Clustering only needs
                // title+body+vision, so the thread can be clustered now and its
                // comments backfilled (and the headline re-surfaced) shortly.
                commentQueue.add(new PendingComment(thread.id(), thread.permalink()));
            }
        }
        if (!batch.isEmpty()) {
            repository.saveThreadsBatch(batch);
        }
        drainResurfaced(stats);
        return stats;
    }

    /**
     * Folds threads that gained comments asynchronously since the last scan into
     * this scan's {@link ScrapeStats}, so the monitor re-clusters them and the
     * editorial layer regenerates their headlines with the new comment evidence.
     */
    private void drainResurfaced(ScrapeStats stats) {
        if (pendingResurface.isEmpty()) return;
        List<String> ids = new ArrayList<>(pendingResurface);
        pendingResurface.clear();
        for (String id : ids) {
            if (stats.scannedIds.contains(id)) continue; // already in this batch
            RedditThread t = repository.getThread(id);
            if (t == null) continue;
            stats.scannedIds.add(id);
            stats.threadUpdates.add(t);
            stats.newComments++; // mark hasUpdates so the cycle isn't a no-op
        }
    }

    @Override
    public ScrapeStats updateThreadsBatch(List<String> threadIds) {
        ScrapeStats stats = new ScrapeStats();
        if (threadIds == null || threadIds.isEmpty()) return stats;

        for (String rawId : new HashSet<>(threadIds)) {
            String id = rawId.startsWith("t3_") ? rawId : "t3_" + rawId;
            RedditThread existing = repository.getThread(id);
            if (existing == null) continue; // no permalink to fetch the feed from

            int newComments = ingestComments(id, existing.permalink(), null);
            if (newComments > 0) {
                stats.newComments += newComments;
                // Bump activity to "now" so the gap-fill window keeps this
                // thread hot, and re-surface it for clustering.
                RedditThread bumped = withActivity(existing, System.currentTimeMillis() / 1000);
                stats.threadUpdates.add(bumped);
                repository.saveThread(bumped);
                LOG.info("RSS update: {} (+{} new comments) — {}", id, newComments, existing.title());
            }
        }
        return stats;
    }

    @Override
    public ThreadAnalysisContext fetchThreadContext(String permalink) {
        ThreadAnalysisContext context = new ThreadAnalysisContext();
        if (permalink == null || permalink.isBlank()) {
            LOG.error("Cannot fetch context: permalink is empty");
            return context;
        }
        String path = normalizePermalink(permalinkOf(permalink));
        String url = redditUrl(path + "/.rss", "limit=" + COMMENT_LIMIT);

        List<Entry> entries = fetchFeed(url);
        if (entries == null || entries.isEmpty()) return context;

        // First t3_ entry is the post itself; the rest are comments.
        for (Entry e : entries) {
            if (e.id != null && e.id.startsWith("t3_")) {
                context.threadId = e.id;
                context.title = e.title;
                context.selftext = extractBodyText(e.content);
                List<String> imgs = extractImages(e.content, e.thumbnail);
                if (!imgs.isEmpty()) context.imageUrl = imgs.get(0);
                break;
            }
        }
        if (context.threadId == null) return context;

        for (Entry e : entries) {
            if (e.id == null || !e.id.startsWith("t1_")) continue;
            RedditComment comment = toComment(e, context.threadId);
            context.comments.add(displayAuthor(comment.author()) + ": " + comment.body());
            repository.saveComment(comment);
        }
        return context;
    }

    @Override
    public boolean probe(String subreddit) {
        if (subreddit == null || subreddit.isBlank()) return false;
        String url = redditUrl("/r/" + subreddit + "/new.rss", "limit=1");
        return fetchFeed(url) != null;
    }

    @Override
    public String sourceName() {
        return "RSS";
    }

    // =====================================================================
    // Comment ingestion
    // =====================================================================

    /**
     * Fetches the comment feed for a thread and persists every comment,
     * returning how many were genuinely new (not already in the repository).
     * The new-comment count is the change signal that drives re-clustering.
     */
    private int ingestComments(String threadId, String permalink, ThreadAnalysisContext sink) {
        if (permalink == null || permalink.isBlank()) return 0;
        String url = redditUrl(normalizePermalink(permalinkOf(permalink)) + "/.rss", "limit=" + COMMENT_LIMIT);
        List<Entry> entries = fetchFeed(url);
        if (entries == null) return 0;

        Set<String> known = new HashSet<>();
        for (RedditComment c : repository.getCommentsForThread(threadId, 0)) {
            known.add(c.id());
        }

        int fresh = 0;
        for (Entry e : entries) {
            if (e.id == null || !e.id.startsWith("t1_")) continue;
            if (!known.contains(e.id)) fresh++;
            RedditComment comment = toComment(e, threadId);
            if (sink != null) {
                sink.comments.add(displayAuthor(comment.author()) + ": " + comment.body());
            }
            repository.saveComment(comment);
        }
        return fresh;
    }

    // =====================================================================
    // Atom → domain mapping
    // =====================================================================

    private RedditThread toThread(Entry e, String subredditDefault, RedditThread existing) {
        long created = parseEpoch(e.published != null ? e.published : e.updated);
        long lastActivity = existing != null ? existing.lastActivityUtc() : created;
        // RSS carries no score / comment-count / upvote-ratio / poll data.
        return new RedditThread(
                e.id,
                subredditDefault,
                e.title != null ? e.title : "No Title",
                normalizeAuthor(e.authorName),
                extractBodyText(e.content),
                created,
                permalinkOf(e.link),
                0,
                0.0,
                0,
                lastActivity,
                extractImages(e.content, e.thumbnail),
                null);
    }

    private RedditComment toComment(Entry e, String threadId) {
        long created = parseEpoch(e.published != null ? e.published : e.updated);
        long now = System.currentTimeMillis() / 1000;
        return new RedditComment(
                e.id,
                threadId,
                threadId, // no parent linkage in Atom — flat under the thread
                stripUserPrefix(e.authorName),
                extractBodyText(e.content),
                0,
                created,
                now,
                now,
                extractImages(e.content, e.thumbnail));
    }

    private RedditThread withActivity(RedditThread t, long lastActivityUtc) {
        return new RedditThread(t.id(), t.subreddit(), t.title(), t.author(), t.textContent(),
                t.createdUtc(), t.permalink(), t.score(), t.upvoteRatio(), t.numComments(),
                lastActivityUtc, t.imageUrls(), t.pollData());
    }

    /**
     * Stamps the real comment count (Atom carries none, so threads start at 0)
     * and bumps activity to now. The non-zero count is what lets the upstream
     * {@code computeDeltas} register a positive delta and re-fire the editorial
     * pass once the async worker has backfilled a thread's comments.
     */
    private RedditThread withCommentCount(RedditThread t, int numComments) {
        return new RedditThread(t.id(), t.subreddit(), t.title(), t.author(), t.textContent(),
                t.createdUtc(), t.permalink(), t.score(), t.upvoteRatio(), numComments,
                System.currentTimeMillis() / 1000, t.imageUrls(), t.pollData());
    }

    // =====================================================================
    // HTTP + feed parsing
    // =====================================================================

    /** Returns parsed entries, or {@code null} when the fetch/parse failed. */
    private List<Entry> fetchFeed(String url) {
        try {
            rateLimiter.acquire();
            RedditResponse response = transport.get(url);
            checkRateLimit(response);
            if (response.statusCode() != 200) {
                LOG.warn("RSS fetch failed (HTTP {}): {}", response.statusCode(), url);
                return null;
            }
            return parseFeed(response.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            LOG.error("RSS fetch/parse error for {}", url, ex);
            return null;
        }
    }

    /**
     * Parses an Atom feed into a flat list of entries via StAX. Only fields the
     * domain needs are captured; everything else (feed-level metadata,
     * categories, icons) is ignored.
     */
    private List<Entry> parseFeed(String xml) throws Exception {
        List<Entry> entries = new ArrayList<>();
        XMLStreamReader r = xmlFactory.createXMLStreamReader(new StringReader(xml));
        boolean inEntry = false;
        boolean authorNameSet = false;
        Entry cur = null;
        try {
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String ln = r.getLocalName();
                    if ("entry".equals(ln)) {
                        inEntry = true;
                        authorNameSet = false;
                        cur = new Entry();
                    } else if (inEntry && cur != null) {
                        switch (ln) {
                            case "id" -> cur.id = textOf(r);
                            case "title" -> cur.title = textOf(r);
                            case "content" -> cur.content = rawTextOf(r);
                            case "published" -> cur.published = textOf(r);
                            case "updated" -> cur.updated = textOf(r);
                            case "name" -> {
                                String n = textOf(r);
                                if (!authorNameSet) { cur.authorName = n; authorNameSet = true; }
                            }
                            case "link" -> {
                                String href = r.getAttributeValue(null, "href");
                                if (href != null && cur.link == null) cur.link = href;
                            }
                            case "thumbnail" -> {
                                String u = r.getAttributeValue(null, "url");
                                if (u != null && cur.thumbnail == null) cur.thumbnail = u;
                            }
                            default -> { /* ignore */ }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("entry".equals(r.getLocalName())) {
                        if (cur != null) entries.add(cur);
                        inEntry = false;
                        cur = null;
                    }
                }
            }
        } finally {
            r.close();
        }
        return entries;
    }

    /** Reads the element's text content, trimmed. Safe for text-only elements. */
    private static String textOf(XMLStreamReader r) throws Exception {
        String t = r.getElementText();
        return t != null ? t.trim() : null;
    }

    /** Reads the element's text content without trimming (HTML content body). */
    private static String rawTextOf(XMLStreamReader r) throws Exception {
        return r.getElementText();
    }

    private void checkRateLimit(RedditResponse response) {
        response.header("x-ratelimit-remaining").ifPresent(remaining -> {
            try {
                if (Double.parseDouble(remaining) < 2.0) {
                    response.header("x-ratelimit-reset").ifPresent(reset -> {
                        int waitSecs = (int) Double.parseDouble(reset) + 1;
                        LOG.warn("Reddit RSS rate limit near. Sleeping for {}s", waitSecs);
                        try {
                            Thread.sleep(waitSecs * 1000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
            } catch (NumberFormatException ignored) {
                // malformed header — ignore
            }
        });
    }

    private void recordFetchOutcome(boolean success) {
        if (success) {
            if (degraded) {
                degraded = false;
                postHealth(RedditHealthEvent.State.OK);
            }
        } else if (!degraded) {
            degraded = true;
            postHealth(RedditHealthEvent.State.DEGRADED);
        }
    }

    private void postHealth(RedditHealthEvent.State state) {
        if (eventBus == null) return;
        try {
            long since = state == RedditHealthEvent.State.DEGRADED
                    ? System.currentTimeMillis() : 0L;
            eventBus.post(new RedditHealthEvent(state, since));
        } catch (Exception e) {
            LOG.debug("Failed to post RedditHealthEvent: {}", e.getMessage());
        }
    }

    // =====================================================================
    // Text / URL utilities
    // =====================================================================

    /**
     * Reduces a feed entry's HTML content to readable text: drops the RSS
     * "submitted by … [link] [comments]" footer, strips tags, unescapes
     * entities, and collapses whitespace. Returns {@code ""} for image-only
     * posts (their pictures live in the image list instead).
     */
    private String extractBodyText(String html) {
        if (html == null || html.isBlank()) return "";
        String s = html;
        int cut = s.indexOf("submitted by");
        if (cut >= 0) s = s.substring(0, cut);
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = unescapeHtml(s);
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        return s;
    }

    /**
     * Collects image URLs from the entry's HTML attributes plus the
     * {@code media:thumbnail}. When a full-resolution {@code i.redd.it} URL is
     * present, the preview variants are dropped so the same picture isn't
     * analysed twice. Capped at {@value #MAX_IMAGES}.
     */
    private List<String> extractImages(String html, String thumbnail) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (html != null) {
            Matcher m = ATTR_URL.matcher(html);
            while (m.find()) {
                String u = unescapeHtml(m.group(1));
                if (isImageUrl(u)) all.add(u);
            }
        }
        if (thumbnail != null) {
            String u = unescapeHtml(thumbnail);
            if (isImageUrl(u)) all.add(u);
        }
        if (all.isEmpty()) return List.of();

        List<String> full = new ArrayList<>();
        for (String u : all) if (u.contains("i.redd.it")) full.add(u);
        List<String> chosen = full.isEmpty() ? new ArrayList<>(all) : full;
        return chosen.size() > MAX_IMAGES ? chosen.subList(0, MAX_IMAGES) : chosen;
    }

    private static boolean isImageUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains(".jpg") || lower.contains(".jpeg")
                || lower.contains(".png") || lower.contains(".webp")
                || lower.contains(".gif");
    }

    private static String unescapeHtml(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&#32;", " ");
    }

    /** "/u/name" or "u/name" → "u/name"; placeholders pass through. */
    private static String normalizeAuthor(String name) {
        String u = stripUserPrefix(name);
        if (u.equals("[deleted]") || u.equals("unknown") || u.isBlank()) return u;
        return "u/" + u;
    }

    /** "/u/name" → "name". */
    private static String stripUserPrefix(String name) {
        if (name == null || name.isBlank()) return "unknown";
        String n = name.trim();
        if (n.startsWith("/u/")) return n.substring(3);
        if (n.startsWith("u/")) return n.substring(2);
        return n;
    }

    private static String displayAuthor(String author) {
        return "`u/" + author + "`";
    }

    /** Extracts the Reddit path from a feed link, dropping scheme + host. */
    private static String permalinkOf(String link) {
        if (link == null) return "";
        int idx = link.indexOf("/r/");
        return idx >= 0 ? link.substring(idx) : link;
    }

    private static String normalizePermalink(String permalink) {
        String p = permalink;
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (!p.startsWith("/")) p = "/" + p;
        return p;
    }

    private static long parseEpoch(String iso) {
        if (iso == null || iso.isBlank()) return System.currentTimeMillis() / 1000;
        try {
            return OffsetDateTime.parse(iso).toEpochSecond();
        } catch (Exception e) {
            return System.currentTimeMillis() / 1000;
        }
    }

    /**
     * Builds an absolute reddit.com URL, percent-encoding the path and query so
     * non-ASCII permalink slugs (e.g. German umlauts in titles) don't blow up
     * {@code URI} parsing in the transport.
     */
    private static String redditUrl(String path, String query) {
        try {
            return new URI("https", REDDIT_HOST, path, query, null).toASCIIString();
        } catch (Exception e) {
            return "https://" + REDDIT_HOST + path + (query != null ? "?" + query : "");
        }
    }

    private void logNewThread(RedditThread thread) {
        String imgTag = thread.imageUrls().isEmpty() ? ""
                : (thread.imageUrls().size() == 1 ? "  [img]" : "  [img×" + thread.imageUrls().size() + "]");
        LOG.info("[REDDIT-RSS] new thread {}{} | {}", thread.id(), imgTag, thread.title());
    }

    /** Mutable accumulator for one Atom {@code <entry>}. */
    private static final class Entry {
        String id;
        String title;
        String content;
        String authorName;
        String published;
        String updated;
        String link;
        String thumbnail;
    }
}
