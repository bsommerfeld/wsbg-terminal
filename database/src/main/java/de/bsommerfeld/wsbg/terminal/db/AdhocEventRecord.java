package de.bsommerfeld.wsbg.terminal.db;

/**
 * One archived EQS ad-hoc disclosure — the German leg of the market-memory
 * event register. There is no German EDGAR and the EQS archive is not
 * reachable keylessly (probed 2026-07-14), so the deep DE event history can
 * only be built forward: every ad-hoc the FN feed ships is frozen here the
 * day it appears.
 *
 * <p>Plain record, no Jackson annotations — round-trips through the vanilla
 * {@code ObjectMapper} by component name, exactly like {@link HeadlineRecord}.
 *
 * @param publishedAt ISO-8601 instant of the disclosure (the feed's pubDate)
 * @param isin        the disclosing issuer's ISIN; null when the feed item
 *                    carried none (venue-less pieces)
 * @param title       the disclosure title, service prefix already stripped
 * @param link        the FN article link (the future full-text/classification leg)
 */
public record AdhocEventRecord(String publishedAt, String isin, String title, String link) {
}
