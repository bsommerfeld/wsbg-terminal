package de.bsommerfeld.wsbg.terminal.web.impl.catalog;

import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.schedule.FetchInterval;

import java.util.List;

/**
 * One line of the curated source list — a whole feed source as DATA. A new
 * source of a known shape is one line here, not a class, not a module.
 *
 * @param name      stable source identifier ({@code "bloomberg"})
 * @param publisher display publisher stamped on every article
 * @param origin    self-declared language/sphere
 * @param modes     transport order, e.g. {@code [DIRECT, BROWSER]}
 * @param interval  the collector cadence bounds
 * @param social    {@code true} for sentiment sources (boards, forums)
 * @param accept    the Accept header the feed wants
 * @param urls      the feed URLs pooled under this source
 */
public record CatalogRow(
        String name,
        String publisher,
        SourceOrigin origin,
        List<FetchUtil> modes,
        FetchInterval interval,
        boolean social,
        String accept,
        List<String> urls) {
}
