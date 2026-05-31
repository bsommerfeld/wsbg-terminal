package de.bsommerfeld.wsbg.terminal.reddit;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Live smoke test for {@link RssRedditScraper} against real Reddit Atom feeds.
 * Tagged {@code integration} so it stays out of the default build. Run with:
 *
 * <pre>mvn test -pl reddit -Dtest=RssRedditScraperIT -Dtest.excludedGroups=</pre>
 *
 * Verifies end-to-end that the parser turns a real comment feed into a populated
 * thread (title, body or image) plus parsed comments (authors + bodies) — i.e.
 * that no content is lost on the RSS path. Only two network calls (one to grab a
 * fresh permalink, one for {@code fetchThreadContext}) so it stays under the
 * burst budget and runs in seconds.
 */
@Tag("integration")
class RssRedditScraperIT {

    private static final String SUB = "wallstreetbetsGER";

    @Test
    void fetchThreadContext_parsesLivePostAndComments() throws Exception {
        RssRedditScraper scraper = new RssRedditScraper(
                new RedditRepository(), new GlobalConfig(), new ApplicationEventBus());

        String permalink = firstPermalink();
        assertNotNull(permalink, "could not extract a live permalink from " + SUB + "/new.rss");
        System.out.println("[IT] permalink = " + permalink);

        ThreadAnalysisContext ctx = scraper.fetchThreadContext(permalink);

        System.out.println("[IT] threadId   = " + ctx.threadId);
        System.out.println("[IT] title      = " + ctx.title);
        System.out.println("[IT] body       = " + snippet(ctx.selftext, 200));
        System.out.println("[IT] imageUrl   = " + ctx.imageUrl);
        System.out.println("[IT] #comments  = " + ctx.comments.size());
        for (int i = 0; i < Math.min(8, ctx.comments.size()); i++) {
            System.out.println("[IT]   - " + snippet(ctx.comments.get(i), 120));
        }

        assertNotNull(ctx.threadId, "thread id should be parsed from the post entry");
        assertNotNull(ctx.title, "title should be parsed");
    }

    @Test
    void scanSubreddit_populatesRepository() {
        RedditRepository repo = new RedditRepository();
        RssRedditScraper scraper = new RssRedditScraper(repo, new GlobalConfig(), new ApplicationEventBus());

        scraper.scanSubreddit(SUB);

        List<RedditThread> threads = repo.getAllThreads();
        System.out.println("[IT] scanned threads = " + threads.size());
        assertFalse(threads.isEmpty(), "scan should persist at least one thread");

        int withImages = 0, withComments = 0;
        for (RedditThread t : threads) {
            if (!t.imageUrls().isEmpty()) withImages++;
            List<RedditComment> cs = repo.getCommentsForThread(t.id(), 0);
            if (!cs.isEmpty()) withComments++;
        }
        RedditThread sample = threads.get(0);
        System.out.println("[IT] sample: " + sample.id() + " | " + sample.title());
        System.out.println("[IT]   author=" + sample.author() + " created=" + sample.createdUtc()
                + " permalink=" + sample.permalink() + " images=" + sample.imageUrls());
        System.out.println("[IT] threads w/ images = " + withImages + ", w/ comments = " + withComments);
    }

    /** Grabs the first comments-permalink from the live new.rss feed. */
    private String firstPermalink() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://www.reddit.com/r/" + SUB + "/new.rss?limit=5"))
                .header("User-Agent", RedditUserAgent.VALUE)
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("href=\"(https://www\\.reddit\\.com/r/" + SUB
                + "/comments/[^\"]+)\"").matcher(resp.body());
        return m.find() ? m.group(1) : null;
    }

    private static String snippet(String s, int n) {
        if (s == null) return "<null>";
        String oneLine = s.replace('\n', ' ');
        return oneLine.length() > n ? oneLine.substring(0, n) + "…" : oneLine;
    }
}
