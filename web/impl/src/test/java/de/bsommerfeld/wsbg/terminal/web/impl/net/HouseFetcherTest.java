package de.bsommerfeld.wsbg.terminal.web.impl.net;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.Transport;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseFetcherTest {

    /** A scripted transport: answers from a queue, records what it was asked. */
    private static final class FakeTransport implements Transport {
        final FetchUtil util;
        final List<String> asked = new ArrayList<>();
        final List<Object> answers = new ArrayList<>();

        FakeTransport(FetchUtil util) {
            this.util = util;
        }

        FakeTransport answer(WebResponse r) {
            answers.add(r);
            return this;
        }

        FakeTransport failWith(Exception e) {
            answers.add(e);
            return this;
        }

        @Override
        public FetchUtil util() {
            return util;
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout)
                throws Exception {
            asked.add(url);
            Object next = answers.isEmpty() ? WebResponse.text(200, "ok", Map.of())
                    : answers.remove(0);
            if (next instanceof Exception e) throw e;
            return (WebResponse) next;
        }
    }

    private static final Duration T = Duration.ofSeconds(5);

    @Test
    void modeOrderIsTheFallbackOrder() throws Exception {
        FakeTransport direct = new FakeTransport(FetchUtil.DIRECT)
                .answer(WebResponse.text(403, "wall", Map.of()));
        FakeTransport browser = new FakeTransport(FetchUtil.BROWSER)
                .answer(WebResponse.text(200, "rescued", Map.of()));
        HouseFetcher fetcher = new HouseFetcher(Set.of(direct, browser));

        WebResponse r = fetcher.fetch("https://wall.example/x", Map.of(), T,
                FetchUtil.DIRECT, FetchUtil.BROWSER);
        assertEquals(200, r.status());
        assertEquals("rescued", r.body());
        assertEquals(1, direct.asked.size());
        assertEquals(1, browser.asked.size());
    }

    @Test
    void definitiveAnswerStopsTheChain() throws Exception {
        FakeTransport direct = new FakeTransport(FetchUtil.DIRECT)
                .answer(WebResponse.text(404, "", Map.of()));
        FakeTransport browser = new FakeTransport(FetchUtil.BROWSER);
        HouseFetcher fetcher = new HouseFetcher(Set.of(direct, browser));

        WebResponse r = fetcher.fetch("https://miss.example/x", Map.of(), T,
                FetchUtil.DIRECT, FetchUtil.BROWSER);
        assertEquals(404, r.status());
        assertTrue(browser.asked.isEmpty(), "definitive 404 must not escalate to the browser");
    }

    @Test
    void unregisteredModeIsSkipped() throws Exception {
        FakeTransport direct = new FakeTransport(FetchUtil.DIRECT)
                .answer(WebResponse.text(200, "ok", Map.of()));
        HouseFetcher fetcher = new HouseFetcher(Set.of(direct));

        WebResponse r = fetcher.fetch("https://x.example/", Map.of(), T,
                FetchUtil.BROWSER, FetchUtil.DIRECT);
        assertEquals(200, r.status());
    }

    @Test
    void chainWide429TripsTheHostCooldown() throws Exception {
        FakeTransport direct = new FakeTransport(FetchUtil.DIRECT)
                .answer(WebResponse.text(429, "", Map.of()));
        HouseFetcher fetcher = new HouseFetcher(Set.of(direct));

        assertFalse(fetcher.hostCoolingDown("https://limited.example/a"));
        fetcher.fetch("https://limited.example/a", Map.of(), T, FetchUtil.DIRECT);
        assertTrue(fetcher.hostCoolingDown("https://limited.example/b"));

        // While cooling, no socket is touched: synthetic 429, transport not asked.
        WebResponse r = fetcher.fetch("https://limited.example/c", Map.of(), T, FetchUtil.DIRECT);
        assertEquals(429, r.status());
        assertEquals(1, direct.asked.size());
    }

    @Test
    void aThrownTransportNeverSpeaksForTheHost() throws Exception {
        // Browser hangs (throws), direct answers 429 — NOT a chain-wide 429.
        FakeTransport browser = new FakeTransport(FetchUtil.BROWSER)
                .failWith(new java.util.concurrent.TimeoutException());
        FakeTransport direct = new FakeTransport(FetchUtil.DIRECT)
                .answer(WebResponse.text(429, "", Map.of()));
        HouseFetcher fetcher = new HouseFetcher(Set.of(browser, direct));

        WebResponse r = fetcher.fetch("https://y.example/", Map.of(), T,
                FetchUtil.BROWSER, FetchUtil.DIRECT);
        assertEquals(429, r.status());
        assertFalse(fetcher.hostCoolingDown("https://y.example/"),
                "a silent transport must never put the host on cooldown");
    }

    @Test
    void chainWide403TripsTheHostCooldownToo() throws Exception {
        // A bot wall answers 403 and never 429. Before this counted, the house
        // kept knocking at full cadence for as long as it ran.
        FakeTransport direct = new FakeTransport(FetchUtil.DIRECT)
                .answer(WebResponse.text(403, "", Map.of()));
        HouseFetcher fetcher = new HouseFetcher(Set.of(direct));

        fetcher.fetch("https://walled.example/a", Map.of(), T, FetchUtil.DIRECT);
        assertTrue(fetcher.hostCoolingDown("https://walled.example/b"));
    }

    @Test
    void aReportedWallCoolsTheHostDown() throws Exception {
        // The 200-shaped challenge: only the source can tell, so it says so.
        FakeTransport direct = new FakeTransport(FetchUtil.DIRECT)
                .answer(WebResponse.text(200, "<html>are you a robot</html>", Map.of()));
        HouseFetcher fetcher = new HouseFetcher(Set.of(direct));

        WebResponse r = fetcher.fetch("https://sneaky.example/feed", Map.of(), T, FetchUtil.DIRECT);
        assertEquals(200, r.status());
        assertFalse(fetcher.hostCoolingDown("https://sneaky.example/feed"),
                "a healthy status alone tells the fetcher nothing");

        fetcher.reportWall("https://sneaky.example/feed");
        assertTrue(fetcher.hostCoolingDown("https://sneaky.example/other"));
    }

    @Test
    void retryAfterAsAnHttpDateIsHonoured() throws Exception {
        // "come back in an hour" used to collapse onto the 120 s base backoff.
        String inAnHour = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusHours(1));
        FakeTransport direct = new FakeTransport(FetchUtil.DIRECT)
                .answer(WebResponse.text(429, "", Map.of("Retry-After", inAnHour)));
        HouseFetcher fetcher = new HouseFetcher(Set.of(direct));

        fetcher.fetch("https://patient.example/a", Map.of(), T, FetchUtil.DIRECT);
        assertTrue(fetcher.hostCoolingDown("https://patient.example/a"));
        // The base backoff is 120 s; a date-form header must outlast it by far.
        assertTrue(fetcher.cooldownLeftMs("https://patient.example/a") > 600_000L,
                "the HTTP-date form must not collapse onto the base backoff");
    }

    @Test
    void noModesMeansTheHouseDefaultOrder() throws Exception {
        FakeTransport direct = new FakeTransport(FetchUtil.DIRECT)
                .answer(WebResponse.text(200, "default", Map.of()));
        HouseFetcher fetcher = new HouseFetcher(Set.of(direct));
        assertEquals("default", fetcher.fetch("https://x.example/", Map.of(), T).body());
        assertThrows(IllegalStateException.class,
                () -> new HouseFetcher(Set.of(new FakeTransport(FetchUtil.BROWSER))));
    }

    @Test
    void conditionalCacheServes304AsTheOriginal200() throws Exception {
        FakeTransport direct = new FakeTransport(FetchUtil.DIRECT)
                .answer(WebResponse.text(200, "fresh", Map.of("ETag", "\"v1\"")))
                .answer(WebResponse.text(304, "", Map.of()));
        HouseFetcher fetcher = new HouseFetcher(Set.of(direct));

        WebResponse first = fetcher.fetch("https://c.example/feed", Map.of(), T, FetchUtil.DIRECT);
        assertEquals("fresh", first.body());
        WebResponse second = fetcher.fetch("https://c.example/feed", Map.of(), T, FetchUtil.DIRECT);
        assertEquals(200, second.status());
        assertEquals("fresh", second.body());
    }
}
