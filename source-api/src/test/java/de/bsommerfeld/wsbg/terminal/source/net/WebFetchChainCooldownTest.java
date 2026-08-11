package de.bsommerfeld.wsbg.terminal.source.net;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The per-host rate-limit cooldown: a host whose EVERY strategy answers 429
 * goes on cooldown — subsequent fetches fail fast with a synthetic 429 and
 * touch NO transport (live 2026-07-14: StockTitan 429s hammered browser AND
 * direct per wire unit). A definitive answer clears the host.
 */
class WebFetchChainCooldownTest {

    @org.junit.jupiter.api.BeforeEach
    void forgetEveryHost() {
        // Both memories are JVM-wide by design; a test must not inherit one.
        WebFetchChain.forgetAll();
    }

    private static WebFetcher answering(int status, AtomicInteger calls) {
        return new WebFetcher() {
            @Override
            public String name() {
                return "stub-" + status;
            }

            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
                calls.incrementAndGet();
                return new WebResponse(status, "", Map.of());
            }
        };
    }

    @Test
    void chainWide429TripsTheHostCooldownAndFailsFast() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WebFetchChain chain = new WebFetchChain(List.of(answering(429, calls)));
        String url = "https://cooldown-test-host-a.invalid/feed/AAA";

        assertEquals(429, chain.fetch(url, Map.of(), Duration.ofSeconds(1)).status());
        int callsAfterFirst = calls.get();
        // Same host, different path: the cooldown answers, the transport rests.
        assertEquals(429, chain.fetch("https://cooldown-test-host-a.invalid/feed/BBB",
                Map.of(), Duration.ofSeconds(1)).status());
        assertEquals(callsAfterFirst, calls.get());
    }

    @Test
    void otherHostsAreUntouchedByACooldown() throws Exception {
        AtomicInteger limited = new AtomicInteger();
        AtomicInteger healthy = new AtomicInteger();
        new WebFetchChain(List.of(answering(429, limited)))
                .fetch("https://cooldown-test-host-b.invalid/x", Map.of(), Duration.ofSeconds(1));
        WebFetchChain ok = new WebFetchChain(List.of(answering(200, healthy)));
        assertEquals(200, ok.fetch("https://cooldown-test-host-c.invalid/x",
                Map.of(), Duration.ofSeconds(1)).status());
        assertEquals(1, healthy.get());
    }

    /** A transport that threw — the browser leg's page-side fetch() timing out. */
    private static WebFetcher throwing(AtomicInteger calls) {
        return new WebFetcher() {
            @Override
            public String name() {
                return "stub-throwing";
            }

            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout)
                    throws Exception {
                calls.incrementAndGet();
                throw new java.util.concurrent.TimeoutException(); // message: null, as in the live log
            }
        };
    }

    @Test
    void aSilentStrategyDoesNotSpeakForTheHost() throws Exception {
        // Live 2026-08-09: browser hung (no status), direct answered 429 — and
        // the whole Yahoo host went on an exponential cooldown regardless.
        AtomicInteger browser = new AtomicInteger();
        AtomicInteger direct = new AtomicInteger();
        WebFetchChain chain = new WebFetchChain(
                List.of(throwing(browser), answering(429, direct)));
        String url = "https://silent-leg-host.invalid/v8/chart";

        assertEquals(429, chain.fetch(url, Map.of(), Duration.ofSeconds(1)).status(),
                "the caller still sees the real answer of the leg that DID speak");
        assertEquals(429, chain.fetch(url, Map.of(), Duration.ofSeconds(1)).status());
        assertEquals(2, browser.get(), "no cooldown — the browser is asked again");
        assertEquals(2, direct.get());
    }

    @Test
    void aMessagelessFailureStillNamesItself() {
        // The browser leg's reply channel times out with no message at all; the
        // log used to print a bare "null".
        assertEquals("TimeoutException",
                WebFetchChain.describe(new java.util.concurrent.TimeoutException()));
        assertEquals("IllegalStateException: session not ready",
                WebFetchChain.describe(new IllegalStateException("session not ready")));
    }

    @Test
    void aStrategyThatStaysSilentTwiceIsSkippedOnThatHost() throws Exception {
        // Live 2026-08-11: the direct leg ran into its 20 s timeout on the St.
        // Louis Fed for all FOURTEEN macro series in a row while the browser leg
        // answered each one in 100-300 ms. One timeout is a blip, two are a wall.
        AtomicInteger dead = new AtomicInteger();
        AtomicInteger alive = new AtomicInteger();
        WebFetchChain chain = new WebFetchChain(List.of(throwing(dead), answering(200, alive)));
        String host = "https://silent-strategy-host.invalid/";

        for (int i = 0; i < 4; i++) {
            assertEquals(200, chain.fetch(host + "series/" + i, Map.of(),
                    Duration.ofSeconds(1)).status());
        }
        assertEquals(2, dead.get(), "muted after the second strike — series 3 and 4 skip it");
        assertEquals(4, alive.get(), "the leg that answers is asked every single time");
    }

    @Test
    void aStrategyThatAnswersAgainIsHeardAgain() throws Exception {
        // The strike count is about a WALL, not about a bad afternoon: a single
        // failure between two answers must never add up to a mute.
        AtomicInteger flaky = new AtomicInteger();
        WebFetcher sometimes = new WebFetcher() {
            @Override
            public String name() {
                return "stub-flaky";
            }

            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout)
                    throws Exception {
                if (flaky.incrementAndGet() % 2 == 0) throw new java.io.IOException("blip");
                return new WebResponse(200, "", Map.of());
            }
        };
        WebFetchChain chain = new WebFetchChain(List.of(sometimes, answering(200,
                new AtomicInteger())));
        for (int i = 0; i < 6; i++) {
            chain.fetch("https://flaky-strategy-host.invalid/" + i, Map.of(),
                    Duration.ofSeconds(1));
        }
        assertEquals(6, flaky.get(), "never muted — every second call answered");
    }

    @Test
    void theMemoryNeverEmptiesTheChain() throws Exception {
        // A source may lose speed to this memory, never its function: when every
        // strategy has gone silent, they are all tried again rather than none.
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        WebFetchChain chain = new WebFetchChain(List.of(throwing(first), throwing(second)));
        for (int i = 0; i < 3; i++) {
            try {
                chain.fetch("https://all-silent-host.invalid/" + i, Map.of(),
                        Duration.ofSeconds(1));
            } catch (Exception expected) {
                // Every strategy threw — the chain hands the last error up.
            }
        }
        assertEquals(3, first.get());
        assertEquals(3, second.get());
    }

    @Test
    void a422StopsTheChainInsteadOfEscalatingToTheJoker() throws Exception {
        // A semantic rejection of the parameters is 400's twin: the browser gets
        // the identical answer, so escalating only buys its readiness wait.
        AtomicInteger direct = new AtomicInteger();
        AtomicInteger joker = new AtomicInteger();
        WebFetchChain chain = new WebFetchChain(
                List.of(answering(422, direct), answering(200, joker)));
        assertEquals(422, chain.fetch("https://definitive-422-host.invalid/x",
                Map.of(), Duration.ofSeconds(1)).status());
        assertEquals(1, direct.get());
        assertEquals(0, joker.get(), "the second transport is never asked");
    }
}
