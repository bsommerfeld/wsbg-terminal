package de.bsommerfeld.wsbg.terminal.db;

/**
 * One external news article a published headline leaned on — the provenance
 * behind the UI's "News" tag. Captured at publish time from the subject
 * unit's attached news pool, so the reader can open the original sources.
 *
 * <p>Transport-neutral and archive-friendly: plain strings + an epoch, no
 * vendor types, so it round-trips through the JSONL archive and the session
 * snapshot like every other {@link AgentRepository.HeadlineRecord} field.
 *
 * @param title       the article's headline text
 * @param publisher   originating outlet (may be blank)
 * @param url         permalink to the full article
 * @param publishedAt publication time in epoch seconds, or {@code null}
 *                    when the source gave none
 */
public record HeadlineNewsRef(String title, String publisher, String url, Long publishedAt) {
}
