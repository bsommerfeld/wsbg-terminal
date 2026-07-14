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
}
