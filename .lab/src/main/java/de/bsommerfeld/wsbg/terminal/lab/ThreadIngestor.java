package de.bsommerfeld.wsbg.terminal.lab;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.RedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.ThreadAnalysisContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a single Reddit thread (post + comments) into the shared
 * {@link RedditRepository} from a thread URL, source-agnostically — so the same
 * call works whether the live {@link RedditSource} resolved to OAuth, anonymous
 * {@code .json}, or the RSS fallback.
 *
 * <h3>Why two fetches</h3>
 * {@link RedditSource#fetchThreadContext} reliably returns the post's title /
 * body / hero image and saves its comments on <em>all</em> three sources, but it
 * does not persist the {@link RedditThread} row itself. So we build a stub thread
 * from that context and save it, then call
 * {@link RedditSource#updateThreadsBatch} — on JSON/OAuth that overwrites the
 * stub with the full-fidelity thread (score, gallery, poll) and re-fetches
 * comments; on RSS it backfills the comment feed against the stub. Either way the
 * repository ends up with the best thread the active source can provide.
 */
public final class ThreadIngestor {

    private static final Logger LOG = LoggerFactory.getLogger(ThreadIngestor.class);

    /** {@code /r/<sub>/comments/<id>[/<slug>]} anywhere in the input. */
    private static final Pattern FULL =
            Pattern.compile("/r/([^/\\s]+)/comments/([A-Za-z0-9]+)");
    /** {@code comments/<id>} or {@code /<id>} fallback. */
    private static final Pattern COMMENTS_ID =
            Pattern.compile("comments/([A-Za-z0-9]{4,12})");
    /** A bare base-36 thread id. */
    private static final Pattern BARE_ID =
            Pattern.compile("^(?:t3_)?([A-Za-z0-9]{4,12})$");

    private final RedditSource source;
    private final RedditRepository repository;
    private final String fallbackSubreddit;

    public ThreadIngestor(RedditSource source, RedditRepository repository, String fallbackSubreddit) {
        this.source = source;
        this.repository = repository;
        this.fallbackSubreddit = fallbackSubreddit == null || fallbackSubreddit.isBlank()
                ? "wallstreetbetsGER" : fallbackSubreddit;
    }

    /** Outcome of one ingest attempt. {@code thread} is {@code null} only when the URL couldn't be parsed. */
    public record IngestResult(
            String requestedUrl,
            RedditThread thread,
            String permalink,
            String sourceName,
            int commentCount,
            boolean fetched) {
    }

    public IngestResult ingest(String url) {
        Parsed p = parse(url);
        if (p == null) {
            LOG.warn("Could not parse a thread id from: {}", url);
            return new IngestResult(url, null, null, source.sourceName(), 0, false);
        }

        ThreadAnalysisContext ctx = source.fetchThreadContext(p.permalink);
        String id = (ctx.threadId != null && !ctx.threadId.isBlank())
                ? ctx.threadId : "t3_" + p.id;

        if (repository.getThread(id) == null) {
            long now = System.currentTimeMillis() / 1000;
            List<String> images = (ctx.imageUrl != null && !ctx.imageUrl.isBlank())
                    ? List.of(ctx.imageUrl) : List.of();
            String title = ctx.title != null && !ctx.title.isBlank() ? ctx.title : p.id;
            String body = ctx.selftext != null ? ctx.selftext : "";
            RedditThread stub = new RedditThread(id, p.subreddit, title, "u/unknown", body,
                    now, p.permalink, 0, 0.0, 0, now, images, null);
            repository.saveThread(stub);
        }

        // Enrich (full fidelity on JSON/OAuth; comment backfill on RSS).
        try {
            source.updateThreadsBatch(List.of(id));
        } catch (Exception e) {
            LOG.debug("updateThreadsBatch enrichment failed for {}: {}", id, e.getMessage());
        }

        RedditThread thread = repository.getThread(id);
        int comments = repository.getCommentsForThread(id, 0).size();
        boolean fetched = !ctx.isEmpty() || comments > 0;
        return new IngestResult(url, thread, p.permalink, source.sourceName(), comments, fetched);
    }

    private record Parsed(String subreddit, String id, String permalink) {}

    private Parsed parse(String input) {
        if (input == null || input.isBlank()) return null;
        String s = input.trim();

        Matcher m = FULL.matcher(s);
        if (m.find()) {
            String sub = m.group(1);
            String id = m.group(2);
            return new Parsed(sub, id, "/r/" + sub + "/comments/" + id);
        }

        Matcher c = COMMENTS_ID.matcher(s);
        if (c.find()) {
            String id = c.group(1);
            return new Parsed(fallbackSubreddit, id, "/r/" + fallbackSubreddit + "/comments/" + id);
        }

        Matcher b = BARE_ID.matcher(s);
        if (b.matches()) {
            String id = b.group(1);
            return new Parsed(fallbackSubreddit, id, "/r/" + fallbackSubreddit + "/comments/" + id);
        }
        return null;
    }
}
