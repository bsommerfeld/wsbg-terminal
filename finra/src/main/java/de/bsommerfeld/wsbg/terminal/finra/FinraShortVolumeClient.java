package de.bsommerfeld.wsbg.terminal.finra;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * FINRA's daily short-sale volume files
 * ({@code cdn.finra.org/equity/regsho/daily/CNMSshvol<yyyyMMdd>.txt}, probed
 * 2026-08-07): one keyless pipe-delimited file per trading day carrying EVERY
 * consolidated-tape symbol with its short volume and its total reported
 * volume.
 *
 * <p>The archive is the point. Unlike an options chain, which only ever
 * answers "now", these files reach back years - a name's short-share HISTORY
 * is available on the first request, with no warm-up period, which is what
 * makes the axis measurable against a name's own normal from day one.
 *
 * <p><b>Cost discipline:</b> a file is ~540 KB and covers all symbols, so it
 * is fetched ONCE per date and scanned line-wise for the one symbol asked
 * about - never parsed into a map of the whole market. Weekends are skipped
 * without a request; holidays answer 404 and are skipped on the reply.
 */
@Singleton
public class FinraShortVolumeClient {

    private static final Logger LOG = LoggerFactory.getLogger(FinraShortVolumeClient.class);

    static final String URL = "https://cdn.finra.org/equity/regsho/daily/CNMSshvol%s.txt";

    private static final DateTimeFormatter FILE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd");
    /** Bare US symbol, class shares included - nothing suffixed or indexed. */
    private static final Pattern US_TICKER = Pattern.compile("[A-Z]{1,5}(\\.[A-Z])?");
    /** Calendar days walked back at most while collecting sessions. */
    private static final int MAX_CALENDAR_REACH = 400;
    /** Trading days fetched in one shift of the walk - see {@link #recent}. */
    private static final int WALK_BATCH = 10;
    /** How many of a shift's files are on the wire at once. */
    private static final int WALK_FAN_WIDTH = 5;

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);

    /** Test/default: plain direct transport. */
    public FinraShortVolumeClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public FinraShortVolumeClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * One session's row for one symbol.
     *
     * @return the row, or empty on a weekend, a holiday, an unknown symbol or
     *         any transport failure - never {@code null}
     */
    public Optional<ShortVolumeDay> day(LocalDate date, String symbol) {
        if (date == null || symbol == null) return Optional.empty();
        String t = symbol.trim().toUpperCase(Locale.ROOT);
        if (!US_TICKER.matcher(t).matches()) return Optional.empty();
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return Optional.empty();
        }
        try {
            WebResponse r = fetcher.fetch(String.format(URL, date.format(FILE_DATE)),
                    Map.of(), requestTimeout);
            if (r == null || r.status() < 200 || r.status() >= 300) return Optional.empty();
            return row(r.body(), t, date);
        } catch (Exception e) {
            LOG.debug("[FINRA] {} {} failed: {}", t, date, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The most recent {@code sessions} rows for one symbol, OLDEST first.
     *
     * <p>Walks back day by day from {@code from}; a date without a file
     * (holiday) costs one request and is skipped. Stops at
     * {@value #MAX_CALENDAR_REACH} calendar days so a delisted or never-listed
     * symbol cannot walk the client into the archive's beginning.
     *
     * @param sessions how many sessions to collect
     */
    public List<ShortVolumeDay> recent(String symbol, LocalDate from, int sessions) {
        if (symbol == null || from == null || sessions <= 0) return List.of();
        List<ShortVolumeDay> out = new ArrayList<>(sessions);
        LocalDate d = from;
        int reach = 0;
        int misses = 0;
        ExecutorService pool = Executors.newFixedThreadPool(WALK_FAN_WIDTH, r -> {
            Thread t = new Thread(r, "finra-walk");
            t.setDaemon(true);
            return t;
        });
        try {
            walk:
            while (out.size() < sessions && reach < MAX_CALENDAR_REACH) {
                // One SHIFT of trading days is fetched side by side and then
                // read strictly newest-first, so the miss streak and the
                // stopping point are the same ones the day-by-day walk found -
                // only the waiting is shared. Serially this leg cost 4 min 05 s
                // of a dossier (2026-08-11), all of it one socket at a time.
                List<LocalDate> schub = new ArrayList<>(WALK_BATCH);
                while (schub.size() < WALK_BATCH && reach < MAX_CALENDAR_REACH) {
                    reach++;
                    if (d.getDayOfWeek() != DayOfWeek.SATURDAY
                            && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                        schub.add(d);
                    }
                    d = d.minusDays(1);
                }
                if (schub.isEmpty()) break;
                List<Future<Optional<ShortVolumeDay>>> futures = new ArrayList<>(schub.size());
                for (LocalDate tag : schub) futures.add(pool.submit(() -> day(tag, symbol)));
                for (Future<Optional<ShortVolumeDay>> f : futures) {
                    Optional<ShortVolumeDay> row;
                    try {
                        row = f.get();
                    } catch (ExecutionException e) {
                        row = Optional.empty();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break walk;
                    }
                    if (row.isPresent()) {
                        out.add(row.get());
                        misses = 0;
                        if (out.size() >= sessions) break walk;
                    } else if (++misses >= 10) {
                        // Ten straight trading days without a row is not a
                        // holiday streak - the symbol is simply not on these
                        // files. The rest of the shift is discarded unread.
                        break walk;
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        out.sort((a, b) -> a.date().compareTo(b.date()));
        return List.copyOf(out);
    }

    /**
     * Scans one file for one symbol. The format is
     * {@code Date|Symbol|ShortVolume|ShortExemptVolume|TotalVolume|Market};
     * the file is walked line-wise rather than split into a market-wide map,
     * because every caller wants exactly one row out of ~9,000.
     */
    private static Optional<ShortVolumeDay> row(String body, String symbol, LocalDate date) {
        String needle = "|" + symbol + "|";
        int from = 0;
        while (from < body.length()) {
            int nl = body.indexOf('\n', from);
            String line = nl < 0 ? body.substring(from) : body.substring(from, nl);
            from = nl < 0 ? body.length() : nl + 1;
            if (line.isEmpty() || !line.contains(needle)) continue;
            String[] p = line.split("\\|");
            if (p.length < 5 || !symbol.equals(p[1])) continue;
            try {
                double shortVolume = Double.parseDouble(p[2]);
                double total = Double.parseDouble(p[4]);
                if (total <= 0) return Optional.empty();
                return Optional.of(new ShortVolumeDay(date, shortVolume, total));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
