package de.bsommerfeld.wsbg.terminal.web.impl.gateway;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.web.facts.AnalystActions;
import de.bsommerfeld.wsbg.terminal.web.facts.AnalystView;
import de.bsommerfeld.wsbg.terminal.web.facts.HedgeFundPopularity;
import de.bsommerfeld.wsbg.terminal.web.facts.InsiderDealings;
import de.bsommerfeld.wsbg.terminal.web.facts.InstrumentFacts;
import de.bsommerfeld.wsbg.terminal.web.facts.MarketFacts;
import de.bsommerfeld.wsbg.terminal.web.facts.OrderBookSnapshot;
import de.bsommerfeld.wsbg.terminal.web.facts.PriceRef;
import de.bsommerfeld.wsbg.terminal.web.facts.ShortInterest;
import de.bsommerfeld.wsbg.terminal.web.facts.UsListingStats;
import de.bsommerfeld.wsbg.terminal.web.facts.VenueStats;
import de.bsommerfeld.wsbg.terminal.web.gateway.SourceOutcome;
import de.bsommerfeld.wsbg.terminal.web.impl.exec.WebExecutor;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pipeline 3: the facts graze. Every roster slot that exists AND has a key it
 * can address runs as its own parallel probe on the house executor; the
 * bundle completes when the slowest probe is in. A probe that fails becomes
 * an outcome line, never an exception up the stack — partial facts are the
 * norm, and the caller sees exactly which block is missing and why.
 *
 * <p>The SLOW blocks (company profile, analyst views/actions, short/insider
 * registers, 13F, US listing stats) carry a RAM memo: they change daily to
 * quarterly, so re-grazing them per inquiry was the actual waste. Live blocks
 * (price, order book, venue stats) are deliberately NOT memoized — decided
 * open for now (user 2026-08-12). Clean answers memo (an EMPTY register hit
 * is a finding); failures never do, so an outage self-heals on the next
 * inquiry.
 */
@Singleton
public final class FactsPipeline {

    /** One grazed bundle plus the per-block honesty lines. */
    public record Graze(MarketFacts facts, List<SourceOutcome> outcomes) {
    }

    /** Registers/analyst desks move daily — half a day of memo is honest. */
    static final java.time.Duration SLOW_TTL = java.time.Duration.ofHours(12);
    /** Company profile and 13F curves move quarterly — a day is generous. */
    static final java.time.Duration GLACIAL_TTL = java.time.Duration.ofHours(24);

    private record Memo(Optional<?> value, java.time.Instant at) {
    }

    private final java.util.concurrent.ConcurrentHashMap<String, Memo> slowMemo =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final FactsSources sources;
    private final WebExecutor executor;

    @Inject
    public FactsPipeline(FactsSources sources, WebExecutor executor) {
        this.sources = sources;
        this.executor = executor;
    }

    public CompletableFuture<Graze> graze(ResolvedInstrument instrument) {
        String isin = instrument.isin().map(i -> i.value()).orElse(null);
        String symbol = instrument.ticker().map(t -> t.value()).orElse(null);
        String baseSymbol = instrument.ticker().map(t -> t.baseSymbol()).orElse(null);

        List<SourceOutcome> outcomes = new CopyOnWriteArrayList<>();
        List<CompletableFuture<?>> probes = new ArrayList<>();

        CompletableFuture<Optional<MarketSnapshot>> price = probe("price", outcomes, probes,
                sources.price() == null ? null
                        : () -> sources.price().snapshot(
                                new PriceRef(instrument.name(), symbol, isin)));
        CompletableFuture<Optional<VenueStats>> venueStats = isinProbe("venue-stats", isin,
                outcomes, probes,
                sources.venueStats() == null ? null : () -> sources.venueStats().statsByIsin(isin));
        CompletableFuture<Optional<OrderBookSnapshot>> orderBook = isinProbe("order-book", isin,
                outcomes, probes,
                sources.orderBook() == null ? null : () -> sources.orderBook().orderBookByIsin(isin));
        CompletableFuture<Optional<InstrumentFacts>> profile = memoized("profile", isin,
                GLACIAL_TTL, outcomes,
                () -> isinProbe("profile", isin, outcomes, probes,
                        sources.profile() == null ? null : () -> sources.profile().factsByIsin(isin)));
        CompletableFuture<Optional<AnalystView>> analystView = memoized("analyst-view", isin,
                SLOW_TTL, outcomes,
                () -> isinProbe("analyst-view", isin, outcomes, probes,
                        sources.analystView() == null ? null
                                : () -> sources.analystView().viewByIsin(isin)));
        CompletableFuture<Optional<AnalystActions>> analystActions = memoized("analyst-actions",
                symbol, SLOW_TTL, outcomes,
                () -> symbolProbe("analyst-actions", symbol, outcomes, probes,
                        sources.analystActions() == null ? null
                                : () -> sources.analystActions().actionsFor(symbol)));
        CompletableFuture<Optional<ShortInterest>> shortInterest = memoized("short-interest", isin,
                SLOW_TTL, outcomes,
                () -> isinProbe("short-interest", isin, outcomes, probes,
                        sources.shortInterest() == null ? null
                                : () -> sources.shortInterest().byIsin(isin)));
        CompletableFuture<Optional<InsiderDealings>> insiderDealings = memoized("insider-dealings",
                isin, SLOW_TTL, outcomes,
                () -> isinProbe("insider-dealings", isin, outcomes, probes,
                        sources.insiderDealings() == null ? null
                                : () -> sources.insiderDealings().byIsin(isin)));
        CompletableFuture<Optional<HedgeFundPopularity>> hedgeFunds = memoized("hedge-funds",
                baseSymbol, GLACIAL_TTL, outcomes,
                () -> symbolProbe("hedge-funds", baseSymbol, outcomes, probes,
                        sources.hedgeFunds() == null ? null
                                : () -> sources.hedgeFunds().popularityFor(baseSymbol)));
        CompletableFuture<Optional<UsListingStats>> usListing = memoized("us-listing",
                baseSymbol, SLOW_TTL, outcomes,
                () -> symbolProbe("us-listing", baseSymbol, outcomes, probes,
                        sources.usListing() == null ? null
                                : () -> sources.usListing().statsFor(baseSymbol)));

        return CompletableFuture.allOf(probes.toArray(CompletableFuture[]::new))
                .thenApply(v -> new Graze(new MarketFacts(
                        price.join(), venueStats.join(), orderBook.join(), profile.join(),
                        analystView.join(), analystActions.join(), shortInterest.join(),
                        insiderDealings.join(), hedgeFunds.join(), usListing.join()),
                        List.copyOf(outcomes)));
    }


    /**
     * The slow-block memo: a fresh clean answer (present OR empty) is served
     * from RAM with a "cached" note; otherwise the real probe runs and its
     * clean result is stamped. Failures pass through unstamped. {@code key}
     * null (no ISIN/symbol) delegates straight to the probe's SKIP handling.
     */
    private <T> CompletableFuture<Optional<T>> memoized(String name, String key,
            java.time.Duration ttl, List<SourceOutcome> outcomes,
            java.util.function.Supplier<CompletableFuture<Optional<T>>> probe) {
        if (key == null) return probe.get();
        purgeMemo();
        String memoKey = name + '|' + key;
        Memo memo = slowMemo.get(memoKey);
        if (memo != null && memo.at().plus(ttl).isAfter(java.time.Instant.now())) {
            @SuppressWarnings("unchecked")
            Optional<T> value = (Optional<T>) memo.value();
            outcomes.add(value.isPresent()
                    ? new SourceOutcome(name, SourceOutcome.Status.DELIVERED, 1, "cached")
                    : new SourceOutcome(name, SourceOutcome.Status.EMPTY, 0, "cached"));
            return CompletableFuture.completedFuture(value);
        }
        cleanSink.set(v -> slowMemo.put(memoKey, new Memo(v, java.time.Instant.now())));
        try {
            return probe.get();
        } finally {
            cleanSink.remove();
        }
    }

    private void purgeMemo() {
        java.time.Instant cutoff = java.time.Instant.now().minus(GLACIAL_TTL);
        slowMemo.values().removeIf(m -> m.at().isBefore(cutoff));
    }

    /** The current probe's clean-result sink — set only inside memoized(). */
    private final ThreadLocal<java.util.function.Consumer<Optional<?>>> cleanSink =
            new ThreadLocal<>();

    /** A probe that needs an ISIN; without one it is SKIPPED, not asked. */
    private <T> CompletableFuture<Optional<T>> isinProbe(String name, String isin,
            List<SourceOutcome> outcomes, List<CompletableFuture<?>> probes,
            Callable<Optional<T>> call) {
        if (call != null && isin == null) {
            outcomes.add(SourceOutcome.skipped(name, "no ISIN on instrument"));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return probe(name, outcomes, probes, call);
    }

    /** A probe that needs a symbol; without one it is SKIPPED, not asked. */
    private <T> CompletableFuture<Optional<T>> symbolProbe(String name, String symbol,
            List<SourceOutcome> outcomes, List<CompletableFuture<?>> probes,
            Callable<Optional<T>> call) {
        if (call != null && symbol == null) {
            outcomes.add(SourceOutcome.skipped(name, "no symbol on instrument"));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return probe(name, outcomes, probes, call);
    }

    private <T> CompletableFuture<Optional<T>> probe(String name,
            List<SourceOutcome> outcomes, List<CompletableFuture<?>> probes,
            Callable<Optional<T>> call) {
        if (call == null) {
            outcomes.add(SourceOutcome.skipped(name, "no source wired"));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // Captured HERE (the memoized() frame), consumed on the worker later.
        java.util.function.Consumer<Optional<?>> onClean = cleanSink.get();
        CompletableFuture<Optional<T>> future = executor.supply(call)
                .handle((result, error) -> {
                    if (error != null) {
                        outcomes.add(SourceOutcome.failed(name, error.toString()));
                        return Optional.<T>empty();
                    }
                    Optional<T> value = result == null ? Optional.empty() : result;
                    outcomes.add(value.isPresent()
                            ? SourceOutcome.delivered(name, 1)
                            : SourceOutcome.empty(name));
                    if (onClean != null) onClean.accept(value);
                    return value;
                });
        probes.add(future);
        return future;
    }
}
