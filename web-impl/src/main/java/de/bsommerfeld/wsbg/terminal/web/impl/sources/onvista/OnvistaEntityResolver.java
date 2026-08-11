package de.bsommerfeld.wsbg.terminal.web.impl.sources.onvista;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ISIN → onvista {@link OnvistaEntity}: the ONE sanctioned way to address an
 * instrument, and the guard against the id trap that would otherwise point
 * every new endpoint at the wrong company.
 *
 * <p>{@code /v1/instruments/query?searchValue=<ISIN>} is the authority. Its
 * {@code list} is a search result, not an exact match — a bare name query
 * happily returns neighbours — so a hit only counts when the row's
 * {@code isin} equals the query AND its {@code entityType} is the wanted one.
 * Verified live 2026-08-02: SAP (DE0007164600) resolves to {@code STOCK/82849}
 * and that same value serves {@code /v1/instruments/STOCK/82849/*} and
 * {@code /v1/stocks/82849/*} alike; the {@code 86627} that circulated as an
 * "alternate SAP id" belongs to Apple (US0378331005). Ids are therefore never
 * hardcoded and never carried between instruments.
 *
 * <p>A second, browser-shaped path exists for the case where the JSON search
 * is unavailable: the public page {@code www.onvista.de/aktien/<slug>-Aktie-<ISIN>}
 * (the slug is normalized by redirect, so any slug works) embeds the entity in
 * its {@code __NEXT_DATA__} island — see {@link #resolveViaPage(String)}.
 *
 * <p>Misses are cached per session, but only STRUCTURED ones (the reply parsed
 * and simply held no matching entity). A garbled body is a transient failure
 * and must heal on the next call.
 *
 * @see OnvistaApi for the shared transport, rate discipline and terms note
 */
@Singleton
public class OnvistaEntityResolver extends OnvistaApi {

    private static final Logger LOG = LoggerFactory.getLogger(OnvistaEntityResolver.class);

    private static final String QUERY = "v1/instruments/query?searchValue=";
    private static final String PAGE_FMT =
            "https://www.onvista.de/aktien/x-Aktie-%s";

    /** "<ISIN>|<TYPE>" → entity or a cached definite miss. */
    private final Map<String, Optional<OnvistaEntity>> cache = new ConcurrentHashMap<>();

    /** Rides the shared direct-first seam (the old {@code @DirectFirst} binding). */
    @Inject
    public OnvistaEntityResolver(WebFetcher fetcher) {
        super(fetcher);
    }

    /** The STOCK entity for an ISIN — the everyday call. */
    public Optional<OnvistaEntity> resolveStock(String isin) {
        return resolve(isin, "STOCK");
    }

    /** The FUND entity for an ISIN (ETFs and mutual funds). */
    public Optional<OnvistaEntity> resolveFund(String isin) {
        return resolve(isin, "FUND");
    }

    /**
     * ISIN → entity of the wanted {@code entityType}, session-cached. Empty on
     * a miss, on a non-matching type, and on any transport failure (the latter
     * is not cached).
     */
    public Optional<OnvistaEntity> resolve(String isin, String entityType) {
        if (isin == null || isin.isBlank() || entityType == null) return Optional.empty();
        String key = isin.trim().toUpperCase(Locale.ROOT) + "|"
                + entityType.toUpperCase(Locale.ROOT);
        Optional<OnvistaEntity> cached = cache.get(key);
        if (cached != null) return cached;

        JsonNode root = getJson(QUERY
                + URLEncoder.encode(isin.trim(), StandardCharsets.UTF_8));
        if (root == null) return Optional.empty();
        Resolution res = parseQuery(root, isin.trim(), entityType);
        if (res.entity() != null) {
            cache.put(key, Optional.of(res.entity()));
            LOG.debug("[onvista] {} → {}", isin, res.entity().pathPair());
            return Optional.of(res.entity());
        }
        if (res.structuredMiss()) cache.put(key, Optional.empty());
        return Optional.empty();
    }

    /**
     * The page fallback: the instrument page embeds the resolved entity in its
     * {@code __NEXT_DATA__} island, so a working browser transport can address
     * an instrument even if the JSON search path is unavailable. One HTML
     * fetch (~550 KB) versus one small JSON — only for the fallback case.
     */
    public Optional<OnvistaEntity> resolveViaPage(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        try {
            var resp = fetcher.fetch(String.format(PAGE_FMT, isin.trim()),
                    Map.of("Accept", "text/html"), requestTimeout, MODES);
            if (resp == null || resp.status() != 200) return Optional.empty();
            return parsePageEntity(resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[onvista] page resolve for {} failed: {}", isin, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Tri-state query verdict: a hit carries the entity; a STRUCTURED miss (the
     * documented shape, its {@code list} simply holding no matching row) may be
     * cached; a garbled body is neither and must never be cached.
     */
    record Resolution(OnvistaEntity entity, boolean structuredMiss) {}

    /** Package-private, network-free: query reply → verdict. */
    static Resolution parseQuery(JsonNode root, String isin, String entityType) {
        if (root == null) return new Resolution(null, false);
        JsonNode list = root.path("list");
        // A parseable body WITHOUT the documented list is a changed or error
        // shape, not a documented miss.
        if (!list.isArray()) return new Resolution(null, false);
        for (JsonNode n : list) {
            if (!isin.equalsIgnoreCase(n.path("isin").asText(""))) continue;
            if (!entityType.equalsIgnoreCase(n.path("entityType").asText(""))) continue;
            OnvistaEntity e = entityOf(n);
            if (e != null) return new Resolution(e, false);
        }
        return new Resolution(null, true);
    }

    /** Package-private, network-free: instrument page HTML → entity. */
    static Optional<OnvistaEntity> parsePageEntity(String html) {
        JsonNode data = OnvistaPageBundle.nextData(html);
        if (data == null) return Optional.empty();
        JsonNode instrument = data.path("props").path("pageProps")
                .path("data").path("snapshot").path("instrument");
        return Optional.ofNullable(entityOf(instrument));
    }
}
