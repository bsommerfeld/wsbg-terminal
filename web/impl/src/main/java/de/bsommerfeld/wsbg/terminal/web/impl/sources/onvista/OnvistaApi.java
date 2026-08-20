package de.bsommerfeld.wsbg.terminal.web.impl.sources.onvista;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared transport and JSON plumbing for the onvista API sources — one place
 * for the base URL, the request headers, the GET/POST reads over the house
 * {@link WebFetcher} seam ({@link #postJson} rides the seam's {@code post()}
 * door; the former private JDK client is gone) and the small parse helpers
 * every client repeats.
 *
 * <p><b>Rate discipline (probed 2026-08-02).</b> The API answers without a bot
 * wall and without an observable rate limit (60 parallel {@code company_snapshot}
 * requests all returned 200), but {@code robots.txt} declares
 * {@code Crawl-delay: 20}. Callers must therefore stay sparse: prefer the
 * bulk/one-fetch paths ({@code quote_list}, the {@code __NEXT_DATA__} bundle,
 * the {@code MAX}-range {@code eod_history} that carries 30 years in a single
 * call) over loops of per-instrument requests, and never poll.
 *
 * <p><b>Terms of service.</b> onvista's terms state literally: "Eine
 * automatisierte Abfrage der Inhalte des Finanzportals ist ohne ausdrückliche
 * Einwilligung von onvista in jeglicher Form unzulässig. Zuwiderhandlung wird
 * verfolgt.", backed by the German database right (§ 87a UrhG). onvista
 * enforces this selectively at the technical level: {@code chart_history}
 * answers 403 with {@code errorCode 1500} pointing at that terms anchor, and
 * {@code order_book} answers 403 ({@code errorCode 4001}). Neither is wired
 * here, deliberately. The project owner has knowingly cleared this source for
 * use; the note stays so the decision is never mistaken for an oversight.
 */
abstract class OnvistaApi {

    private static final Logger LOG = LoggerFactory.getLogger(OnvistaApi.class);

    static final String API = "https://api.onvista.de/api/";
    static final ObjectMapper JSON = new ObjectMapper();

    /** Direct-first (old {@code @DirectFirst} binding): keyless, no bot wall. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    final WebFetcher fetcher;
    final Duration requestTimeout = Duration.ofSeconds(15);

    OnvistaApi(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** GET {@code API + path}; null on any non-200 or transport failure. */
    JsonNode getJson(String path) {
        try {
            WebResponse resp = fetcher.fetch(API + path,
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp == null || resp.status() != 200) {
                LOG.debug("[onvista] HTTP {} for {}",
                        resp == null ? "null" : resp.status(), path);
                return null;
            }
            return JSON.readTree(resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[onvista] GET {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * POST a JSON body — {@code instruments/quote_list} is the one onvista
     * path that needs one. Rides the house seam's {@code post()} door (the old
     * private JDK client is gone; the seam carries bodies now).
     */
    JsonNode postJson(String path, String body) {
        try {
            WebResponse resp = fetcher.post(API + path,
                    Map.of("Accept", "application/json"),
                    body, "application/json", requestTimeout, MODES);
            if (resp == null || resp.status() != 200) {
                LOG.debug("[onvista] HTTP {} for POST {}",
                        resp == null ? "null" : resp.status(), path);
                return null;
            }
            return JSON.readTree(resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[onvista] POST {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    // ---- parse helpers ----

    /** Blank-safe text read; null when absent or empty. */
    static String text(JsonNode node, String field) {
        if (node == null) return null;
        String s = node.path(field).asText("");
        return s.isBlank() ? null : s;
    }

    /** Numeric read that answers {@link Double#NaN} instead of 0 for absent fields. */
    static double num(JsonNode node, String field) {
        if (node == null) return Double.NaN;
        JsonNode n = node.path(field);
        return n.isNumber() ? n.asDouble() : Double.NaN;
    }

    static int intOr(JsonNode node, String field, int fallback) {
        if (node == null) return fallback;
        JsonNode n = node.path(field);
        return n.isNumber() ? n.asInt() : fallback;
    }

    /** onvista timestamps are ISO-8601 with an offset; null on anything else. */
    static Instant instant(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) return null;
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(raw);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /**
     * The array under {@code node} reached by {@code path}, never null.
     *
     * <p>onvista is inconsistent about this on purpose-looking grounds: the API
     * wraps collections in {@code {"list":[…]}}, while the page bundle often
     * hands the bare array. Both shapes resolve here, and a section that walks
     * one field deeper ({@code eqsCompanyInfo.companyDocumentList.list}) does
     * too — so no caller has to guess.
     */
    static JsonNode listOf(JsonNode node, String... path) {
        if (node == null) return JSON.createArrayNode();
        if (node.isArray()) return node;
        JsonNode cur = node;
        for (String p : path) {
            cur = cur.path(p);
            if (cur.isArray()) return cur;
        }
        JsonNode list = cur.path("list");
        return list.isArray() ? list : JSON.createArrayNode();
    }

    /** Reads the embedded {@code instrument} object into an {@link OnvistaEntity}. */
    static OnvistaEntity entityOf(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) return null;
        String type = text(node, "entityType");
        String value = text(node, "entityValue");
        if (type == null || value == null) return null;
        return new OnvistaEntity(type, value, text(node, "name"), text(node, "isin"),
                text(node, "wkn"), text(node, "symbol"));
    }

    /** Columnar array → doubles; onvista serves EOD/T&S as parallel columns. */
    static double[] doubles(JsonNode node, String field) {
        JsonNode arr = node == null ? null : node.path(field);
        if (arr == null || !arr.isArray()) return new double[0];
        double[] out = new double[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).isNumber() ? arr.get(i).asDouble() : Double.NaN;
        }
        return out;
    }

    static long[] longs(JsonNode node, String field) {
        JsonNode arr = node == null ? null : node.path(field);
        if (arr == null || !arr.isArray()) return new long[0];
        long[] out = new long[arr.size()];
        for (int i = 0; i < out.length; i++) out[i] = arr.get(i).asLong();
        return out;
    }

    static List<String> strings(JsonNode node, String field) {
        JsonNode arr = node == null ? null : node.path(field);
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) for (JsonNode n : arr) out.add(n.asText(""));
        return List.copyOf(out);
    }

    /** onvista date parameters are plain {@code yyyy-MM-dd}. */
    static String isoDate(LocalDate date) {
        return date.toString();
    }

    static LocalDate dayOf(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    static String fmt(double d) {
        return Double.isFinite(d) ? String.format(Locale.ROOT, "%.2f", d) : "n/a";
    }
}
