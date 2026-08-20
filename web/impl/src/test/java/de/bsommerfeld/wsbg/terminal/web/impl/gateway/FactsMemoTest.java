package de.bsommerfeld.wsbg.terminal.web.impl.gateway;

import de.bsommerfeld.wsbg.terminal.web.facts.InstrumentFacts;
import de.bsommerfeld.wsbg.terminal.web.facts.InstrumentFactsSource;
import de.bsommerfeld.wsbg.terminal.web.gateway.SourceOutcome;
import de.bsommerfeld.wsbg.terminal.web.impl.exec.WebExecutor;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slow-block memo: profile/analyst/register blocks change daily to
 * quarterly, so a repeat graze inside the memo window answers from RAM with
 * an honest "cached" note. Failures never memo (self-healing).
 */
class FactsMemoTest {

    private final WebExecutor executor = new WebExecutor();

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private static final ResolvedInstrument SAP = new ResolvedInstrument(
            Isin.parse("DE0007164600"), Optional.empty(), "SAP SE");

    @Test
    void slowBlocksAnswerFromTheMemoOnRepeat() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InstrumentFactsSource profile = new InstrumentFactsSource() {
            @Override
            public Optional<InstrumentFacts> factsByIsin(String isin) {
                calls.incrementAndGet();
                return Optional.empty(); // an EMPTY register answer is a finding
            }
        };
        FactsPipeline pipeline = new FactsPipeline(new FactsSources(
                null, null, null, profile, null, null, null, null, null, null), executor);

        FactsPipeline.Graze first = pipeline.graze(SAP).get();
        assertEquals(1, calls.get());
        FactsPipeline.Graze second = pipeline.graze(SAP).get();
        assertEquals(1, calls.get(), "the memo answers the repeat graze");

        SourceOutcome cached = second.outcomes().stream()
                .filter(o -> o.sourceName().equals("profile")).findFirst().orElseThrow();
        assertEquals(SourceOutcome.Status.EMPTY, cached.status());
        assertEquals("cached", cached.note(), "the memo hit is honest about itself");
        assertTrue(first.outcomes().stream()
                .anyMatch(o -> o.sourceName().equals("profile") && o.note().isEmpty()));
    }

    @Test
    void failuresNeverMemo() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InstrumentFactsSource broken = new InstrumentFactsSource() {
            @Override
            public Optional<InstrumentFacts> factsByIsin(String isin) {
                calls.incrementAndGet();
                throw new IllegalStateException("outage");
            }
        };
        FactsPipeline pipeline = new FactsPipeline(new FactsSources(
                null, null, null, broken, null, null, null, null, null, null), executor);

        pipeline.graze(SAP).get();
        pipeline.graze(SAP).get();
        assertEquals(2, calls.get(), "an outage retries on the next inquiry");
    }
}
