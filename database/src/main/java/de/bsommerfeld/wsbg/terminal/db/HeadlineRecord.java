package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;

import java.util.List;

/**
 * Immutable headline data. Source-ID lists may be empty; ticker symbol
 * and price-move % are nullable when the agent didn't attribute them.
 */
public record HeadlineRecord(
        String clusterId,
        String headline,
        String context,
        long createdAt,
        List<String> sourceThreadIds,
        List<String> sourceCommentIds,
        HeadlineHighlight highlight,
        String tickerSymbol,
        List<HeadlineSubject> subjects,
        Double priceMovePercent,
        List<String> sectors,
        String assetClass,
        HeadlineSentiment sentiment,
        MarketSnapshot snapshot,
        boolean newsEnriched,
        List<HeadlineNewsRef> newsRefs) {

    /**
     * Archive lines that predate {@code newsRefs} deserialize with a
     * {@code null} list — normalise it away so no consumer ever needs a
     * null-guard on the field.
     */
    public HeadlineRecord {
        newsRefs = newsRefs == null ? List.of() : newsRefs;
    }

    /**
     * Backward-compatible constructor for records that predate the
     * {@code newsEnriched} provenance flag — old archive (JSONL) lines and
     * existing call sites that never knew about news enrichment. Defaults the
     * flag to {@code false}. (Jackson loads old lines via the canonical
     * constructor; a missing {@code newsEnriched} primitive simply stays
     * {@code false}, so the archive is read-compatible without this ctor — it
     * exists only to keep positional Java call sites compiling.)
     */
    public HeadlineRecord(String clusterId, String headline, String context,
            long createdAt, List<String> sourceThreadIds, List<String> sourceCommentIds,
            HeadlineHighlight highlight, String tickerSymbol, List<HeadlineSubject> subjects,
            Double priceMovePercent, List<String> sectors, String assetClass,
            HeadlineSentiment sentiment, MarketSnapshot snapshot) {
        this(clusterId, headline, context, createdAt, sourceThreadIds, sourceCommentIds,
                highlight, tickerSymbol, subjects, priceMovePercent, sectors, assetClass,
                sentiment, snapshot, false, List.of());
    }

    /** Positional-compatibility constructor from before {@code newsRefs}. */
    public HeadlineRecord(String clusterId, String headline, String context,
            long createdAt, List<String> sourceThreadIds, List<String> sourceCommentIds,
            HeadlineHighlight highlight, String tickerSymbol, List<HeadlineSubject> subjects,
            Double priceMovePercent, List<String> sectors, String assetClass,
            HeadlineSentiment sentiment, MarketSnapshot snapshot, boolean newsEnriched) {
        this(clusterId, headline, context, createdAt, sourceThreadIds, sourceCommentIds,
                highlight, tickerSymbol, subjects, priceMovePercent, sectors, assetClass,
                sentiment, snapshot, newsEnriched, List.of());
    }
}
