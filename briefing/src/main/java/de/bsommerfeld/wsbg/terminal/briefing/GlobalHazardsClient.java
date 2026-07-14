package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The day's PHYSICAL-WORLD hazards with a market shadow — three keyless legs
 * (all live-probed 2026-07-14), each best-effort on its own:
 *
 * <ul>
 * <li><b>Tropical storms</b> — NOAA NHC {@code CurrentStorms.json}: the
 * active Atlantic/Pacific systems by name, classification and intensity. A
 * hurricane aimed at the Gulf coast IS an energy/insurance story; an empty
 * {@code activeStorms} array out of season is the normal answer.</li>
 * <li><b>Earthquakes</b> — USGS {@code 4.5_day.geojson} (past 24h, M4.5+),
 * filtered to what could carry an economic shadow: magnitude ≥ 5.5 or a
 * non-green PAGER alert or a tsunami flag. Magnitude, place, alert level.</li>
 * <li><b>US aviation</b> — the FAA's national airspace status XML
 * ({@code nasstatus.faa.gov/api/airport-status-information}): ground stops,
 * ground delay programs and weather delays by airport — the airline-sector
 * disruption signal. Airport CLOSURE entries are deliberately skipped (GA
 * NOTAM noise: "CLSD TO TRANSIENT GA ACFT" is not a market event).</li>
 * </ul>
 */
@Singleton
public class GlobalHazardsClient {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalHazardsClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    private static final String NHC_URL = "https://www.nhc.noaa.gov/CurrentStorms.json";
    private static final String USGS_URL =
            "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson";
    private static final String FAA_URL =
            "https://nasstatus.faa.gov/api/airport-status-information";

    /** Quakes below this stay out unless PAGER/tsunami flags them. */
    static final double QUAKE_MIN_MAGNITUDE = 5.5;

    /** One hazard line. {@code kind}: STORM / QUAKE / AVIATION; {@code severity} a stable token. */
    public record Hazard(String kind, String text, String severity) {}

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public GlobalHazardsClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam — all three feeds are wall-less. */
    @Inject
    public GlobalHazardsClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** All three legs merged, storms first; each leg best-effort. */
    public List<Hazard> hazards() {
        List<Hazard> out = new ArrayList<>();
        out.addAll(leg("NHC storms", () -> parseStorms(get(NHC_URL, "application/json"))));
        out.addAll(leg("USGS quakes", () -> parseQuakes(get(USGS_URL, "application/json"))));
        out.addAll(leg("FAA aviation", () -> parseFaa(get(FAA_URL, "application/xml"))));
        return out;
    }

    private List<Hazard> leg(String what, java.util.function.Supplier<List<Hazard>> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            LOG.debug("[Hazards] leg {} failed: {}", what, e.getMessage());
            return List.of();
        }
    }

    private String get(String url, String accept) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", accept),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[Hazards] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Hazards] fetch {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** Package-private for tests: NHC CurrentStorms JSON → one line per active system. */
    static List<Hazard> parseStorms(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<Hazard> out = new ArrayList<>();
        try {
            JsonNode storms = JSON.readTree(body).get("activeStorms");
            if (storms == null || !storms.isArray()) return List.of();
            for (JsonNode s : storms) {
                String name = text(s, "name");
                if (name.isEmpty()) continue;
                String classification = text(s, "classification");
                String intensity = text(s, "intensity");
                StringBuilder sb = new StringBuilder();
                sb.append(classificationWord(classification)).append(' ').append(name);
                if (!intensity.isEmpty()) sb.append(", ").append(intensity).append(" kt");
                String basin = text(s, "binNumber");
                if (!basin.isEmpty()) {
                    sb.append(" (").append(basin.startsWith("EP") ? "Pazifik" : "Atlantik")
                            .append(')');
                }
                out.add(new Hazard("STORM", sb.toString(),
                        "HU".equalsIgnoreCase(classification) ? "HIGH" : "MEDIUM"));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /** NHC classification codes → readable words (HU hurricane, TS tropical storm, TD depression). */
    static String classificationWord(String code) {
        if (code == null) return "Sturmsystem";
        return switch (code.toUpperCase(java.util.Locale.ROOT)) {
            case "HU" -> "Hurrikan";
            case "TS" -> "Tropensturm";
            case "TD" -> "Tropentief";
            case "STD", "STS" -> "Subtropensturm";
            case "PTC" -> "Post-tropisches System";
            default -> "Sturmsystem";
        };
    }

    /**
     * Package-private for tests: USGS GeoJSON → quakes with an economic
     * shadow (M ≥ {@link #QUAKE_MIN_MAGNITUDE}, or PAGER alert beyond green,
     * or a tsunami flag).
     */
    static List<Hazard> parseQuakes(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<Hazard> out = new ArrayList<>();
        try {
            JsonNode features = JSON.readTree(body).get("features");
            if (features == null || !features.isArray()) return List.of();
            for (JsonNode f : features) {
                JsonNode p = f.get("properties");
                if (p == null) continue;
                double mag = p.hasNonNull("mag") ? p.get("mag").asDouble() : 0;
                String alert = text(p, "alert");
                boolean tsunami = p.hasNonNull("tsunami") && p.get("tsunami").asInt() != 0;
                boolean alerted = !alert.isEmpty() && !"green".equalsIgnoreCase(alert);
                if (mag < QUAKE_MIN_MAGNITUDE && !alerted && !tsunami) continue;
                StringBuilder sb = new StringBuilder("M").append(
                        String.format(java.util.Locale.ROOT, "%.1f", mag));
                String place = text(p, "place");
                if (!place.isEmpty()) sb.append(' ').append(place);
                if (tsunami) sb.append(" — Tsunami-Warnung");
                if (alerted) sb.append(" — PAGER ").append(alert);
                out.add(new Hazard("QUAKE", sb.toString(),
                        tsunami || "red".equalsIgnoreCase(alert)
                                || "orange".equalsIgnoreCase(alert) ? "HIGH" : "MEDIUM"));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /**
     * Package-private for tests: FAA airspace XML → ground stops, ground
     * delay programs and arrival/departure delays. Closure entries skipped.
     */
    static List<Hazard> parseFaa(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<Hazard> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            String airport = null, reason = null, avg = null, max = null, endTime = null;
            // Which container we're inside decides the hazard flavor.
            String container = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        switch (r.getLocalName()) {
                            case "Program", "Ground_Delay", "Delay" -> {
                                container = r.getLocalName();
                                airport = reason = avg = max = endTime = null;
                            }
                            case "ARPT" -> airport = r.getElementText().trim();
                            case "Reason" -> {
                                if (container != null) reason = r.getElementText().trim();
                            }
                            case "Avg" -> avg = r.getElementText().trim();
                            case "Max" -> max = r.getElementText().trim();
                            case "End_Time" -> endTime = r.getElementText().trim();
                            default -> { /* closures etc. — ignored */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && container != null
                            && container.equals(r.getLocalName())) {
                        if (airport != null && !airport.isEmpty()) {
                            out.add(faaHazard(container, airport, reason, avg, max, endTime));
                        }
                        container = null;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            return List.copyOf(out);
        }
        return out;
    }

    private static Hazard faaHazard(String container, String airport, String reason,
            String avg, String max, String endTime) {
        StringBuilder sb = new StringBuilder(airport);
        String severity = "MEDIUM";
        switch (container) {
            case "Program" -> {
                sb.append(": Ground Stop");
                if (endTime != null && !endTime.isEmpty()) sb.append(" bis ").append(endTime);
                severity = "HIGH";
            }
            case "Ground_Delay" -> {
                sb.append(": Ground Delay");
                if (avg != null && !avg.isEmpty()) sb.append(", Ø ").append(avg);
                if (max != null && !max.isEmpty()) sb.append(", max ").append(max);
            }
            default -> sb.append(": Verspätungen");
        }
        if (reason != null && !reason.isEmpty()) {
            sb.append(" (").append(reason.replace("WX:", "Wetter: ")).append(')');
        }
        return new Hazard("AVIATION", sb.toString(), severity);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
