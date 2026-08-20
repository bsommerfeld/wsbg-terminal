package de.bsommerfeld.wsbg.terminal.web.source;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import java.util.List;

/**
 * A SEARCH ENGINE: a source that answers a free-text query live — the door
 * for general research questions, not for instrument fans (those go through
 * {@link InstrumentSource}). The gateway's {@code search(String)} fans every
 * registered engine and pushes the extracted articles through the same
 * pipeline the collectors feed, so search results and collected wires land
 * in the one pool.
 */
public interface SearchEngine extends WebSource {

    /**
     * Searches the engine for {@code query}.
     *
     * @return matching articles, most-relevant first, or an empty list —
     *         never {@code null}
     */
    List<Article> search(String query, int limit) throws Exception;
}
