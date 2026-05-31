package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collector;

/**
 * Persists one finished headline draft and broadcasts it to the UI. The
 * deterministic-pipeline replacement for {@code PublishHeadlineTool.execute}:
 * the editorial model produces a {@link Draft}, the {@link TickerResolver} has
 * already resolved the instruments, and this writer applies the
 * <em>quality</em> checks and saves.
 *
 * <h3>What it keeps vs. drops (vs. the old tool)</h3>
 * Kept — pure QA that sanitises or enriches without dropping a headline: HTML
 * stripping, source-id hygiene, ticker-shape sanitising, the
 * position-P&amp;L/price-move sanity cap, subject validation, sector/asset
 * normalisation, market-snapshot attach. Dropped — the reject/throttle gates
 * that suppressed the 1:1 mirror: the hard ≤20-word reject (now prompt guidance
 * only), the 10-minute per-(cluster,ticker) cooldown, and the cross-cluster
 * ticker reject. The single soft throttle retained is the
 * IMPORTANT→NORMAL downgrade when the same ticker was just flagged, so the wire
 * doesn't light up red five times for one symbol — it still prints every line.
 * A short identical-text guard prevents an accidental double-publish.
 */
@Singleton
public final class HeadlineWriter {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlineWriter.class);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    /** Same ticker flagged IMPORTANT within this window → downgrade to NORMAL. */
    private static final long TICKER_FLAG_WINDOW_SECS = 900;
    /** Skip an identical headline text for the same cluster within this window. */
    private static final long DUP_TEXT_GUARD_SECS = 120;

    private final AgentRepository agentRepository;
    private final ApplicationEventBus eventBus;

    @Inject
    public HeadlineWriter(AgentRepository agentRepository, ApplicationEventBus eventBus) {
        this.agentRepository = agentRepository;
        this.eventBus = eventBus;
    }

    /**
     * One headline as drafted by the editorial model. {@code subjects} carry
     * the ticker the model copied from the resolved data we showed it; the
     * writer re-checks it against {@code resolved} so a hallucinated symbol is
     * dropped rather than rendered.
     */
    public record Draft(
            String headline,
            String sentiment,
            String highlight,
            String tickerSymbol,
            List<DraftSubject> subjects,
            Double priceMovePercent,
            List<String> sectors,
            String assetClass,
            List<String> sourceThreadIds,
            List<String> sourceCommentIds) {
    }

    public record DraftSubject(String name, String ticker) {
    }

    /**
     * Publishes the draft for the given cluster. Returns {@code true} when a
     * headline was saved + broadcast, {@code false} when it was skipped (blank
     * text or identical-text guard). Never throws on bad model output —
     * malformed fields are sanitised away, not rejected.
     */
    public boolean publish(InvestigationCluster cluster, Draft draft,
            List<ResolvedSubject> resolved) {
        if (cluster == null || draft == null) return false;
        String headline = stripHtml(draft.headline()).trim();
        if (headline.isEmpty()) return false;

        // Identical-text guard: don't double-publish the exact same line for
        // the same cluster within a short window (accidental re-fire).
        long now = System.currentTimeMillis() / 1000;
        boolean dup = agentRepository.getHeadlinesByClusterId(cluster.id).stream()
                .anyMatch(h -> headline.equalsIgnoreCase(h.headline())
                        && (now - h.createdAt()) < DUP_TEXT_GUARD_SECS);
        if (dup) {
            LOG.debug("[WRITE] skip duplicate headline text for {}", cluster.id);
            return false;
        }

        // Validated tickers + their market snapshots come from the resolver,
        // not the model — Yahoo is the single source of truth.
        Set<String> validTickers = new HashSet<>();
        Map<String, MarketSnapshot> snapshotByTicker = new HashMap<>();
        if (resolved != null) {
            for (ResolvedSubject rs : resolved) {
                if (rs.isInstrument()) {
                    validTickers.add(rs.ticker().toUpperCase(Locale.ROOT));
                    if (rs.snapshot() != null) {
                        snapshotByTicker.put(rs.ticker().toUpperCase(Locale.ROOT), rs.snapshot());
                    }
                }
            }
        }

        // Source-id hygiene: thread ids must be cluster members; comment ids
        // must look like Reddit comment fullnames.
        List<String> threadIds = clean(draft.sourceThreadIds());
        threadIds.removeIf(id -> !cluster.activeThreadIds.contains(id));
        List<String> commentIds = clean(draft.sourceCommentIds());
        commentIds.removeIf(id -> !id.startsWith("t1_"));

        // Subjects: name must appear verbatim in the headline (UI glow) and the
        // ticker must be one the resolver validated. Dropped otherwise.
        List<HeadlineSubject> subjects = new ArrayList<>();
        if (draft.subjects() != null) {
            for (DraftSubject ds : draft.subjects()) {
                String name = ds.name() == null ? "" : ds.name().trim();
                String ticker = sanitizeTicker(ds.ticker());
                if (name.isEmpty() || ticker == null) continue;
                if (!validTickers.contains(ticker.toUpperCase(Locale.ROOT))) continue;
                if (!headline.contains(name)) continue;
                boolean exists = subjects.stream()
                        .anyMatch(s -> s.name().equals(name) && s.ticker().equals(ticker));
                if (!exists) subjects.add(new HeadlineSubject(name, ticker));
            }
        }

        String tickerSymbol = sanitizeTicker(draft.tickerSymbol());
        if (tickerSymbol != null && !validTickers.contains(tickerSymbol.toUpperCase(Locale.ROOT))) {
            tickerSymbol = null;
        }
        if (tickerSymbol == null && !subjects.isEmpty()) {
            tickerSymbol = subjects.get(0).ticker();
        }

        HeadlineHighlight highlight = HeadlineHighlight.fromString(draft.highlight());
        // Soft anti-over-flag: if this ticker was flagged IMPORTANT very
        // recently (any cluster), downgrade to NORMAL so the wire shows the
        // line without lighting red again. Does NOT drop the headline.
        if (highlight == HeadlineHighlight.IMPORTANT && tickerSymbol != null
                && recentlyFlagged(tickerSymbol, now)) {
            highlight = HeadlineHighlight.NORMAL;
        }

        Double priceMove = sanePriceMove(draft.priceMovePercent(), headline);

        List<String> sectors = clean(draft.sectors()).stream()
                .collect(distinctByLower()).stream().limit(2).toList();
        String assetClass = normalizeAssetClass(draft.assetClass());
        HeadlineSentiment sentiment = HeadlineSentiment.fromString(draft.sentiment());

        MarketSnapshot snapshot = tickerSymbol == null ? null
                : snapshotByTicker.get(tickerSymbol.toUpperCase(Locale.ROOT));

        agentRepository.saveHeadline(cluster.id, headline, "",
                threadIds, commentIds, highlight, tickerSymbol, subjects, priceMove,
                sectors, assetClass, sentiment, snapshot);

        LOG.info("[WRITE] {} [{}{} {}{}]: {}", cluster.id, highlight,
                tickerSymbol == null ? "" : " " + tickerSymbol, sentiment,
                priceMove == null ? "" : String.format(Locale.ROOT, " %+.1f%%", priceMove),
                headline);

        eventBus.post(new AgentStreamEndEvent(
                "||PASSIVE||" + headline + "||REF||ID:" + cluster.id));
        return true;
    }

    private boolean recentlyFlagged(String ticker, long now) {
        long horizon = now - TICKER_FLAG_WINDOW_SECS;
        return agentRepository.getRecentHeadlines().stream()
                .filter(h -> h.createdAt() >= horizon)
                .filter(h -> h.highlight() == HeadlineHighlight.IMPORTANT)
                .anyMatch(h -> ticker.equalsIgnoreCase(h.tickerSymbol()));
    }

    // ---- sanitisers ported verbatim from the old PublishHeadlineTool ----

    private static String stripHtml(String s) {
        return s == null ? "" : HTML_TAG.matcher(s).replaceAll("");
    }

    private static Double sanePriceMove(Double priceMove, String headline) {
        if (priceMove == null) return null;
        if (Math.abs(priceMove) > 500.0
                && headline.matches(".*\\d[\\d.,]*\\s*(€|\\$|EUR|USD).*")) {
            return null; // money amount + huge % ⇒ almost always a P&L misread
        }
        return priceMove;
    }

    private static String sanitizeTicker(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String clean = raw.trim();
        clean = clean.startsWith("$") ? clean.substring(1) : clean;
        return clean.matches("[A-Z]{1,5}([.-][A-Z0-9]{1,3})?") ? clean : null;
    }

    private static String normalizeAssetClass(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.toLowerCase(Locale.ROOT).trim();
        if (s.matches("stock|stocks|equity|equities|aktie|aktien|share|shares")) return "stock";
        if (s.matches("etf|etfs|fund|funds")) return "etf";
        if (s.matches("crypto|cryptocurrency|coin|coins|token|tokens|krypto")) return "crypto";
        if (s.matches("commodity|commodities|metal|metals|oil|gold|silver")) return "commodity";
        if (s.matches("forex|fx|currency|currencies|pair|pairs")) return "forex";
        if (s.matches("bond|bonds|treasury|treasuries|note|notes|yield")) return "bond";
        if (s.matches("option|options|call|put|warrant|warrants")) return "option";
        if (s.matches("other|misc")) return "other";
        return null;
    }

    private static List<String> clean(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        for (String s : in) {
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private static Collector<String, ?, List<String>> distinctByLower() {
        return Collector.of(ArrayList::new,
                (acc, s) -> {
                    String key = s.toLowerCase(Locale.ROOT);
                    if (acc.stream().noneMatch(x -> x.toLowerCase(Locale.ROOT).equals(key))) {
                        acc.add(s);
                    }
                },
                (a, b) -> { a.addAll(b); return a; });
    }
}
