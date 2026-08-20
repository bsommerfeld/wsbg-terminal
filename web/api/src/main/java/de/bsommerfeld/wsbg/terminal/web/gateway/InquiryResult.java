package de.bsommerfeld.wsbg.terminal.web.gateway;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.facts.MarketFacts;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import java.util.List;

/**
 * The agent's return object: everything one instrument inquiry found, across
 * all three pipelines, in one bundle — the pool's local hits and the live
 * instrument fan merged into ONE de-duplicated article collection, the
 * sentiment stream beside it, the grazed facts, and the per-source outcome
 * lines that make partial answers visible instead of silent.
 *
 * @param instrument the register's resolution (partial resolution is valid)
 * @param articles   press articles, de-duplicated across pool + live fan,
 *                   freshest first
 * @param sentiment  social-sentiment items for the instrument, kept apart
 *                   from press by contract
 * @param facts      the facts pipeline's bundle (possibly {@link MarketFacts#EMPTY})
 * @param outcomes   one line per source that was asked or skipped
 */
public record InquiryResult(
        ResolvedInstrument instrument,
        List<Article> articles,
        List<Article> sentiment,
        MarketFacts facts,
        List<SourceOutcome> outcomes) {

    public InquiryResult {
        if (articles == null) articles = List.of();
        if (sentiment == null) sentiment = List.of();
        if (facts == null) facts = MarketFacts.EMPTY;
        if (outcomes == null) outcomes = List.of();
    }
}
