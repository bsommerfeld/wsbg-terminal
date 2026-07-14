package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the vision pre-fetch pool and all image cache-warming (extracted from
 * {@link PassiveMonitorService}). Vision is the slowest step in the pipeline
 * (~5-25 s per call on gemma4); a single background worker warms the per-URL cache
 * on {@link AgentBrain} so the editorial reports read cache-only and never block on
 * cold images. Vision is currently HARD-DISABLED ({@link #analyzeImages()} always
 * answers {@code false}, regardless of {@code headlines.analyze-images}) — images
 * are ignored wire-wide until the feature returns.
 */
final class VisionPrefetcher {

    /**
     * Vision pre-fetch pool — a single worker. Vision shares the editorial agent's
     * model + num_ctx, so both hit ONE Ollama runner with {@code NUM_PARALLEL=2}
     * request slots. Keeping vision to one worker leaves the second slot free for
     * the latency-sensitive editorial calls — vision is background cache-warming, so
     * serialising it is the right trade.
     */
    private final ExecutorService visionExecutor = Executors.newFixedThreadPool(1, r -> {
        Thread t = new Thread(r, "vision-prefetch");
        t.setDaemon(true);
        return t;
    });

    /**
     * Gallery slides that feed the blocking vision join ({@code describeAll}). Kept
     * small so a deep gallery can't stall time-to-headline — the first few slides are
     * plenty to route the post. Slides beyond this are prefetched async for the
     * report only.
     */
    private static final int EMBED_GALLERY_IMAGES = 4;

    private final AgentBrain brain;
    private final RedditRepository repository;
    private final GlobalConfig config;

    VisionPrefetcher(AgentBrain brain, RedditRepository repository, GlobalConfig config) {
        this.brain = brain;
        this.repository = repository;
        this.config = config;
    }

    /**
     * Pre-launches vision for every thread in {@code updates} that carries a fresh
     * image URL, so multiple threads' images analyse in parallel on the pool. Returns
     * a per-thread future map (empty when vision is off); the caller joins each future
     * lazily when it reaches that thread. The brain caches by URL, so already-seen
     * images resolve instantly.
     */
    Map<String, CompletableFuture<String>> launchBatch(List<RedditThread> updates) {
        Map<String, CompletableFuture<String>> visionFutures = new HashMap<>();
        if (!analyzeImages()) return visionFutures;
        for (RedditThread t : updates) {
            List<String> urls = t.imageUrls();
            if (urls.isEmpty()) continue;
            visionFutures.put(t.id(),
                    CompletableFuture.supplyAsync(() -> describeAll(urls), visionExecutor));
        }
        return visionFutures;
    }

    /**
     * Cache-warms the images that don't feed the blocking join — gallery slides
     * beyond the embedded set, and the images on the thread's comments. Fire-and-
     * forget: the report reads cache-only, so a cold image simply won't appear this
     * tick and gets re-surfaced once the prefetch settles.
     */
    void warm(RedditThread t) {
        prefetchThreadImages(t);
        prefetchCommentImages(t.id());
    }

    /**
     * Builds a vision block for a thread from the restored cache ONLY — never
     * triggers a (slow) fresh analysis. Returns "" if the thread has no images or
     * none are cached yet.
     */
    String cachedVisionText(RedditThread t) {
        List<String> urls = t.imageUrls();
        if (urls.isEmpty()) return "";
        int n = Math.min(urls.size(), EMBED_GALLERY_IMAGES);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String desc = brain.describeImageIfCached(urls.get(i));
            if (desc == null || desc.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(desc);
        }
        return sb.toString();
    }

    void shutdown() {
        visionExecutor.shutdownNow();
    }

    /**
     * Prefetches the gallery slides that {@code describeAll} skipped (those beyond
     * {@link #EMBED_GALLERY_IMAGES}). Fire-and-forget — shown in the report cache-only
     * once ready. The embedded slides are already being computed by the thread's
     * vision future, so we start past them.
     */
    private void prefetchThreadImages(RedditThread t) {
        if (!analyzeImages()) return; // vision opt-out — no cache-warming
        List<String> urls = t.imageUrls();
        for (int i = EMBED_GALLERY_IMAGES; i < urls.size(); i++) {
            String url = urls.get(i);
            if (brain.isImageCached(url)) continue;
            visionExecutor.submit(() -> brain.describeImage(url));
        }
    }

    /**
     * Submits EVERY image on EVERY comment of {@code threadId} to the vision pool for
     * cache-filling — no top-N cap, no score gate. We only sort by score so
     * high-signal images warm first — nothing is dropped. Fire-and-forget.
     */
    private void prefetchCommentImages(String threadId) {
        if (!analyzeImages()) return; // vision opt-out — no cache-warming
        List<RedditComment> comments = repository.getCommentsForThread(threadId, 0);
        if (comments.isEmpty()) return;
        comments.stream()
                .filter(c -> !c.imageUrls().isEmpty())
                .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                .forEach(c -> {
                    for (String url : c.imageUrls()) {
                        if (brain.isImageCached(url)) continue;
                        visionExecutor.submit(() -> brain.describeImage(url));
                    }
                });
    }

    /**
     * Describes the first {@link #EMBED_GALLERY_IMAGES} slides of a thread's gallery
     * and joins them into one labelled block. Deeper slides are left to async prefetch
     * + the report. Empty descriptions (cache miss + vision failure, or "no image"
     * placeholders) are skipped.
     */
    private String describeAll(List<String> urls) {
        if (urls.size() == 1) {
            return brain.describeImage(urls.get(0));
        }
        int n = Math.min(urls.size(), EMBED_GALLERY_IMAGES);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String desc = brain.describeImage(urls.get(i));
            if (desc == null || desc.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append("[IMAGE ").append(i + 1).append('/').append(n).append("] ").append(desc);
        }
        return sb.toString();
    }

    /**
     * The vision gate. HARD-DISABLED: always {@code false}, deliberately ignoring
     * {@code headlines.analyze-images} — the UI toggle was removed and images stay
     * out of the pipeline no matter what an old {@code config.toml} says. The config
     * key survives only for a possible future re-activation.
     */
    private boolean analyzeImages() {
        return false;
    }
}
