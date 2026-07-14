package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wikipedia's Current-Events portal as the day's world log (live-verified
 * 2026-07-13): {@code Portal:Current_events/YYYY_MonthName_D} via the official
 * {@code action=parse} API — the one keyless, machine-readable digest of what
 * happened in the world today, categorized, one sentence per event, WITH a
 * press citation per bullet. Only the market-relevant categories are kept
 * (conflicts, business, politics, disasters, international relations); the
 * leaf bullets are recognized by their external citation — the wikilink-only
 * bullets above them are the event-chain breadcrumb, not an event. The German
 * Wikipedia has no daily log (probed), so the EN portal is the source; the
 * report writes German regardless. UA per Wikimedia etiquette: descriptive,
 * not a spoofed browser.
 */
@Singleton
public class WikipediaCurrentEventsClient {

    private static final Logger LOG = LoggerFactory.getLogger(WikipediaCurrentEventsClient.class);

    private static final String API =
            "https://en.wikipedia.org/w/api.php?action=parse&prop=wikitext&format=json"
                    + "&formatversion=2&page=";
    private static final String USER_AGENT =
            "wsbg-terminal/1.0 (https://github.com/bsommerfeld/wsbg-terminal)";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The portal's category headings this report cares about (market-relevant). */
    private static final List<String> CATEGORIES = List.of(
            "Armed conflicts and attacks",
            "Business and economy",
            "Disasters and accidents",
            "International relations",
            "Politics and elections");

    /** A bullet counts as an EVENT (not a breadcrumb) when it cites a source. */
    private static final Pattern CITATION =
            Pattern.compile("\\[https?://\\S+\\s+\\(?'{0,2}([^)\\]']+?)'{0,2}\\)?\\]");
    private static final Pattern PIPED_LINK = Pattern.compile("\\[\\[[^\\]|]*\\|([^\\]]*)]]");
    private static final Pattern PLAIN_LINK = Pattern.compile("\\[\\[([^\\]]*)]]");
    private static final Pattern BOLD_HEADING = Pattern.compile("^'''(.+?)'''\\s*$");

    /** One world event of the day: portal category, plain-text sentence, cited outlet. */
    public record WorldEvent(String category, String text, String source) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public WikipediaCurrentEventsClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public WikipediaCurrentEventsClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The day's events in the market-relevant categories, at most
     * {@code maxPerCategory} each, portal order. Empty on any failure (incl.
     * a not-yet-created page).
     */
    public List<WorldEvent> eventsOn(LocalDate day, int maxPerCategory) {
        try {
            String page = "Portal:Current_events/" + day.getYear() + "_"
                    + monthName(day.getMonthValue()) + "_" + day.getDayOfMonth();
            WebResponse resp = fetcher.fetch(API + URLEncoder.encode(page, StandardCharsets.UTF_8),
                    Map.of("User-Agent", USER_AGENT, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                String wikitext = JSON.readTree(resp.body())
                        .path("parse").path("wikitext").asText("");
                return parse(wikitext, maxPerCategory);
            }
            LOG.debug("[Wikipedia] current events {} answered status {}", day,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Wikipedia] current events {} failed: {}", day, e.getMessage());
        }
        return List.of();
    }

    /** English month name, locale-independent (the page names are English). */
    static String monthName(int month) {
        return switch (month) {
            case 1 -> "January";
            case 2 -> "February";
            case 3 -> "March";
            case 4 -> "April";
            case 5 -> "May";
            case 6 -> "June";
            case 7 -> "July";
            case 8 -> "August";
            case 9 -> "September";
            case 10 -> "October";
            case 11 -> "November";
            default -> "December";
        };
    }

    /**
     * Package-private for tests: portal wikitext → events, network-free.
     * Structure pinned by probe: bold category headings, nested {@code *}
     * bullets; a leaf event carries prose plus {@code [url (''Outlet'')]}.
     */
    static List<WorldEvent> parse(String wikitext, int maxPerCategory) {
        if (wikitext == null || wikitext.isBlank()) return List.of();
        List<WorldEvent> out = new ArrayList<>();
        Map<String, Integer> perCategory = new HashMap<>();
        String category = null;
        for (String raw : wikitext.split("\n")) {
            String line = raw.strip();
            Matcher heading = BOLD_HEADING.matcher(line);
            if (heading.matches()) {
                String h = heading.group(1).strip();
                category = CATEGORIES.stream().filter(h::equalsIgnoreCase).findFirst()
                        .orElse(null);
                continue;
            }
            if (category == null || !line.startsWith("*")) continue;
            Matcher citation = CITATION.matcher(line);
            if (!citation.find()) continue; // breadcrumb bullet, not an event
            if (perCategory.merge(category, 1, Integer::sum) > maxPerCategory) continue;
            String source = citation.group(1).strip();
            String text = cleanText(line);
            if (text.length() < 30) continue; // a bare link with a citation is no sentence
            out.add(new WorldEvent(category, text, source));
        }
        return out;
    }

    /** Bullet wikitext → plain sentence: links unwrapped, citations and markup stripped. */
    static String cleanText(String line) {
        String s = line.replaceFirst("^\\*+\\s*", "");
        s = CITATION.matcher(s).replaceAll("");
        s = PIPED_LINK.matcher(s).replaceAll("$1");
        s = PLAIN_LINK.matcher(s).replaceAll("$1");
        s = s.replace("'''", "").replace("''", "");
        s = s.replaceAll("<!--.*?-->", "");
        s = s.replaceAll("\\{\\{[^}]*}}", "");
        return s.replaceAll("\\s+", " ").strip();
    }
}
