package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GDACS — the UN/EU Global Disaster Alert and Coordination System (researched
 * 2026-07-17, keyless RSS with georss points): the ONE global feed covering
 * floods, droughts, cyclones, quakes and volcanoes with an honest three-step
 * alert level (Green/Orange/Red). Closes exactly the gap the house NHC/USGS
 * legs leave: floods and droughts outside the US, cyclones outside the
 * Atlantic/E-Pacific basins.
 */
@Singleton
public class GdacsClient {

    private static final Logger LOG = LoggerFactory.getLogger(GdacsClient.class);

    private static final String URL = "https://www.gdacs.org/xml/rss.xml";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final int MAX_EVENTS = 60;

    /** One alert: kind token (EQ/TC/FL/DR/VO/WF...), level (GREEN/ORANGE/RED). */
    public record GdacsEvent(String kind, String level, String title, double lat, double lon) {
    }

    private record Cached(Instant at, List<GdacsEvent> events) {
    }

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern POINT =
            Pattern.compile("<georss:point>\\s*(-?[\\d.]+)\\s+(-?[\\d.]+)\\s*</georss:point>");
    private static final Pattern LEVEL =
            Pattern.compile("<gdacs:alertlevel>(.*?)</gdacs:alertlevel>");
    private static final Pattern KIND =
            Pattern.compile("<gdacs:eventtype>(.*?)</gdacs:eventtype>");

    /** Direct-first (joker mandate 2026-07-14); the browser is the rescue. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);
    private volatile Cached cached;

    @Inject
    public GdacsClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The current alerts; stale kept on failure. */
    public List<GdacsEvent> events() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.events();
        }
        try {
            // Accept MUST stay wide: GDACS answers a 406 to the precise
            // "application/rss+xml" its own feed is (live-probed 2026-08-02 -
            // the layer was silently empty until this line changed).
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("Accept", "*/*"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                List<GdacsEvent> parsed = parse(resp.body());
                if (!parsed.isEmpty()) {
                    cached = new Cached(Instant.now(), parsed);
                    return parsed;
                }
            } else {
                LOG.debug("[GDACS] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[GDACS] fetch failed: {}", e.getMessage());
        }
        return hit == null ? List.of() : hit.events();
    }

    /** Package-visible for the fixture test. Regex over RSS — the shape is stable. */
    List<GdacsEvent> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<GdacsEvent> out = new ArrayList<>();
        Matcher item = ITEM.matcher(xml);
        while (item.find() && out.size() < MAX_EVENTS) {
            String body = item.group(1);
            Matcher point = POINT.matcher(body);
            if (!point.find()) continue;
            try {
                double lat = Double.parseDouble(point.group(1));
                double lon = Double.parseDouble(point.group(2));
                out.add(new GdacsEvent(
                        firstGroup(KIND, body, "?"),
                        firstGroup(LEVEL, body, "GREEN").toUpperCase(Locale.ROOT),
                        cleanTitle(firstGroup(TITLE, body, "")),
                        lat, lon));
            } catch (NumberFormatException ignored) {
                // malformed point — skip the item
            }
        }
        return List.copyOf(out);
    }

    private static String firstGroup(Pattern p, String body, String fallback) {
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1).strip() : fallback;
    }

    /** The titles carry XML entities ("MMI&gt;=III") — a tooltip must read them, not spell them. */
    private static String cleanTitle(String raw) {
        return raw.replace("<![CDATA[", "").replace("]]>", "")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&amp;", "&")
                .strip();
    }
}
