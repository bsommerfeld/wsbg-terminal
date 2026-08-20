package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tech/AI infrastructure health (researched + live-verified 2026-07-17,
 * keyless): the statuspage.io {@code /api/v2/summary.json} pattern most big
 * services expose. The catalog is a fixed infrastructure board (the
 * world-weather PLACES doctrine: each entry a market story — AI outages ARE
 * market events since half the economy calls these APIs), never editorial
 * curation of news.
 *
 * <p>Quiet services stay silent: the report carries only services whose
 * indicator is NOT "none", plus the count of boards checked — absence of
 * entries IS the "all green" statement.
 */
@Singleton
public class ServiceStatusClient {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceStatusClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One status board: display name + its statuspage summary endpoint. */
    private record Board(String name, String url) {
    }

    /** The infrastructure board — AI desks first, then the plumbing of the web. */
    private static final List<Board> BOARDS = List.of(
            new Board("OpenAI", "https://status.openai.com/api/v2/summary.json"),
            new Board("Claude/Anthropic", "https://status.claude.com/api/v2/summary.json"),
            new Board("GitHub", "https://www.githubstatus.com/api/v2/summary.json"),
            new Board("Cloudflare", "https://www.cloudflarestatus.com/api/v2/summary.json"),
            new Board("Discord", "https://discordstatus.com/api/v2/summary.json"),
            new Board("Vercel", "https://www.vercel-status.com/api/v2/summary.json"),
            new Board("Netlify", "https://www.netlifystatus.com/api/v2/summary.json"));

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /**
     * One degraded service: {@code indicator} is statuspage's own token
     * (minor/major/critical), {@code note} the newest unresolved incident name.
     */
    public record ServiceStatus(String name, String indicator, String note) {
    }

    /** The board sweep: degraded services only + how many boards answered. */
    public record StatusReport(int boardsChecked, List<ServiceStatus> degraded) {
    }

    private record Cached(Instant at, StatusReport report) {
    }

    /** Transport order of the old {@code @DirectFirst} seam: direct first, browser joker as fallback. */
    private static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(10);
    private volatile Cached cached;

    /** Production: the shared house fetcher; {@code MODES} carries the old {@code @DirectFirst} order. */
    @Inject
    public ServiceStatusClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The current sweep; stale kept on total failure. */
    public StatusReport report() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.report();
        }
        int answered = 0;
        List<ServiceStatus> degraded = new ArrayList<>();
        for (Board board : BOARDS) {
            try {
                WebResponse resp = fetcher.fetch(board.url(),
                        Map.of("Accept", "application/json"),
                        requestTimeout, MODES);
                if (resp == null || resp.status() != 200) continue;
                answered++;
                parseBoard(board.name(), resp.body()).ifPresent(degraded::add);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.debug("[STATUS] {} failed: {}", board.name(), e.getMessage());
            }
        }
        if (answered > 0) {
            StatusReport report = new StatusReport(answered, List.copyOf(degraded));
            cached = new Cached(Instant.now(), report);
            return report;
        }
        return hit == null ? new StatusReport(0, List.of()) : hit.report();
    }

    /** Package-visible for the fixture test: empty = the board is all green. */
    Optional<ServiceStatus> parseBoard(String name, String body) {
        try {
            JsonNode root = JSON.readTree(body);
            String indicator = root.path("status").path("indicator").asText("none");
            if ("none".equals(indicator)) return Optional.empty();
            String note = null;
            JsonNode incidents = root.path("incidents");
            if (incidents.isArray() && incidents.size() > 0) {
                note = incidents.get(0).path("name").asText(null);
            }
            return Optional.of(new ServiceStatus(name, indicator, note));
        } catch (Exception e) {
            LOG.debug("[STATUS] {} parse failed: {}", name, e.getMessage());
            return Optional.empty();
        }
    }
}
