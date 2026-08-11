package de.bsommerfeld.wsbg.terminal.web.impl.gateway;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.gateway.InquiryResult;
import de.bsommerfeld.wsbg.terminal.web.gateway.SourceOutcome;
import de.bsommerfeld.wsbg.terminal.web.impl.exec.WebExecutor;
import de.bsommerfeld.wsbg.terminal.web.impl.pool.InMemoryArticlePool;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fan ledger: a repeat inquiry inside the freshness window answers from
 * the basin — clean sources (even empty ones) are not asked again, failed
 * ones are.
 */
class FanLedgerTest {

    private final WebExecutor executor = new WebExecutor();
    private final InMemoryArticlePool pool = new InMemoryArticlePool();

    private final AtomicInteger delivering = new AtomicInteger();
    private final AtomicInteger empty = new AtomicInteger();
    private final AtomicInteger failing = new AtomicInteger();

    private InstrumentSource source(String name, AtomicInteger counter,
            java.util.function.Supplier<List<Article>> answer) {
        return new InstrumentSource() {
            @Override
            public String sourceName() {
                return name;
            }

            @Override
            public FetchUtil[] mode() {
                return new FetchUtil[] {FetchUtil.DIRECT};
            }

            @Override
            public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
                counter.incrementAndGet();
                return answer.get();
            }
        };
    }

    private HouseWebGateway gateway() {
        InstrumentSource delivers = source("delivers", delivering, () -> List.of(
                new Article("a1", "Apple beats", "P", "https://x.example/a1",
                        Instant.now(), List.of())));
        InstrumentSource silent = source("silent", empty, List::of);
        InstrumentSource broken = source("broken", failing, () -> {
            throw new IllegalStateException("wall");
        });
        return new HouseWebGateway(ResolvedInstrument::ofName, pool,
                Set.of(delivers, silent, broken), Set.of(), Set.of(),
                new FactsPipeline(FactsSources.NONE, executor), executor);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void repeatInquiryInsideTheWindowSkipsCleanSourcesButRetriesFailed() throws Exception {
        HouseWebGateway gateway = gateway();

        InquiryResult first = gateway.searchInstrument("Apple").get();
        assertEquals(1, delivering.get());
        assertEquals(1, empty.get());
        assertEquals(1, failing.get());
        assertEquals(1, first.articles().size());

        InquiryResult second = gateway.searchInstrument("Apple").get();
        assertEquals(1, delivering.get(), "clean source answers from the basin");
        assertEquals(1, empty.get(), "an EMPTY answer stamps the ledger too");
        assertEquals(2, failing.get(), "a failed source retries");
        assertEquals(1, second.articles().size(), "the yield still comes out of the basin");

        List<String> skipped = second.outcomes().stream()
                .filter(o -> o.status() == SourceOutcome.Status.SKIPPED)
                .map(SourceOutcome::sourceName).toList();
        assertTrue(skipped.containsAll(List.of("delivers", "silent")),
                "skips are visible in the outcomes: " + skipped);
    }
}
