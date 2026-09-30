package de.bsommerfeld.tinysearch.api;

import de.bsommerfeld.tinyfetch.api.CooldownException;
import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.api.FetchResponse;
import de.bsommerfeld.tinyfetch.api.Fetcher;
import de.bsommerfeld.tinyfetch.api.HostPolicy;
import de.bsommerfeld.tinyfetch.api.Wall;
import de.bsommerfeld.tinysearch.api.EngineReport.Outcome;
import de.bsommerfeld.tinysearch.page.PageHit;
import de.bsommerfeld.tinysearch.page.ResultPage;
import de.bsommerfeld.tinysearch.page.UnreadablePageException;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Searches the web on several {@linkplain SearchEngine search engines} at
 * once and merges what they found into one list.
 *
 * <h2>Traffic</h2>
 * Everything goes out through the {@link Fetcher} it is given - in
 * production a {@code TinyFetch}, so every search is the user's own browser
 * asking the engine's own search page, at a person's pace, stopping at the
 * first sign of refusal. Give that client {@link #hostPolicies()}. A search
 * is one request per engine asked, all engines at the same time; this
 * client never pages further, retries or searches on its own.
 *
 * <h2>When an engine does not answer</h2>
 * Every engine asked gets an {@link EngineReport}; the search itself only
 * fails if the calling thread is interrupted. A wall pauses the engine's
 * host in TinyFetch. An answer that is not a result page - a check
 * TinyFetch does not know as a wall - leaves the engine alone for
 * {@link Builder#demotion}, as TinyFetch does after a challenge.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TinyFetch.Builder http = TinyFetch.builder().engine(browserEngine);
 * TinySearch.hostPolicies().forEach(http::policy);
 *
 * try (TinyFetch fetch = http.build()) {
 *     TinySearch search = TinySearch.builder(fetch).build();
 *
 *     SearchResults results = search.search("SAP Aktie", SearchEngine.BRAVE, SearchEngine.STARTPAGE);
 *     for (SearchHit hit : results.hits()) {
 *         System.out.println(hit.url() + " " + hit.title() + " " + hit.engines());
 *     }
 *     results.reports().values().stream()
 *             .filter(report -> report.outcome() != EngineReport.Outcome.ANSWERED)
 *             .forEach(report -> log(report.engine() + ": " + report.detail()));
 * }
 * }</pre>
 */
public final class TinySearch {

    private final Fetcher fetcher;
    private final Duration demotion;
    private final Supplier<Instant> clock;
    private final Map<SearchEngine, Instant> demotedUntil = new ConcurrentHashMap<>();

    private TinySearch(Fetcher fetcher, Duration demotion, Supplier<Instant> clock) {
        this.fetcher = fetcher;
        this.demotion = demotion;
        this.clock = clock;
    }

    public static Builder builder(Fetcher fetcher) {
        return new Builder(fetcher);
    }

    /**
     * The pace the engines are to be asked at - hand these to the
     * {@code TinyFetch} builder. Every engine: one search every 5 s at most,
     * plus jitter - a person does not search the same engine faster.
     */
    public static Map<String, HostPolicy> hostPolicies() {
        HostPolicy person = HostPolicy.defaults().withMinInterval(Duration.ofSeconds(5));
        return EnumSet.allOf(SearchEngine.class).stream()
                .collect(Collectors.toUnmodifiableMap(SearchEngine::host, engine -> person));
    }

    /** Searches every engine. */
    public SearchResults search(String query) throws InterruptedException {
        return search(query, EnumSet.allOf(SearchEngine.class));
    }

    /** Searches the engines named. */
    public SearchResults search(String query, SearchEngine engine, SearchEngine... more) throws InterruptedException {
        return search(query, EnumSet.of(engine, more));
    }

    /** Searches the engines in {@code engines}; at least one. */
    public SearchResults search(String query, Set<SearchEngine> engines) throws InterruptedException {
        Objects.requireNonNull(query, "query");
        String stripped = query.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("empty query");
        }
        if (engines.isEmpty()) {
            throw new IllegalArgumentException("no engine to search");
        }

        Map<SearchEngine, Asked> asked = new EnumMap<>(SearchEngine.class);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<SearchEngine, Future<Asked>> pending = new EnumMap<>(SearchEngine.class);
            for (SearchEngine engine : EnumSet.copyOf(engines)) {
                pending.put(engine, threads.submit(() -> ask(engine, stripped)));
            }
            try {
                for (Map.Entry<SearchEngine, Future<Asked>> entry : pending.entrySet()) {
                    asked.put(entry.getKey(), entry.getValue().get());
                }
            } catch (InterruptedException e) {
                threads.shutdownNow();
                throw e;
            } catch (ExecutionException e) {
                throw new IllegalStateException("searching " + stripped + " failed", e.getCause());
            }
        }

        Map<SearchEngine, List<PageHit>> pages = new EnumMap<>(SearchEngine.class);
        Map<SearchEngine, EngineReport> reports = new EnumMap<>(SearchEngine.class);
        asked.forEach((engine, answer) -> {
            pages.put(engine, answer.hits());
            reports.put(engine, answer.report());
        });
        return new SearchResults(stripped, Fusion.merge(pages), reports);
    }

    /** When {@code engine} will be asked again, if it is left alone after an unreadable page. */
    public Optional<Instant> demotedUntil(SearchEngine engine) {
        Instant until = demotedUntil.get(engine);
        return until != null && clock.get().isBefore(until) ? Optional.of(until) : Optional.empty();
    }

    // ---- one engine ---------------------------------------------------------

    private record Asked(EngineReport report, List<PageHit> hits) {

        static Asked nothing(SearchEngine engine, Outcome outcome, String detail) {
            return new Asked(new EngineReport(engine, outcome, 0, detail), List.of());
        }
    }

    private Asked ask(SearchEngine engine, String query) throws InterruptedException {
        Optional<Instant> leftAlone = demotedUntil(engine);
        if (leftAlone.isPresent()) {
            return Asked.nothing(engine, Outcome.PAUSED,
                    "left alone until " + leftAlone.get() + " after an unreadable page");
        }
        ResultPage page = pageOf(engine);
        FetchResponse response;
        try {
            response = fetcher.fetch(FetchRequest.of(page.address(query)));
        } catch (CooldownException paused) {
            return Asked.nothing(engine, Outcome.PAUSED,
                    "host paused after " + paused.reason() + " until " + paused.until());
        } catch (FetchException failed) {
            return Asked.nothing(engine, Outcome.FAILED, failed.getMessage());
        }
        if (response.wall() != Wall.NONE) {
            return Asked.nothing(engine, Outcome.REFUSED, "HTTP " + response.status() + " " + response.wall());
        }
        if (!response.ok()) {
            return Asked.nothing(engine, Outcome.FAILED, "HTTP " + response.status());
        }
        try {
            List<PageHit> hits = page.read(response.text(), response.url().toString());
            demotedUntil.remove(engine);
            return new Asked(new EngineReport(engine, Outcome.ANSWERED, hits.size(), ""), hits);
        } catch (UnreadablePageException unreadable) {
            Instant until = clock.get().plus(demotion);
            demotedUntil.put(engine, until);
            return Asked.nothing(engine, Outcome.UNREADABLE,
                    unreadable.getMessage() + " - left alone until " + until);
        }
    }

    private static ResultPage pageOf(SearchEngine engine) {
        return switch (engine) {
            case BING -> ResultPage.BING;
            case BRAVE -> ResultPage.BRAVE;
            case DUCKDUCKGO -> ResultPage.DUCKDUCKGO;
            case ECOSIA -> ResultPage.ECOSIA;
            case STARTPAGE -> ResultPage.STARTPAGE;
            case YAHOO -> ResultPage.YAHOO;
            case YANDEX -> ResultPage.YANDEX;
        };
    }

    // ---- builder ------------------------------------------------------------

    /** Configures a {@link TinySearch}. */
    public static final class Builder {

        private final Fetcher fetcher;
        private Duration demotion = Duration.ofMinutes(30);
        private Supplier<Instant> clock = Instant::now;

        private Builder(Fetcher fetcher) {
            this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        }

        /**
         * How long an engine is left alone after an answer that was not its
         * result page; 30 minutes unless set - TinyFetch's first pause after
         * a challenge.
         */
        public Builder demotion(Duration demotion) {
            if (demotion.isNegative()) {
                throw new IllegalArgumentException("demotion must not be negative");
            }
            this.demotion = demotion;
            return this;
        }

        /** The clock for demotion; for tests. */
        public Builder clock(Supplier<Instant> clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public TinySearch build() {
            return new TinySearch(fetcher, demotion, clock);
        }
    }
}
