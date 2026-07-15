package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * openligadb — the German-football fixture leg (keyless JSON, live-probed
 * 2026-07-14): {@code api.openligadb.de/getmatchdata/<league>} returns the
 * current/next matchday's fixtures for a league shortcut. Pinned shortcuts:
 * {@code bl1}/{@code bl2}/{@code bl3} (1./2./3. Liga), {@code dfb} (DFB-Pokal),
 * and the big tournaments where the API carries them ({@code em}/{@code wm}) —
 * a shortcut the API doesn't cover simply answers empty. Per match:
 * {@code matchDateTimeUTC} (ISO instant), {@code team1}/{@code team2} names, and
 * the {@code group.groupName} (the "N. Spieltag" label).
 *
 * <p>Only UPCOMING (not-yet-finished) fixtures are surfaced. Cache 12h per
 * league. Best-effort empty on any failure.
 */
@Singleton
public class SportsCalendarClient {

    private static final Logger LOG = LoggerFactory.getLogger(SportsCalendarClient.class);

    private static final String BASE = "https://api.openligadb.de/getmatchdata/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CACHE_TTL = Duration.ofHours(12);

    /** The pinned league/tournament shortcuts the top German audience follows. */
    public static final List<String> DEFAULT_LEAGUES = List.of("bl1", "bl2", "bl3", "dfb");

    /** One upcoming fixture: league, matchday label, teams, kickoff instant. */
    public record Fixture(String league, String matchday, String home, String away,
            Instant kickoff) {
    }

    private record Cached(Instant at, List<Fixture> fixtures) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public SportsCalendarClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam — openligadb is wall-less. */
    @Inject
    public SportsCalendarClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Upcoming fixtures for one league shortcut (only not-yet-finished matches). */
    public List<Fixture> upcoming(String league) {
        Cached hit = cache.get(league);
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.fixtures();
        }
        try {
            WebResponse resp = fetcher.fetch(BASE + league,
                    Map.of("Accept", "application/json"), requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<Fixture> fixtures = parse(league, resp.body());
                cache.put(league, new Cached(Instant.now(), fixtures));
                return fixtures;
            }
            LOG.debug("[Sports] {} answered status {}", league,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Sports] {} failed: {}", league, e.getMessage());
        }
        return hit != null ? hit.fixtures() : List.of();
    }

    /** Upcoming fixtures across the default league set, merged. */
    public List<Fixture> upcomingDefault() {
        List<Fixture> out = new ArrayList<>();
        for (String league : DEFAULT_LEAGUES) out.addAll(upcoming(league));
        return out;
    }

    /**
     * Package-private for tests: a getmatchdata JSON array → upcoming fixtures,
     * network-free. Finished matches ({@code matchIsFinished == true}) are dropped.
     */
    static List<Fixture> parse(String league, String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Fixture> out = new ArrayList<>();
        try {
            for (JsonNode n : JSON.readTree(json)) {
                if (n.path("matchIsFinished").asBoolean(false)) continue;
                String home = n.path("team1").path("teamName").asText("").strip();
                String away = n.path("team2").path("teamName").asText("").strip();
                if (home.isEmpty() && away.isEmpty()) continue;
                out.add(new Fixture(league,
                        blankToNull(n.path("group").path("groupName").asText("")),
                        home, away,
                        parseDate(n.path("matchDateTimeUTC").asText(""))));
            }
        } catch (Exception e) {
            LOG.debug("[Sports] parse failed: {}", e.getMessage());
        }
        return out;
    }

    private static Instant parseDate(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
