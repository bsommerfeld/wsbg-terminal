package de.bsommerfeld.wsbg.terminal.web.source;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import java.time.LocalDate;
import java.util.List;

/**
 * The ARCHIVE role: a source whose backend can address a DATE WINDOW — the
 * researcher's door into the past, which the basin (present only, by design)
 * can never answer. Opt-in self-description like every capability on the
 * contract: a source with a real archive implements this BESIDE its normal
 * role; a source without one simply doesn't — no flags, no curated list of
 * "archive-capable" names anywhere else.
 *
 * <p>The gateway fans only the registered archive sources for a window
 * inquiry; results ride the same pipeline as everything else (stamped,
 * pooled, de-duplicated, outcome-tracked).
 */
public interface ArchiveSource extends WebSource {

    /**
     * Articles referencing the instrument published inside the window,
     * newest first.
     *
     * @param fromDate        first day of the window (inclusive)
     * @param toDateExclusive first day AFTER the window
     * @return matching articles, or an empty list — never {@code null}
     */
    List<Article> newsForWindow(ResolvedInstrument instrument,
            LocalDate fromDate, LocalDate toDateExclusive, int limit) throws Exception;
}
