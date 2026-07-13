package de.bsommerfeld.wsbg.terminal.db;

/**
 * One entry of the archive's subject vocabulary: a subject the wire has named
 * at least once, aggregated across every archived headline. {@code name} is the
 * most recently written display form (falls back to the ticker when no line
 * ever carried a name), {@code ticker} is nullable (name-only subjects are
 * first-class on the wire), {@code count} is the number of headlines naming the
 * subject and {@code lastSeen} the newest mention's {@code createdAt}.
 *
 * <p>This is the "internal mapping" the search UI's suggestion resolver runs
 * on — small, flat and cheap to ship over the socket.
 */
public record HeadlineSubjectStat(String name, String ticker, int count, long lastSeen) {
}
