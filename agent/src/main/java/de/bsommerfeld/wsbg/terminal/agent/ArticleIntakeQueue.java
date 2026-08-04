package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.util.BackgroundThreads;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The KI-DD's article INTAKE as producer/consumer: eight fetch producers pull
 * article full texts off the net while the two LLM lanes chew on what already
 * landed. Before this the two lanes did both jobs on one thread - fetch (up to
 * 12 s) THEN 2-8 model calls - so the GPU idled through every HTTP wait and the
 * net idled through every extraction. Nothing about the model side changes:
 * {@code LlmGate} keeps its two permits, {@code runTwoWide} stays two-wide
 * (gemma4 is GPU-bound, a third slot measurably LOWERED throughput). The whole
 * win is that those two slots never wait on the wire again.
 *
 * <p><b>The traded-away cost (accepted, not an oversight):</b> the fetch runs
 * through the shared browser-first transport, which keeps ONE hidden tab per
 * origin with {@code MAX_TABS = 16} across the WHOLE app. Eight simultaneous
 * origins here can therefore evict the tabs the wire, the weather leg and the
 * watchlist are holding, and their next fetch pays a fresh anchor (page load,
 * consent, cookies) instead of riding a warm tab. That is the deal: the DD is
 * the interactive job a human is watching, the background legs are not, and
 * they lose at most one warm-up - they never lose data.
 *
 * <p><b>Host politeness lives here</b>, not in the transport: at most
 * {@link #PER_HOST_CONCURRENCY} in-flight fetches per host and at least
 * {@link #PER_HOST_MIN_GAP_MS} between two fetch STARTS on the same host
 * (2.5 req/s). The work list is round-robin interleaved by host before it is
 * submitted, so the eight slots typically sit on eight DIFFERENT origins
 * instead of hammering one.
 *
 * <p><b>The load-bearing contract:</b> the producer puts EXACTLY ONE element
 * per task, failure included (empty text). {@code total} is fixed up front and
 * the consumers take exactly {@code total} elements, so a hole cannot form
 * structurally - no consumer can ever block on an element that will never come.
 * The bounded queue ({@code 2 x} the producer width) is the throttle: once the
 * GPU falls behind, the producers block in {@code put()} and the fetch rate
 * settles on the model's tempo all by itself.
 *
 * <p>Run-scoped: one instance per DD run, {@link #close()}d in the caller's
 * {@code finally} - same lifetime and shutdown shape as {@code runTwoWide}.
 * The pool makes NO model calls and therefore never takes an
 * {@code LlmGate} permit.
 */
final class ArticleIntakeQueue implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ArticleIntakeQueue.class);

    /** Fetch producers running in parallel (user-set 2026-08-03). */
    static final int PRODUCER_WIDTH = 8;
    /** In-flight fetches allowed against ONE host. */
    static final int PER_HOST_CONCURRENCY = 2;
    /** Minimum distance between two fetch STARTS on the same host. */
    static final long PER_HOST_MIN_GAP_MS = 400;
    /** Bounded hand-over buffer - backpressure onto the producers is wanted. */
    static final int QUEUE_CAPACITY = 2 * PRODUCER_WIDTH;

    /** One read article on its way to an LLM lane; {@code text} may be empty. */
    record FetchedArticle(RawNewsItem item, String text) {}

    private final Function<String, String> fetcher;
    private final Runnable cancelCheck;
    private final BlockingQueue<FetchedArticle> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Map<String, Semaphore> hostGates = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStartNanos = new ConcurrentHashMap<>();
    private final AtomicInteger claimed = new AtomicInteger();
    private final AtomicInteger produced = new AtomicInteger();
    private volatile ExecutorService producers;
    private volatile int total;

    /**
     * @param fetcher   the ONE fetch seam - the caller hands in
     *                  {@code NewsDigester::articleTextNow} unchanged, so the
     *                  session text cache, the walled-host fast miss, the shell
     *                  detection and the {@code read-articles} opt-out all stay
     *                  in exactly one place.
     * @param cancelCheck the run's abort check, raised before every fetch and
     *                  in every consumer wait round.
     */
    ArticleIntakeQueue(Function<String, String> fetcher, Runnable cancelCheck) {
        this.fetcher = fetcher;
        this.cancelCheck = cancelCheck;
    }

    /**
     * Submits the whole work list to the producers, host-interleaved. Returns
     * the number of articles the consumers must take.
     */
    int start(List<RawNewsItem> items) {
        List<RawNewsItem> work = interleaveByHost(items);
        total = work.size();
        if (work.isEmpty()) return 0;
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(PRODUCER_WIDTH, work.size()),
                BackgroundThreads.factory("dd-intake"));
        producers = pool;
        for (RawNewsItem item : work) pool.submit(() -> fetchOne(item));
        pool.shutdown(); // no more submissions; running tasks finish
        return total;
    }

    /**
     * Claims the next fetched article for THIS consumer lane, waiting for a
     * producer if need be, or {@code null} when all {@code total} articles have
     * been handed out. Never a bare {@code take()}: every wait round is one
     * second long and re-raises the cancel check, so a dead producer can never
     * park an LLM lane.
     */
    FetchedArticle next() {
        while (true) {
            int c = claimed.get();
            if (c >= total) return null;
            if (claimed.compareAndSet(c, c + 1)) break;
        }
        while (true) {
            cancelCheck.run();
            FetchedArticle a;
            try {
                a = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelCheck.run();
                return null;
            }
            if (a != null) return a;
            // Backstop for the impossible case (a producer thread killed
            // between its catch and its finally): everything that will ever be
            // produced IS produced and the buffer is empty - nothing is coming.
            if (produced.get() >= total && queue.isEmpty()) return null;
        }
    }

    /** One article: cancel check, host gate, the caller's fetch seam, hand over. */
    private void fetchOne(RawNewsItem item) {
        String text = "";
        try {
            cancelCheck.run();
            String link = item.link() == null ? "" : item.link().trim();
            String host = hostKey(link);
            Semaphore gate = hostGates.computeIfAbsent(host, h -> new Semaphore(PER_HOST_CONCURRENCY));
            gate.acquire();
            try {
                awaitHostGap(host, gate);
                text = fetcher.apply(link);
            } finally {
                gate.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // A failed read costs the article its facts, never its seat and
            // never the queue's arithmetic - the element is put below anyway.
            LOG.debug("[DEEPDIVE] intake fetch failed for {}: {}", item.link(), t.toString());
        } finally {
            produced.incrementAndGet();
            put(new FetchedArticle(item, text == null ? "" : text));
        }
    }

    /** Blocks until this host's minimum start distance has passed. */
    private void awaitHostGap(String host, Object lock) throws InterruptedException {
        synchronized (lock) {
            Long last = lastStartNanos.get(host);
            if (last != null) {
                long waitMs = PER_HOST_MIN_GAP_MS
                        - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - last);
                if (waitMs > 0) Thread.sleep(waitMs);
            }
            lastStartNanos.put(host, System.nanoTime());
        }
    }

    /** The hand-over; an interrupt here means teardown, so the element is dropped. */
    private void put(FetchedArticle a) {
        try {
            queue.put(a);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queue.offer(a);
        }
    }

    @Override
    public void close() {
        ExecutorService pool = producers;
        if (pool != null) pool.shutdownNow(); // rips in-flight fetches on cancel
        queue.clear();
    }

    /* ---- pure helpers (unit-tested) ---- */

    /**
     * Round-robin by host: {@code [a,a,a,b,b,c]} becomes {@code [a,b,c,a,b,a]}.
     * Hosts keep their first-appearance order, and inside one host the items
     * keep theirs. This reorders the FETCH only - the result order is rebuilt
     * from the caller's original list.
     */
    static List<RawNewsItem> interleaveByHost(List<RawNewsItem> items) {
        Map<String, Deque<RawNewsItem>> byHost = new LinkedHashMap<>();
        if (items != null) {
            for (RawNewsItem item : items) {
                if (item == null || item.link() == null || item.link().isBlank()) continue;
                byHost.computeIfAbsent(hostKey(item.link().trim()), h -> new ArrayDeque<>()).add(item);
            }
        }
        List<RawNewsItem> out = new ArrayList<>();
        while (!byHost.isEmpty()) {
            byHost.values().removeIf(q -> {
                out.add(q.poll());
                return q.isEmpty();
            });
        }
        return out;
    }

    /**
     * Lowercased host of a link, {@code ""} when it doesn't parse - mirrors
     * {@code NewsDigester.hostOf} (private there; the digester stays untouched).
     */
    static String hostKey(String link) {
        if (link == null || link.isBlank()) return "";
        try {
            String host = java.net.URI.create(link.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }
}
