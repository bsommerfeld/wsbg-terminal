package de.bsommerfeld.wsbg.terminal.db;

/**
 * One verified fact on a subject's permanent dossier — distilled from a NEWS
 * article, never from room sentiment. The source is <b>value-copied</b> at mint
 * time (title, publisher, url, published-at): articles live nowhere durable in
 * this system, so a fact holding only a link would dangle within minutes of the
 * article aging out of its pool.
 *
 * <p>{@code subjectKey} is the subject's ticker (UPPER) — the same key the
 * headline archive and the market-event register use. {@code isin} is the hard
 * identity axis where the desk stamped one: reads union over ticker OR ISIN, so
 * a WKN-keyed unit and its Yahoo-keyed twin share one dossier without any
 * merge bookkeeping.
 *
 * <p>{@code consolidated} marks a summary line the consolidation pass wrote
 * from several older facts — it carries no single source.
 */
public record DossierFact(
        String subjectKey,
        String isin,
        String canonicalName,
        String text,
        long atEpoch,
        String sourceTitle,
        String sourcePublisher,
        String sourceUrl,
        Long sourcePublishedAtEpoch,
        boolean consolidated) {

    /**
     * Idempotency key: one fact per (subject, article). Consolidated lines key
     * on their text hash — they have no source URL.
     */
    public String identity() {
        String source = sourceUrl == null || sourceUrl.isBlank()
                ? "~" + Integer.toHexString(text == null ? 0 : text.hashCode())
                : sourceUrl.trim();
        return subjectKey + "|" + source;
    }
}
