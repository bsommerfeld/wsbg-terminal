package de.bsommerfeld.tinyrss.mapping;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feed dates to instants. RSS promises RFC 822 and Atom RFC 3339; what feeds
 * write is either of them in any variant, a German date, an SQL timestamp or
 * epoch seconds. Read by tokens rather than by one fixed pattern:
 * <ul>
 *   <li>RFC 822 with or without weekday or seconds, two-digit years, month
 *       names in English, German, French, Spanish, Italian or Portuguese,
 *       zones as offset ({@code +0200}, {@code +02:00}, {@code GMT+2}) or name
 *       ({@code GMT}, {@code Z}, {@code EST}, {@code CEST}, {@code MESZ}), a
 *       trailing comment ({@code (CEST)});</li>
 *   <li>ISO 8601 with {@code T} or a space, with or without seconds,
 *       fraction or zone, or as a bare date;</li>
 *   <li>{@code 30.09.2026 10:15} and epoch seconds or milliseconds.</li>
 * </ul>
 * A date without a zone is taken as UTC.
 */
final class Dates {

    private static final Pattern ISO = Pattern.compile(
            "(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})"
                    + "(?:[Tt\\s]+(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?)?\\s*(.*)");
    private static final Pattern DAY_FIRST = Pattern.compile(
            "(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})(?:[,\\s]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?\\s*(.*)");
    private static final Pattern EPOCH = Pattern.compile("\\d{10}|\\d{13}");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,]\\d+)?)?(.*)");
    private static final Pattern OFFSET = Pattern.compile("(?:GMT|UTC|UT)?([+-])(\\d{1,2})(?::?(\\d{2}))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = Pattern.compile("\\([^)]*\\)");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("gen", 1), Map.entry("ene", 1),
            Map.entry("feb", 2), Map.entry("fev", 2),
            Map.entry("mar", 3), Map.entry("mrz", 3),
            Map.entry("apr", 4), Map.entry("avr", 4), Map.entry("abr", 4),
            Map.entry("may", 5), Map.entry("mai", 5), Map.entry("mag", 5),
            Map.entry("jun", 6), Map.entry("giu", 6), Map.entry("juin", 6),
            Map.entry("jul", 7), Map.entry("lug", 7), Map.entry("juil", 7),
            Map.entry("aug", 8), Map.entry("aou", 8), Map.entry("ago", 8),
            Map.entry("sep", 9), Map.entry("set", 9),
            Map.entry("oct", 10), Map.entry("okt", 10), Map.entry("ott", 10), Map.entry("out", 10),
            Map.entry("nov", 11),
            Map.entry("dec", 12), Map.entry("dez", 12), Map.entry("dic", 12));

    /** Zone names feeds write, in minutes east of UTC. */
    private static final Map<String, Integer> ZONES = Map.ofEntries(
            Map.entry("z", 0), Map.entry("ut", 0), Map.entry("utc", 0), Map.entry("gmt", 0), Map.entry("wet", 0),
            Map.entry("bst", 60), Map.entry("cet", 60), Map.entry("mez", 60), Map.entry("west", 60),
            Map.entry("cest", 120), Map.entry("mesz", 120), Map.entry("eet", 120),
            Map.entry("eest", 180), Map.entry("msk", 180),
            Map.entry("ist", 330),
            Map.entry("sgt", 480), Map.entry("hkt", 480), Map.entry("awst", 480),
            Map.entry("jst", 540), Map.entry("kst", 540),
            Map.entry("aest", 600), Map.entry("aedt", 660), Map.entry("nzst", 720), Map.entry("nzdt", 780),
            Map.entry("est", -300), Map.entry("edt", -240), Map.entry("cst", -360), Map.entry("cdt", -300),
            Map.entry("mst", -420), Map.entry("mdt", -360), Map.entry("pst", -480), Map.entry("pdt", -420),
            Map.entry("akst", -540), Map.entry("akdt", -480), Map.entry("hst", -600));

    private Dates() {
    }

    /** @return {@code null} when {@code raw} is empty or no date */
    static Instant parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = COMMENT.matcher(raw).replaceAll(" ").trim();
        try {
            if (EPOCH.matcher(text).matches()) {
                long value = Long.parseLong(text);
                return text.length() == 10 ? Instant.ofEpochSecond(value) : Instant.ofEpochMilli(value);
            }
            Matcher iso = ISO.matcher(text);
            if (iso.matches()) {
                return instant(number(iso.group(1)), number(iso.group(2)), number(iso.group(3)),
                        number(iso.group(4)), number(iso.group(5)), number(iso.group(6)), nanos(iso.group(7)),
                        zone(iso.group(8)));
            }
            Matcher dayFirst = DAY_FIRST.matcher(text);
            if (dayFirst.matches()) {
                return instant(year(number(dayFirst.group(3))), number(dayFirst.group(2)), number(dayFirst.group(1)),
                        number(dayFirst.group(4)), number(dayFirst.group(5)), number(dayFirst.group(6)), 0,
                        zone(dayFirst.group(7)));
            }
            return textual(text);
        } catch (DateTimeException | NumberFormatException invalid) {
            return null;
        }
    }

    /** {@code [weekday,] day month year [time] [zone]} - in any order the tokens allow. */
    private static Instant textual(String text) {
        String[] tokens = text.split("[\\s,]+");
        int first = 0;
        int comma = text.indexOf(',');
        if (comma > 0 && !text.substring(0, comma).isBlank() && text.substring(0, comma).trim().chars().allMatch(
                c -> Character.isLetter(c) || c == '.')) {
            first = 1;
        }
        Integer month = null;
        List<Integer> numbers = new ArrayList<>();
        int hour = 0;
        int minute = 0;
        int second = 0;
        ZoneOffset zone = null;
        String meridiem = null;
        for (int i = first; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                continue;
            }
            Matcher time = TIME.matcher(token);
            if (time.matches()) {
                hour = number(time.group(1));
                minute = number(time.group(2));
                second = number(time.group(3));
                if (!time.group(4).isEmpty()) {
                    zone = zoneOrNull(time.group(4));
                }
            } else if (token.matches("\\d{1,4}\\.?")) {
                numbers.add(Integer.parseInt(token.replace(".", "")));
            } else if (token.matches("(?i)[ap]\\.?m\\.?")) {
                meridiem = token.substring(0, 1).toLowerCase(Locale.ROOT);
            } else if (zoneOrNull(token) != null) {
                zone = zoneOrNull(token);
            } else if (month == null) {
                month = month(token);
            }
        }
        if (month == null || numbers.size() < 2) {
            return null;
        }
        int yearIndex = numbers.get(0) > 31 ? 0 : 1;
        int year = year(numbers.get(yearIndex));
        int day = numbers.get(1 - yearIndex);
        if ("p".equals(meridiem) && hour < 12) {
            hour += 12;
        } else if ("a".equals(meridiem) && hour == 12) {
            hour = 0;
        }
        return instant(year, month, day, hour, minute, second, 0, zone == null ? ZoneOffset.UTC : zone);
    }

    private static Instant instant(int year, int month, int day, int hour, int minute, int second, int nanos,
            ZoneOffset zone) {
        return LocalDateTime.of(year, month, day, hour, minute, second, nanos).toInstant(zone);
    }

    private static Integer month(String token) {
        String normalized = Normalizer.normalize(token.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace(".", "");
        if (normalized.length() < 3 || !normalized.chars().allMatch(Character::isLetter)) {
            return null;
        }
        if (normalized.length() >= 4 && MONTHS.containsKey(normalized.substring(0, 4))) {
            return MONTHS.get(normalized.substring(0, 4));
        }
        return MONTHS.get(normalized.substring(0, 3));
    }

    /** A zone, UTC when there is none or it is unknown. */
    private static ZoneOffset zone(String text) {
        ZoneOffset zone = zoneOrNull(text.trim());
        return zone == null ? ZoneOffset.UTC : zone;
    }

    private static ZoneOffset zoneOrNull(String text) {
        if (text.isEmpty()) {
            return null;
        }
        Integer minutes = ZONES.get(text.toLowerCase(Locale.ROOT));
        if (minutes != null) {
            return ZoneOffset.ofTotalSeconds(minutes * 60);
        }
        Matcher offset = OFFSET.matcher(text.replace(" ", ""));
        if (!offset.matches()) {
            return null;
        }
        String digits = offset.group(2);
        int hours;
        int extraMinutes;
        if (offset.group(3) == null && digits.length() <= 2) {
            hours = Integer.parseInt(digits);
            extraMinutes = 0;
        } else {
            hours = Integer.parseInt(digits);
            extraMinutes = offset.group(3) == null ? 0 : Integer.parseInt(offset.group(3));
        }
        if (hours > 18 || extraMinutes > 59) {
            return null;
        }
        int sign = offset.group(1).equals("-") ? -1 : 1;
        return ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + extraMinutes * 60));
    }

    private static int year(int year) {
        if (year >= 100) {
            return year;
        }
        return year < 50 ? 2000 + year : 1900 + year;
    }

    private static int number(String digits) {
        return digits == null ? 0 : Integer.parseInt(digits);
    }

    /** {@code 123} as the fraction {@code .123}, in nanoseconds. */
    private static int nanos(String fraction) {
        if (fraction == null) {
            return 0;
        }
        return Integer.parseInt((fraction + "000000000").substring(0, 9));
    }
}
