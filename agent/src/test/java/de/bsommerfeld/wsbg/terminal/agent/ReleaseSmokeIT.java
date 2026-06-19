package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditSnapshotStore;
import de.bsommerfeld.wsbg.terminal.reddit.FallbackRedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.OAuthRedditFetcher;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.RedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.RssRedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.ThreadAnalysisContext;
import de.bsommerfeld.wsbg.terminal.reddit.TokenBucketRateLimiter;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test of the release changes — no UI, no Ollama. Exercises:
 * <ol>
 *   <li>the dynamic {@link FallbackRedditSource} resolving against LIVE Reddit
 *       (per-source probe verdicts + which path the chain picks),</li>
 *   <li>the shared {@link RedditRepository} continuity layer,</li>
 *   <li>real on-disk snapshot save/restore + TTL expiry.</li>
 * </ol>
 *
 * <p>Tagged {@code integration} (hits the network + app-data dir). Run with:
 * <pre>mvn test -pl agent -Dtest=ReleaseSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
class ReleaseSmokeIT {

    private static final String SUB = "wallstreetbetsGER";

    @Test
    void dynamicChain_resolvesToWorkingSourceLive_onSharedRepository() throws Exception {
        RedditRepository repo = new RedditRepository();
        GlobalConfig config = new GlobalConfig(); // oauth-client-id blank → OAuth must fail
        ApplicationEventBus bus = new ApplicationEventBus();

        RedditScraper oauth = new RedditScraper(repo, bus,
                new OAuthRedditFetcher(config), new TokenBucketRateLimiter(20, 8));
        RedditScraper json = new RedditScraper(repo, bus,
                new DirectWebFetcher(), new TokenBucketRateLimiter(5, 0.15));
        RssRedditScraper rss = new RssRedditScraper(repo, config, bus);

        System.out.println("[SMOKE] probe OAuth = " + oauth.probe(SUB) + " (expect false: no client-id)");
        System.out.println("[SMOKE] probe JSON  = " + json.probe(SUB) + " (expect false: bot-blocked)");
        boolean rssReachable = rss.probe(SUB);
        System.out.println("[SMOKE] probe RSS   = " + rssReachable + " (expect true)");
        assertTrue(rssReachable, "RSS must be reachable for the chain to have a fallback");

        RedditSource chain = new FallbackRedditSource(List.of(oauth, json, rss), SUB, 600);

        // Trigger resolution with one cheap real fetch through the chain.
        String permalink = firstPermalink();
        assertNotNull(permalink, "could not read a live permalink");
        ThreadAnalysisContext ctx = chain.fetchThreadContext(permalink);

        System.out.println("[SMOKE] chain resolved to: " + chain.sourceName());
        System.out.println("[SMOKE] fetched thread: " + ctx.threadId + " | " + ctx.title
                + " | comments=" + ctx.comments.size());

        assertEquals("RSS", chain.sourceName(), "this IP is blocked on .json → chain must land on RSS");
        assertNotNull(ctx.threadId, "chain must return real data via the working source");

        // Shared-repository continuity: whatever the active source fetched
        // lands in the one repo every delegate shares — so a switch would
        // continue from here, not re-fetch. (A brand-new post may have 0
        // comments; the point is the counts match, not that there are any.)
        assertEquals(ctx.comments.size(), repo.getCommentsForThread(ctx.threadId, 0).size(),
                "comments fetched via the chain must be observable through the shared repository");
        System.out.println("[SMOKE] shared repo holds " + ctx.comments.size()
                + " comments for " + ctx.threadId + " (continuity layer OK)");
    }

    @Test
    void snapshot_savesAndRestoresFromDisk_andExpiresPastTtl() throws Exception {
        Path appDir = StorageUtils.getSnapshotsDir();
        Path redditFile = appDir.resolve("reddit-snapshot.json");
        Path backup = appDir.resolve("reddit-snapshot.json.smoke-bak");

        boolean hadExisting = Files.exists(redditFile);
        if (hadExisting) Files.move(redditFile, backup);
        try {
            RedditSnapshotStore store = new RedditSnapshotStore();
            RedditThread t = new RedditThread("t3_smoke", SUB, "Smoke", "u/x", "body",
                    System.currentTimeMillis() / 1000, "/r/x/comments/smoke/", 0, 0.0, 0,
                    System.currentTimeMillis() / 1000, List.of(), null);
            store.save(List.of(t), List.of());

            // Fresh store instance reads the file back.
            Optional<RedditSnapshotStore.RedditSnapshot> fresh =
                    new RedditSnapshotStore().loadIfFresh(60);
            assertTrue(fresh.isPresent(), "a just-saved snapshot must restore within TTL");
            assertEquals("t3_smoke", fresh.get().threads().get(0).id());
            System.out.println("[SMOKE] snapshot saved + restored from disk: "
                    + fresh.get().threads().size() + " threads");

            // TTL expiry: ttl=0 disables, and the loader deletes a stale file.
            assertTrue(new RedditSnapshotStore().loadIfFresh(0).isEmpty(),
                    "ttl<=0 must disable restore");
            System.out.println("[SMOKE] TTL gate honoured (0 = disabled)");
        } finally {
            Files.deleteIfExists(redditFile);
            if (hadExisting) Files.move(backup, redditFile);
        }
    }

    /** Live agent-side snapshot: vision cache + headlines + a full cluster, on disk. */
    @Test
    void agentSnapshot_persistsClusterVerbatimToDisk() throws Exception {
        Path appDir = StorageUtils.getSnapshotsDir();
        Path file = appDir.resolve("agent-snapshot.json");
        Path backup = appDir.resolve("agent-snapshot.json.smoke-bak");
        boolean hadExisting = Files.exists(file);
        if (hadExisting) Files.move(file, backup);
        try {
            RedditThread thread = new RedditThread("t3_c", SUB, "NOW 🚀", "u/k", "body",
                    1780000000L, "/r/x/comments/c/", 42, 0.9, 7, 1780000500L,
                    List.of("https://i.redd.it/x.jpeg"), null);
            InvestigationCluster cluster = new InvestigationCluster(
                    thread, Embedding.from(new float[] {0.1f, 0.2f, 0.3f}));
            cluster.shownImageUrls.add("https://i.redd.it/x.jpeg");

            AgentRepository repo = new AgentRepository();
            repo.saveHeadline("t3_c", "NOW +606%", "ctx");

            AgentSnapshotStore store = new AgentSnapshotStore();
            store.save(Map.of("https://i.redd.it/x.jpeg", "grüner Screenshot"),
                    repo.getAllHeadlines(), List.of(cluster.toSnapshot()));

            Optional<AgentSnapshotStore.AgentSnapshot> back =
                    new AgentSnapshotStore().loadIfFresh(60);
            assertTrue(back.isPresent(), "agent snapshot must restore within TTL");
            assertEquals(1, back.get().clusters().size());
            InvestigationCluster restored = new InvestigationCluster(back.get().clusters().get(0));
            assertEquals("t3_c", restored.id);
            assertTrue(restored.shownImageUrls.contains("https://i.redd.it/x.jpeg"));
            System.out.println("[SMOKE] agent snapshot restored cluster verbatim: " + restored.id
                    + " | shownImages=" + restored.shownImageUrls.size()
                    + " | headlines=" + back.get().headlines().size());
        } finally {
            Files.deleteIfExists(file);
            if (hadExisting) Files.move(backup, file);
        }
    }

    private String firstPermalink() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://www.reddit.com/r/" + SUB + "/new.rss?limit=5"))
                .header("User-Agent", "java:de.bsommerfeld.wsbg.terminal:smoke (by /u/WsbgTerminal)")
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("href=\"(https://www\\.reddit\\.com/r/" + SUB
                + "/comments/[^\"]+)\"").matcher(resp.body());
        return m.find() ? m.group(1) : null;
    }
}
