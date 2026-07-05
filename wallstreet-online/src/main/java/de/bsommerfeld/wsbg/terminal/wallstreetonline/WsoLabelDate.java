package de.bsommerfeld.wsbg.terminal.wallstreetonline;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the publish date out of a wallstreet-online searchNews result's
 * {@code label} HTML.
 *
 * <p>The date rides inside the label: a {@code previous-day} span with
 * {@code dd.MM.yy} for older items, a bare {@code HH:mm:ss} for today's.
 * Unparseable labels yield {@code null} (the aggregator sorts those last),
 * never a guessed timestamp — the "never guess a date" contract is load-bearing.
 */
final class WsoLabelDate {

    private WsoLabelDate() {
    }

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.uu");

    /** {@code dd.MM.yy} inside the label's previous-day span. */
    private static final Pattern LABEL_DAY =
            Pattern.compile("previous-day\">(\\d{2}\\.\\d{2}\\.\\d{2})<");
    /** A bare {@code HH:mm:ss} in the wknBox — the item is from today. */
    private static final Pattern LABEL_TIME =
            Pattern.compile("wknBox\">(\\d{2}:\\d{2}:\\d{2})<");

    /** The label's date: {@code dd.MM.yy} → that day, bare {@code HH:mm:ss} → today (Berlin). */
    static Instant publishedAt(String label) {
        try {
            Matcher day = LABEL_DAY.matcher(label);
            if (day.find()) {
                return LocalDate.parse(day.group(1), DAY).atStartOfDay(BERLIN).toInstant();
            }
            Matcher time = LABEL_TIME.matcher(label);
            if (time.find()) {
                return LocalDate.now(BERLIN).atTime(LocalTime.parse(time.group(1)))
                        .atZone(BERLIN).toInstant();
            }
        } catch (Exception ignored) {
            // fall through — an unparseable date must never drop the item
        }
        return null;
    }
}
