package de.bsommerfeld.wsbg.terminal.web.gateway;

import de.bsommerfeld.wsbg.terminal.web.pool.ArticlePool;
import java.util.concurrent.CompletableFuture;

/**
 * THE agent attachment — the one door through which the agent and the
 * terminal see the outside world. Behind it run the three pipelines:
 *
 * <ol>
 *   <li>the POOL query (local — the collectors already brought the world in),</li>
 *   <li>the live INSTRUMENT fan (sources that address ISIN/symbol/name),</li>
 *   <li>the FACTS graze (prices, order book, analysts, shorts, …),</li>
 * </ol>
 *
 * all in parallel, composed into one {@link InquiryResult}. Async-first: the
 * future completes when the slowest pipeline is in; a caller that cannot wait
 * takes the pool's answer via {@link #pool()} immediately.
 */
public interface WebGateway {

    /**
     * One instrument inquiry: resolves {@code query} against the register
     * (ISIN, symbol, name — whatever exists), then runs all three pipelines
     * in parallel. Never completes exceptionally for source failures — those
     * show as {@link SourceOutcome}s; only a broken gateway itself fails the
     * future.
     */
    CompletableFuture<InquiryResult> searchInstrument(String query);

    /**
     * General free-text research: fans every registered search engine and
     * pushes the extracted articles through the same pipeline the collectors
     * feed — results land in the pool, attributed and de-duplicated. Fire
     * and forget by design; readers query the pool.
     */
    void search(String query);

    /**
     * The ARCHIVE inquiry: resolves {@code query} against the register, then
     * fans every {@link de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource}
     * for the date window — the researcher's look into the past the basin
     * cannot hold. Same honesty contract as {@link #searchInstrument}: source
     * failures become outcomes, the result carries the de-duplicated articles
     * (facts and sentiment stay empty — a window inquiry is a press question).
     *
     * @param fromDate        first day of the window (inclusive)
     * @param toDateExclusive first day AFTER the window
     */
    CompletableFuture<InquiryResult> searchInstrumentWindow(String query,
            java.time.LocalDate fromDate, java.time.LocalDate toDateExclusive);

    /** The basin, for direct local reads (world wire, recents, free queries). */
    ArticlePool pool();
}
