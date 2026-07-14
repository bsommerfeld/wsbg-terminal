package de.bsommerfeld.wsbg.terminal.db;

/**
 * One archived Fear&amp;Greed trading day — the sentiment-regime axis of the
 * market memory. CNN's dataviz endpoint serves the whole daily series back to
 * 2020-09-21 (live-probed 2026-07-14), so the archive is back-filled once and
 * topped up forward; every dated event can then be stamped with the regime
 * that ruled the day BEFORE it (t−1, the event-study convention).
 *
 * <p>Plain record, no Jackson annotations — round-trips through the vanilla
 * {@code ObjectMapper} by component name, exactly like {@link HeadlineRecord}.
 *
 * @param date   ISO local date of the reading (UTC trading day)
 * @param score  the composite 0–100 score
 * @param rating CNN's own band word ("extreme fear" … "extreme greed"); may be
 *               empty on points the payload shipped without one — the band is
 *               always re-derivable from the score
 */
public record FearGreedDayRecord(String date, double score, String rating) {
}
