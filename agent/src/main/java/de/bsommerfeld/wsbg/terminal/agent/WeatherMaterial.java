package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.briefing.FnRssClient;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.AdhocStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.AnalystActionStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.BetStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CbDateStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CryptoStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DepthStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.EconOutcomeStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.EventReviewStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.IndexStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MacroStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MoverStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.NewsStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OutlookStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RateStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RoomPulse;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ShortVolStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SocialStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TopNewsStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TrendingCoin;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WatchlistStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldEventStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldStats;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Deterministic formatting of the frozen day stats into the labelled material
 * blocks the Abendausgabe passes are written FROM — numbers are never run
 * through the model to be condensed (a 4B model mangles figures it re-tells;
 * the wire stories are the only model-condensed material). Block labels are
 * the DeepDive convention: terse English section names with a source /
 * trust marker; the model writes the report in the user language regardless.
 *
 * <p>Every block renders empty when its stats are absent, so a failed leg
 * silently vanishes from the material instead of feeding the model an empty
 * header to hallucinate under.
 */
final class WeatherMaterial {

    /** Ceiling for the assembled stat blocks — the wire digest gets the rest of the budget. */
    static final int MAX_BLOCK_CHARS = 5500;

    // Section ordinals of the forecast skeleton (must match WeatherCharts'
    // anchors and the service's heading literals).
    static final int SEC_PICTURE = 0;
    static final int SEC_MORNING = 1;
    static final int SEC_MIDDAY = 2;
    static final int SEC_EVENING = 3;
    static final int SEC_OUTLOOK = 4;
    static final int SECTION_COUNT = 5;

    private WeatherMaterial() {
    }

    /**
     * The Redaktion's section shelves (the KI-DD workspace pattern): each of
     * the five forecast sections gets ONLY the material it may draw from —
     * the window sections their own wire digest plus the disclosures, macro
     * lines and analyst actions that fell into THAT window (by timestamp),
     * the big picture the whole condensed day plus the day aggregates, the
     * outlook strictly tomorrow's schedule plus the colour. Duplication
     * across shelves is deliberate and free — every author call is
     * independent. The DATE line gives the examiner today's ISO date so a
     * written-out date in the prose can reconcile.
     */
    static String[] sectionShelves(WeatherStatsCollector.Stats stats, LocalDate today,
            String wireMorning, String wireMidday, String wireEvening) {
        WorldStats w = stats.world();
        List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DaypartStat> dayparts =
                w == null ? List.of() : w.dayparts();
        String date = "DATE: " + today + " (" + today.getDayOfWeek() + ")";

        List<AdhocStat> adhocs = w == null ? List.of() : w.adhocs();
        List<AnalystActionStat> analyst = w == null ? List.of() : w.analystActions();
        List<MacroStat> actuals = w == null ? List.of() : w.macroActuals();
        List<MacroStat> events = w == null ? List.of() : w.macroEvents();
        List<EconOutcomeStat> outcomes = w == null ? List.of() : w.econOutcomes();
        List<PressReviewStat> press = w == null ? List.of() : w.pressReview();
        List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerNewsStat> tickerNews =
                w == null ? List.of() : w.tickerNews();

        String[] shelves = new String[SECTION_COUNT];

        // The big picture gets the day's AGGREGATES, deliberately NOT the wire
        // digests — with the full day on its shelf the first live run recapped
        // every story and the window sections then repeated it near-verbatim.
        // The timeline's lead subjects + most-discussed carry the arc; the
        // detail lives in the window sections.
        StringBuilder picture = new StringBuilder(date);
        append(picture, timelineBlock(dayparts));
        append(picture, pulseBlock(w == null ? null : w.pulse()));
        append(picture, worldEventsBlock(w == null ? List.of() : w.worldEvents()));
        append(picture, topNewsBlock(w == null ? List.of() : w.topNews()));
        append(picture, hazardsBlock(w == null ? List.of() : w.hazards()));
        append(picture, worldWeatherBlock(w == null ? List.of() : w.worldWeather()));
        append(picture, tickersBlock(stats.tickers()));
        append(picture, marketsBlock(stats.indices()));
        append(picture, sectorsBlock(w == null ? List.of() : w.sectors()));
        append(picture, ratesBlock(w == null ? List.of() : w.rates()));
        append(picture, sentimentBlock(stats.sentiment(), w == null ? null : w.putCall()));
        shelves[SEC_PICTURE] = picture.toString();

        StringBuilder morning = new StringBuilder(date);
        append(morning, windowLine(daypart(dayparts, "MORNING"), "morning"));
        append(morning, wireBlock("this window (morning)", wireMorning));
        append(morning, pressReviewBlock("this window (morning)",
                inWindow(press, PressReviewStat::time, 0, true)));
        append(morning, tickerNewsBlock("this window (morning)", inWindow(tickerNews,
                de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerNewsStat::time,
                0, true)));
        append(morning, tickersBlock(stats.tickers()));
        append(morning, sectorsBlock(w == null ? List.of() : w.sectors()));
        append(morning, adhocBlock(inWindow(adhocs, AdhocStat::time, 0, true)));
        append(morning, macroBlock(inWindow(actuals, MacroStat::time, 0, true),
                inWindow(events, MacroStat::time, 0, true)));
        append(morning, econOutcomesBlock(inWindow(outcomes, EconOutcomeStat::time, 0, true)));
        append(morning, analystBlock(inWindow(analyst, AnalystActionStat::time, 0, false)));
        shelves[SEC_MORNING] = morning.toString();

        StringBuilder midday = new StringBuilder(date);
        append(midday, windowLine(daypart(dayparts, "MIDDAY"), "midday"));
        append(midday, wireBlock("this window (midday)", wireMidday));
        append(midday, pressReviewBlock("this window (midday)",
                inWindow(press, PressReviewStat::time, 1, false)));
        append(midday, tickerNewsBlock("this window (midday)", inWindow(tickerNews,
                de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerNewsStat::time,
                1, false)));
        append(midday, marketsBlock(stats.indices()));
        append(midday, sectorsBlock(w == null ? List.of() : w.sectors()));
        append(midday, analystBlock(inWindow(analyst, AnalystActionStat::time, 1, true)));
        append(midday, adhocBlock(inWindow(adhocs, AdhocStat::time, 1, false)));
        append(midday, macroBlock(inWindow(actuals, MacroStat::time, 1, false),
                inWindow(events, MacroStat::time, 1, false)));
        append(midday, econOutcomesBlock(inWindow(outcomes, EconOutcomeStat::time, 1, false)));
        append(midday, depthBlock(w == null ? List.of() : w.depth()));
        shelves[SEC_MIDDAY] = midday.toString();

        StringBuilder evening = new StringBuilder(date);
        append(evening, windowLine(daypart(dayparts, "EVENING"), "evening"));
        append(evening, wireBlock("this window (evening)", wireEvening));
        append(evening, pressReviewBlock("this window (evening)",
                inWindow(press, PressReviewStat::time, 2, false)));
        append(evening, tickerNewsBlock("this window (evening)", inWindow(tickerNews,
                de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerNewsStat::time,
                2, false)));
        append(evening, adhocBlock(inWindow(adhocs, AdhocStat::time, 2, false)));
        append(evening, macroBlock(inWindow(actuals, MacroStat::time, 2, false),
                inWindow(events, MacroStat::time, 2, false)));
        append(evening, econOutcomesBlock(inWindow(outcomes, EconOutcomeStat::time, 2, false)));
        append(evening, eventReviewsBlock(w == null ? List.of() : w.eventReviews()));
        append(evening, analystBlock(inWindow(analyst, AnalystActionStat::time, 2, false)));
        append(evening, moversBlock(w == null ? List.of() : w.movers()));
        append(evening, sectorsBlock(w == null ? List.of() : w.sectors()));
        append(evening, shortVolumeBlock(w == null ? List.of() : w.shortVolume()));
        append(evening, socialBlock(w == null ? List.of() : w.social()));
        append(evening, cryptoBlock(w == null ? null : w.crypto()));
        append(evening, betsBlock(w == null ? List.of() : w.bets()));
        append(evening, sentimentBlock(stats.sentiment(), w == null ? null : w.putCall()));
        append(evening, overnightBlock(w == null ? List.of() : w.overnight()));
        append(evening, pressBlock(w == null ? null : w.pressDigest()));
        append(evening, houseBlock(w == null ? List.of() : w.watchlist(),
                w == null ? List.of() : w.deepDives()));
        shelves[SEC_EVENING] = evening.toString();

        StringBuilder outlook = new StringBuilder(date);
        String outlookBlock = outlookBlock(w == null ? List.of() : w.outlook());
        String cbBlock = cbDatesBlock(w == null ? List.of() : w.cbDates());
        if (!cbBlock.isEmpty()) {
            outlookBlock = outlookBlock.isEmpty() ? cbBlock : outlookBlock + "\n\n" + cbBlock;
        }
        String colourBlock = colourBlock(w);
        if (!outlookBlock.isEmpty() || !colourBlock.isEmpty()) {
            // Tomorrow's ISO date, so a written-out "am 14. Juli 2026" can
            // reconcile — the first live run's outlook died on a DATE
            // objection because only TODAY's date was on the shelf.
            LocalDate tomorrow = today.plusDays(1);
            append(outlook, "TOMORROW'S DATE: " + tomorrow + " (" + tomorrow.getDayOfWeek() + ")");
            append(outlook, outlookBlock);
            append(outlook, hazardsBlock(w == null ? List.of() : w.hazards()));
            append(outlook, worldWeatherBlock(w == null ? List.of() : w.worldWeather()));
            // Today's sector table rides along so the docket can be tied to
            // the sectors it measures ("CPI misst morgen die zinssensitiven
            // Sektoren, die heute schon schwächelten") — the tie is the
            // model's read, the numbers stay today's verified closes.
            append(outlook, sectorsBlock(w == null ? List.of() : w.sectors()));
            append(outlook, colourBlock);
        }
        shelves[SEC_OUTLOOK] = outlook.toString();

        return shelves;
    }

    /** A shelf whose only content is the DATE line carries nothing to write from. */
    static boolean shelfEmpty(String shelf) {
        return shelf == null || shelf.isBlank() || !shelf.strip().contains("\n\n");
    }

    private static String wireBlock(String scope, String wire) {
        if (wire == null || wire.isBlank()) return "";
        return "WIRE STORIES " + scope + " (the cage, condensed):\n" + wire.strip();
    }

    private static de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DaypartStat daypart(
            List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DaypartStat> dayparts,
            String key) {
        for (var d : dayparts) {
            if (key.equals(d.key())) return d;
        }
        return null;
    }

    /** The window section's own deterministic lead line — its mood and protagonist. */
    static String windowLine(de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DaypartStat d,
            String label) {
        if (d == null) return "";
        StringBuilder sb = new StringBuilder("THIS WINDOW (").append(label)
                .append(", deterministic): ").append(d.lines()).append(" lines");
        if (d.lines() > 0) {
            sb.append(" (").append(d.bullish()).append(" bullish-leaning / ")
                    .append(d.bearish()).append(" bearish-leaning");
            if (d.red() > 0) sb.append(", ").append(d.red()).append(" red-flagged");
            sb.append(')');
            if (d.note() != null && !d.note().isBlank()) {
                sb.append("; lead subject ").append(d.note());
            }
        } else {
            sb.append(" (the cage was quiet)");
        }
        return sb.toString();
    }

    /**
     * Items whose {@code HH:mm} timestamp falls into the given window
     * (0 = morning &lt;12h, 1 = midday 12-16h, 2 = evening ≥16h);
     * {@code untimedToo} routes timestamp-less items here (each list has ONE
     * home window so an untimed item appears exactly once across the shelves).
     */
    private static <T> List<T> inWindow(List<T> items, Function<T, String> time,
            int window, boolean untimedToo) {
        List<T> out = new ArrayList<>();
        for (T item : items) {
            int w = windowOf(time.apply(item));
            if (w == window || (w < 0 && untimedToo)) out.add(item);
        }
        return out;
    }

    /**
     * The press items of ONE day-part window — the service's weave loop reads
     * the same routing the shelves use (morning is the untimed items' home
     * window, mirroring {@link #sectionShelves}).
     */
    static List<PressReviewStat> pressInWindow(List<PressReviewStat> press, int window) {
        if (press == null || press.isEmpty()) return List.of();
        return inWindow(press, PressReviewStat::time, window, window == 0);
    }

    /** "HH:mm" → window ordinal, or -1 when absent/unparseable. */
    static int windowOf(String hhmm) {
        if (hhmm == null) return -1;
        int colon = hhmm.indexOf(':');
        if (colon <= 0) return -1;
        try {
            int hour = Integer.parseInt(hhmm.substring(0, colon).strip());
            if (hour < 0 || hour > 23) return -1;
            if (hour < 12) return 0;
            return hour < 16 ? 1 : 2;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** All stat blocks in reading order, empty sections skipped, hard-capped. */
    static String blocks(WeatherStatsCollector.Stats stats) {
        StringBuilder sb = new StringBuilder();
        WorldStats w = stats.world();
        append(sb, timelineBlock(w == null ? List.of() : w.dayparts()));
        append(sb, pulseBlock(w == null ? null : w.pulse()));
        append(sb, tickersBlock(stats.tickers()));
        append(sb, marketsBlock(stats.indices()));
        append(sb, sectorsBlock(w == null ? List.of() : w.sectors()));
        append(sb, ratesBlock(w == null ? List.of() : w.rates()));
        append(sb, sentimentBlock(stats.sentiment(), w == null ? null : w.putCall()));
        append(sb, macroBlock(w == null ? List.of() : w.macroActuals(),
                w == null ? List.of() : w.macroEvents()));
        append(sb, econOutcomesBlock(w == null ? List.of() : w.econOutcomes()));
        append(sb, eventReviewsBlock(w == null ? List.of() : w.eventReviews()));
        append(sb, worldEventsBlock(w == null ? List.of() : w.worldEvents()));
        append(sb, topNewsBlock(w == null ? List.of() : w.topNews()));
        append(sb, hazardsBlock(w == null ? List.of() : w.hazards()));
        append(sb, worldWeatherBlock(w == null ? List.of() : w.worldWeather()));
        append(sb, adhocBlock(w == null ? List.of() : w.adhocs()));
        append(sb, analystBlock(w == null ? List.of() : w.analystActions()));
        append(sb, pressReviewBlock("of the day", w == null ? List.of() : w.pressReview()));
        append(sb, tickerNewsBlock("of the day", w == null ? List.of() : w.tickerNews()));
        append(sb, pressBlock(w == null ? null : w.pressDigest()));
        append(sb, moversBlock(w == null ? List.of() : w.movers()));
        append(sb, shortVolumeBlock(w == null ? List.of() : w.shortVolume()));
        append(sb, socialBlock(w == null ? List.of() : w.social()));
        append(sb, cryptoBlock(w == null ? null : w.crypto()));
        append(sb, betsBlock(w == null ? List.of() : w.bets()));
        append(sb, depthBlock(w == null ? List.of() : w.depth()));
        append(sb, houseBlock(w == null ? List.of() : w.watchlist(),
                w == null ? List.of() : w.deepDives()));
        append(sb, newsBlock(stats.news()));
        append(sb, overnightBlock(w == null ? List.of() : w.overnight()));
        append(sb, outlookBlock(w == null ? List.of() : w.outlook()));
        append(sb, cbDatesBlock(w == null ? List.of() : w.cbDates()));
        append(sb, colourBlock(w));
        String out = sb.toString().strip();
        return out.length() > MAX_BLOCK_CHARS ? out.substring(0, MAX_BLOCK_CHARS) : out;
    }

    /**
     * The dpa-AFX press narrative, condensed DETERMINISTICALLY (titles + teaser
     * heads) — attributed background, never the model's own observation.
     */
    static String pressText(List<FnRssClient.PressItem> items) {
        if (items == null || items.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (FnRssClient.PressItem item : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("„").append(item.title()).append("“");
            String teaser = item.teaser();
            if (teaser != null && !teaser.isBlank()) {
                String t = teaser.strip();
                sb.append(" — ").append(t.length() > 240 ? t.substring(0, 240) + "…" : t);
            }
        }
        String out = sb.toString();
        return out.length() > 900 ? out.substring(0, 900) + "…" : out;
    }

    // --- individual blocks (package-private for tests) ---------------------

    /**
     * The chronological skeleton the forecast narrative hangs on — one line per
     * elapsed day part with its window mood and protagonist, so the model can
     * tell morning, midday and evening apart without re-deriving times from
     * the wire digest.
     */
    static String timelineBlock(List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DaypartStat> dayparts) {
        if (dayparts == null || dayparts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "DAY TIMELINE (deterministic; morning <12h, midday 12-16h, evening >16h local):");
        for (var d : dayparts) {
            if ("TOMORROW".equals(d.key())) continue;
            String label = switch (d.key()) {
                case "MORNING" -> "morning";
                case "MIDDAY" -> "midday";
                case "EVENING" -> "evening";
                default -> d.key().toLowerCase(Locale.ROOT);
            };
            sb.append("\n- ").append(label).append(": ").append(d.lines()).append(" lines");
            if (d.lines() > 0) {
                sb.append(" (").append(d.bullish()).append(" bullish-leaning / ")
                        .append(d.bearish()).append(" bearish-leaning");
                if (d.red() > 0) sb.append(", ").append(d.red()).append(" red-flagged");
                sb.append(')');
                if (d.note() != null && !d.note().isBlank()) {
                    sb.append("; lead subject ").append(d.note());
                }
            } else {
                sb.append(" (the cage was quiet)");
            }
        }
        return sb.toString();
    }

    /**
     * The literal sky over the market-relevant places (Open-Meteo, verified)
     * — the user's "viele handeln auf Wetter": each place carries its market
     * role so the model knows WHY the sky matters there. Tying the sky to a
     * price move stays an attributed desk reading, never a fact.
     */
    static String worldWeatherBlock(
            List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PlaceWeatherStat> places) {
        if (places == null || places.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "WORLD WEATHER over market-relevant places (verified, Open-Meteo):");
        for (var p : places) {
            sb.append("\n- ").append(p.place());
            if (p.role() != null && !p.role().isBlank()) {
                sb.append(" (").append(p.role()).append(')');
            }
            sb.append(':');
            if (p.tempC() != null) sb.append(' ').append(num(p.tempC(), 1)).append(" °C");
            if (p.word() != null && !p.word().isBlank()) sb.append(", ").append(p.word());
            if (p.windKmh() != null && p.windKmh() >= 40) {
                sb.append(", Wind ").append(num(p.windKmh(), 0)).append(" km/h");
            }
            if (p.tomorrowMaxC() != null) {
                sb.append("; tomorrow up to ").append(num(p.tomorrowMaxC(), 0)).append(" °C");
                if (p.tomorrowWord() != null && !p.tomorrowWord().isBlank()) {
                    sb.append(" (").append(p.tomorrowWord()).append(')');
                }
            }
        }
        return sb.toString();
    }

    /**
     * Physical-world hazards with a market shadow (NHC storms / USGS quakes /
     * FAA aviation, verified) — reported as events; any market consequence is
     * the desk's attributed reading.
     */
    static String hazardsBlock(
            List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HazardStat> hazards) {
        if (hazards == null || hazards.isEmpty()) return "";
        // "CURRENT state, TODAY" is load-bearing: on the outlook shelf the
        // TOMORROW date line otherwise re-dates these events to tomorrow
        // (live smoke 2: "US-Luftverkehr meldet für den 15.07. ..." — an FAA
        // ground delay is an Ist-Zustand, never a forecast).
        StringBuilder sb = new StringBuilder(
                "HAZARDS (verified CURRENT state as of TODAY — report as today's"
                        + " situation, never as tomorrow's forecast; NHC tropical storms"
                        + " / USGS quakes / FAA US aviation):");
        for (var h : hazards) {
            sb.append("\n- [").append(hazardKindWord(h.kind())).append("] ").append(h.text());
        }
        return sb.toString();
    }

    private static String hazardKindWord(String kind) {
        if (kind == null) return "Ereignis";
        return switch (kind) {
            case "STORM" -> "Sturm";
            case "QUAKE" -> "Beben";
            case "AVIATION" -> "US-Luftverkehr";
            default -> kind;
        };
    }

    /**
     * The general market press review of ONE day-part window (CNBC/
     * MarketWatch/WSJ/Investing + n-tv/Spiegel/Handelsblatt/WiWo) — timed
     * press headlines, attributed. This is the "why the tape moved" layer:
     * the reported cause sits beside the window's market data, so the model
     * links them instead of speculating.
     */
    static String pressReviewBlock(String scope, List<PressReviewStat> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("MARKET PRESS ").append(scope)
                .append(" (ATTRIBUTED — the press's reading, cite the outlet):");
        for (PressReviewStat p : items) {
            sb.append("\n- ");
            if (p.time() != null) sb.append(p.time()).append(' ');
            sb.append('[').append(p.source()).append("] ").append(p.title());
            if (p.teaser() != null && !p.teaser().isBlank()) {
                String t = p.teaser().strip();
                sb.append(" — ").append(t.length() > 160 ? t.substring(0, 160) + "…" : t);
            }
        }
        return sb.toString();
    }

    /**
     * Fresh triangulated press on the day's TOP tickers (the KI-DD's 7-source
     * aggregator) — the per-paper catalyst feed beside the general market
     * press, attributed to its outlet.
     */
    static String tickerNewsBlock(String scope,
            List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerNewsStat> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("PRESS ON THE DAY'S TOP PAPERS ").append(scope)
                .append(" (house news triangulation — ATTRIBUTED, cite the outlet):");
        for (var n : items) {
            sb.append("\n- ");
            if (n.time() != null) sb.append(n.time()).append(' ');
            sb.append('[').append(n.ticker()).append("] ").append(n.title());
            if (n.publisher() != null) sb.append(" · ").append(n.publisher());
        }
        return sb.toString();
    }

    static String pulseBlock(RoomPulse p) {
        if (p == null) return "";
        StringBuilder sb = new StringBuilder("ROOM PULSE (deterministic aggregate): ");
        sb.append(p.bullish() + p.bearish() + p.neutral()).append(" lines from ")
                .append(p.distinctSubjects()).append(" subjects; sentiment split ")
                .append(p.bullish()).append(" bullish-leaning / ")
                .append(p.bearish()).append(" bearish-leaning / ")
                .append(p.neutral()).append(" neutral-mixed; ")
                .append(p.redCount()).append(" red-flagged");
        if (p.busiestHour() != null) {
            sb.append("; busiest hour ").append(p.busiestHour()).append(":00");
        }
        return sb.append('.').toString();
    }

    static String tickersBlock(List<TickerStat> tickers) {
        if (tickers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("MOST DISCUSSED (verified, quotes as the wire showed them):");
        for (TickerStat t : tickers) {
            sb.append("\n- ").append(t.name());
            if (!t.name().equalsIgnoreCase(t.ticker())) sb.append(" (").append(t.ticker()).append(')');
            sb.append(": ").append(t.headlineCount()).append(" lines");
            if (t.importantCount() > 0) sb.append(" (").append(t.importantCount()).append(" red)");
            if (t.price() != null) {
                sb.append(", ").append(money(t.price(), t.currency()));
            }
            if (t.changePercent() != null) sb.append(", ").append(pct(t.changePercent()));
            if (t.turnoverEur() != null) {
                sb.append(", Tradegate turnover ").append(compact(t.turnoverEur())).append(" EUR");
            }
        }
        return sb.toString();
    }

    static String marketsBlock(List<IndexStat> indices) {
        return tileLine("MARKETS (verified)", indices);
    }

    static String sectorsBlock(List<IndexStat> sectors) {
        if (sectors.isEmpty()) return "";
        List<IndexStat> sorted = sectors.stream()
                .filter(s -> s.changePercent() != null)
                .sorted((a, b) -> Double.compare(b.changePercent(), a.changePercent()))
                .toList();
        if (sorted.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("US SECTOR ROTATION (verified, sector ETFs): strongest ");
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sorted.get(i).name()).append(' ').append(pct(sorted.get(i).changePercent()));
        }
        sb.append("; weakest ");
        for (int i = Math.max(0, sorted.size() - 3); i < sorted.size(); i++) {
            if (i > Math.max(0, sorted.size() - 3)) sb.append(", ");
            sb.append(sorted.get(i).name()).append(' ').append(pct(sorted.get(i).changePercent()));
        }
        return sb.append('.').toString();
    }

    static String ratesBlock(List<RateStat> rates) {
        if (rates.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("RATES (verified): ");
        boolean first = true;
        for (RateStat r : rates) {
            if (!first) sb.append(" · ");
            first = false;
            sb.append(r.name()).append(' ').append(num(r.percent(), 2)).append(" %");
            if (r.previousPercent() != null) {
                sb.append(" (prior ").append(num(r.previousPercent(), 2)).append(" %");
                if (r.dateIso() != null) sb.append(", as of ").append(r.dateIso());
                sb.append(')');
            } else if (r.dateIso() != null) {
                sb.append(" (as of ").append(r.dateIso()).append(')');
            }
        }
        return sb.toString();
    }

    static String sentimentBlock(SentimentStat s, de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PutCallStat pc) {
        if (s == null && pc == null) return "";
        StringBuilder sb = new StringBuilder("SENTIMENT GAUGES (verified): ");
        boolean any = false;
        if (s != null && s.score() != null) {
            sb.append("US Fear & Greed ").append(s.score());
            if (s.band() != null) sb.append(" (").append(bandWord(s.band())).append(')');
            if (s.previousClose() != null) sb.append(", prior day ").append(s.previousClose());
            any = true;
        }
        if (s != null && s.score() != null && !s.components().isEmpty()) {
            sb.append("; components:");
            for (var c : s.components()) {
                sb.append(' ').append(c.key().replace('_', ' ')).append(' ')
                        .append(c.score()).append(',');
            }
            sb.setLength(sb.length() - 1);
        }
        if (s != null && s.cryptoScore() != null) {
            if (any) sb.append(" · ");
            sb.append("Crypto Fear & Greed ").append(s.cryptoScore());
            if (s.cryptoBand() != null) sb.append(" (").append(bandWord(s.cryptoBand())).append(')');
            any = true;
        }
        if (pc != null && (pc.total() != null || pc.equity() != null)) {
            if (any) sb.append(" · ");
            sb.append("CBOE put/call");
            if (pc.dateIso() != null) sb.append(' ').append(pc.dateIso());
            sb.append(':');
            if (pc.total() != null) sb.append(" total ").append(num(pc.total(), 2));
            if (pc.equity() != null) sb.append(", equity ").append(num(pc.equity(), 2));
            if (pc.index() != null) sb.append(", index ").append(num(pc.index(), 2));
            any = true;
        }
        return any ? sb.toString() : "";
    }

    static String macroBlock(List<MacroStat> actuals, List<MacroStat> events) {
        if (actuals.isEmpty() && events.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("MACRO (verified actuals + today's docket):");
        for (MacroStat a : actuals) {
            sb.append("\n- [").append(a.source()).append("] ").append(a.title());
        }
        for (MacroStat e : events) {
            sb.append("\n- [docket ").append(e.source());
            if (e.time() != null) sb.append(' ').append(e.time());
            sb.append("] ").append(e.title());
            if (e.impact() != null && !e.impact().isBlank()) sb.append(" (").append(e.impact());
            if (e.forecast() != null && !e.forecast().isBlank()) {
                sb.append("; forecast ").append(e.forecast());
            }
            if (e.previous() != null && !e.previous().isBlank()) {
                sb.append("; previous ").append(e.previous());
            }
            if (e.impact() != null && !e.impact().isBlank()) sb.append(')');
        }
        return sb.toString();
    }

    /**
     * The released numbers themselves — actual vs forecast vs previous, with a
     * deterministic direction word so the model never computes the surprise.
     */
    static String econOutcomesBlock(List<EconOutcomeStat> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "MACRO OUTCOMES (verified, released figures — actual vs forecast):");
        for (EconOutcomeStat o : outcomes) {
            sb.append("\n- ");
            if (o.time() != null) sb.append(o.time()).append(' ');
            sb.append('[').append(o.country()).append("] ").append(o.title()).append(": actual ")
                    .append(figure(o.actual(), o.unit()));
            if (o.forecast() != null) {
                sb.append(" (forecast ").append(figure(o.forecast(), o.unit()));
                if (o.previous() != null) {
                    sb.append(", previous ").append(figure(o.previous(), o.unit()));
                }
                sb.append(") — ").append(surpriseWord(o.actual(), o.forecast()));
            } else if (o.previous() != null) {
                sb.append(" (previous ").append(figure(o.previous(), o.unit())).append(')');
            }
        }
        return sb.toString();
    }

    /** Deterministic surprise direction; the reading (good/bad) stays with the model's material. */
    static String surpriseWord(double actual, double forecast) {
        double tolerance = Math.max(Math.abs(forecast) * 0.005, 1e-9);
        if (actual > forecast + tolerance) return "above forecast";
        if (actual < forecast - tolerance) return "below forecast";
        return "in line with forecast";
    }

    /** How the press read the day's numbers — search-found titles, attributed. */
    static String eventReviewsBlock(List<EventReviewStat> reviews) {
        if (reviews == null || reviews.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "PRESS ON TODAY'S DATA (web-search titles — ATTRIBUTED, the press's reading):");
        for (EventReviewStat r : reviews) {
            sb.append("\n- ").append(r.event()).append(':');
            for (String headline : r.headlines()) {
                sb.append("\n  · ").append(headline);
            }
        }
        return sb.toString();
    }

    /**
     * The world outside the tape (Wikipedia Current Events, EN, attributed) —
     * background for the Großwetterlage; tied to markets only where the
     * market material itself does.
     */
    /**
     * The ARD desk's top news of the day (Tagesschau api2u) — the press's
     * account of what mattered in the world and the German economy today,
     * strictly attributed. Homepage-ranked stories lead.
     */
    static String topNewsBlock(List<TopNewsStat> news) {
        if (news == null || news.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "TOP NEWS of the day (Tagesschau/ARD — ATTRIBUTED press, cite as the press's account):");
        for (TopNewsStat n : news) {
            sb.append("\n- ");
            if (n.time() != null) sb.append(n.time()).append(' ');
            if (n.breaking()) sb.append("(breaking) ");
            if (n.topline() != null && !n.topline().isBlank()) {
                sb.append(n.topline()).append(": ");
            }
            sb.append(n.title());
            if (n.firstSentence() != null && !n.firstSentence().isBlank()) {
                String t = n.firstSentence().strip();
                sb.append(" — ").append(t.length() > 160 ? t.substring(0, 160) + "…" : t);
            }
        }
        return sb.toString();
    }

    static String worldEventsBlock(List<WorldEventStat> events) {
        if (events == null || events.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "WORLD TODAY (Wikipedia Current Events, attributed background):");
        for (WorldEventStat e : events) {
            sb.append("\n- [").append(e.category()).append("] ").append(e.text());
            if (e.source() != null && !e.source().isBlank()) {
                sb.append(" (").append(e.source()).append(')');
            }
        }
        return sb.toString();
    }

    /** The hard forward anchors: the next rate decisions on both sides of the Atlantic. */
    static String cbDatesBlock(List<CbDateStat> cbDates) {
        if (cbDates == null || cbDates.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("NEXT RATE DECISIONS (verified): ");
        boolean first = true;
        for (CbDateStat c : cbDates) {
            if (!first) sb.append(" · ");
            first = false;
            sb.append(c.title()).append(' ').append(c.dateIso());
        }
        return sb.toString();
    }

    /** "0,3 %" / "2,44" / "23,7 Mrd" — unit-aware, German grouping like every other block. */
    static String figure(double v, String unit) {
        double abs = Math.abs(v);
        String n;
        if (abs >= 1e9) n = num(v / 1e9, 1) + " Mrd";
        else if (abs >= 1e6) n = num(v / 1e6, 1) + " Mio";
        else n = num(v, abs >= 100 ? 0 : 2);
        if (unit == null || unit.isBlank()) return n;
        return "%".equals(unit) ? n + " %" : n + " " + unit;
    }

    static String adhocBlock(List<AdhocStat> adhocs) {
        if (adhocs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "GERMANY AD-HOC DISCLOSURES (verified, EQS via finanznachrichten):");
        for (AdhocStat a : adhocs) {
            sb.append("\n- ");
            if (a.time() != null) sb.append(a.time()).append(' ');
            sb.append(a.title());
            if (a.kaefigTicker() != null) {
                sb.append(" [discussed in the room today: ").append(a.kaefigTicker()).append(']');
            }
        }
        return sb.toString();
    }

    static String analystBlock(List<AnalystActionStat> actions) {
        if (actions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("ANALYST ACTIONS (verified titles, dpa-AFX):");
        for (AnalystActionStat a : actions) {
            sb.append("\n- ");
            if (a.time() != null) sb.append(a.time()).append(' ');
            sb.append(a.title());
        }
        return sb.toString();
    }

    static String pressBlock(String pressDigest) {
        if (pressDigest == null || pressDigest.isBlank()) return "";
        return "PRESS EOD (dpa-AFX market reports — ATTRIBUTED background, cite as the press's view):\n"
                + pressDigest;
    }

    static String moversBlock(List<MoverStat> movers) {
        if (movers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("US MOVERS (verified, Yahoo screeners):");
        appendMoverKind(sb, movers, "GAINER", "gainers");
        appendMoverKind(sb, movers, "LOSER", "losers");
        appendMoverKind(sb, movers, "ACTIVE", "most active");
        return sb.toString();
    }

    private static void appendMoverKind(StringBuilder sb, List<MoverStat> movers,
            String kind, String label) {
        List<MoverStat> of = movers.stream().filter(m -> kind.equals(m.kind())).toList();
        if (of.isEmpty()) return;
        sb.append("\n- ").append(label).append(": ");
        boolean first = true;
        for (MoverStat m : of) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(m.name() == null || m.name().isBlank() ? m.symbol() : m.name());
            if (m.changePercent() != null) sb.append(' ').append(pct(m.changePercent()));
            if (m.inKaefig()) sb.append(" [also discussed in the room]");
        }
    }

    static String shortVolumeBlock(List<ShortVolStat> shortVolume) {
        if (shortVolume.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "SHORT VOLUME of room tickers (FINRA; short VOLUME is not short interest"
                        + " — often market-maker hedging, report, don't conclude): ");
        boolean first = true;
        for (ShortVolStat s : shortVolume) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(s.symbol()).append(' ').append(num(s.shortPercent(), 0)).append(" %");
        }
        if (shortVolume.get(0).dateIso() != null) {
            sb.append(" (").append(shortVolume.get(0).dateIso()).append(')');
        }
        return sb.toString();
    }

    static String socialBlock(List<SocialStat> social) {
        if (social.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "SOCIAL PULSE next door (ApeWisdom over r/wallstreetbets & co):");
        for (SocialStat s : social) {
            sb.append("\n- #").append(s.rank()).append(' ')
                    .append(s.name() == null || s.name().isBlank() ? s.ticker() : s.name())
                    .append(" (").append(s.ticker()).append("), ")
                    .append(s.mentions()).append(" mentions");
            if (s.rankClimb() != null && s.rankClimb() > 0) {
                sb.append(", climbed ").append(s.rankClimb()).append(" ranks in 24h");
            }
        }
        return sb.toString();
    }

    static String cryptoBlock(CryptoStat c) {
        if (c == null) return "";
        StringBuilder sb = new StringBuilder("CRYPTO (verified): ");
        boolean any = false;
        if (c.marketCapUsd() != null) {
            sb.append("total market cap ").append(compact(Math.round(c.marketCapUsd()))).append(" USD");
            if (c.mcapChangePercent() != null) sb.append(" (").append(pct(c.mcapChangePercent())).append(" 24h)");
            any = true;
        }
        if (c.btcDominance() != null) {
            if (any) sb.append(" · ");
            sb.append("BTC dominance ").append(num(c.btcDominance(), 1)).append(" %");
            any = true;
        }
        if (c.fundingRatePercent() != null) {
            if (any) sb.append(" · ");
            sb.append("BTC perp funding ").append(num(c.fundingRatePercent(), 4)).append(" %/8h");
            any = true;
        }
        if (c.dvol() != null) {
            if (any) sb.append(" · ");
            sb.append("DVOL (BTC vol index) ").append(num(c.dvol(), 0));
            any = true;
        }
        if (!c.trending().isEmpty()) {
            sb.append("\n- trending coins: ");
            boolean first = true;
            for (TrendingCoin t : c.trending()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(t.name());
                if (t.changePercent() != null) sb.append(' ').append(pct(t.changePercent()));
            }
            any = true;
        }
        return any ? sb.toString() : "";
    }

    static String betsBlock(List<BetStat> bets) {
        if (bets.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "PREDICTION MARKETS (Polymarket, real-money odds — attribute as betting odds):");
        for (BetStat b : bets) {
            sb.append("\n- \"").append(b.question()).append("\": ").append(b.outcome());
            if (b.probabilityPercent() != null) {
                sb.append(" at ").append(num(b.probabilityPercent(), 0)).append(" %");
            }
            if (b.volume24hUsd() != null) {
                sb.append(" (24h volume ").append(compact(Math.round(b.volume24hUsd()))).append(" USD)");
            }
        }
        return sb.toString();
    }

    static String depthBlock(List<DepthStat> depth) {
        if (depth.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "STREET DEPTH on top room tickers (verified; analyst views are the street's, attribute):");
        for (DepthStat d : depth) {
            sb.append("\n- ").append(d.ticker()).append(':');
            boolean any = false;
            if (d.targetPrice() != null) {
                sb.append(" consensus target ").append(money(d.targetPrice(), d.targetCurrency()));
                if (d.upsidePercent() != null) sb.append(" (").append(pct(d.upsidePercent())).append(" implied)");
                any = true;
            }
            if (d.buy() != null) {
                sb.append(any ? ";" : "").append(' ').append(d.buy()).append(" buy/")
                        .append(d.hold() == null ? 0 : d.hold()).append(" hold/")
                        .append(d.sell() == null ? 0 : d.sell()).append(" sell");
                any = true;
            }
            if (d.nextEventTitle() != null) {
                sb.append(any ? ";" : "").append(" next event ").append(d.nextEventTitle());
                if (d.nextEventDate() != null) sb.append(" on ").append(d.nextEventDate());
                any = true;
            }
            if (d.shortPercent() != null) {
                sb.append(any ? ";" : "").append(" disclosed shorts ")
                        .append(num(d.shortPercent(), 2)).append(" %");
                if (d.topShortHolder() != null) sb.append(" (top: ").append(d.topShortHolder()).append(')');
                any = true;
            }
            if (d.insiderNote() != null) {
                sb.append(any ? ";" : "").append(" insider: ").append(d.insiderNote());
            }
        }
        return sb.toString();
    }

    static String houseBlock(List<WatchlistStat> watchlist, List<String> deepDives) {
        if (watchlist.isEmpty() && deepDives.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("HOUSE DESK (this terminal's own artifacts):");
        if (!watchlist.isEmpty()) {
            sb.append("\n- watchlist day moves: ");
            boolean first = true;
            for (WatchlistStat w : watchlist) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(w.name());
                if (w.changePercent() != null) sb.append(' ').append(pct(w.changePercent()));
                if (w.tldr() != null && !w.tldr().isBlank()) {
                    String t = w.tldr().strip();
                    sb.append(" (house read: ")
                            .append(t.length() > 120 ? t.substring(0, 117) + "…" : t)
                            .append(')');
                }
            }
        }
        if (!deepDives.isEmpty()) {
            sb.append("\n- deep-dive reports written today: ").append(String.join(", ", deepDives));
        }
        return sb.toString();
    }

    static String newsBlock(List<NewsStat> news) {
        if (news.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("MOST CITED NEWS by the wire (verified titles):");
        for (NewsStat n : news) {
            sb.append("\n- ").append(n.title());
            if (n.source() != null && !n.source().isBlank()) sb.append(" [").append(n.source()).append(']');
            if (n.citations() > 1) sb.append(" (cited ").append(n.citations()).append("×)");
        }
        return sb.toString();
    }

    static String overnightBlock(List<IndexStat> overnight) {
        return tileLine("OVERNIGHT/US-ASIA (verified; US cash still open at report time"
                + " — futures and Asia's close carry the night)", overnight);
    }

    static String outlookBlock(List<OutlookStat> outlook) {
        if (outlook.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("TOMORROW (schedule):");
        for (OutlookStat o : outlook) {
            sb.append("\n- ");
            if ("EARNINGS".equals(o.kind())) {
                sb.append("earnings: ").append(o.title());
                if (o.detail() != null && !o.detail().isBlank()) sb.append(" (").append(o.detail());
                if (o.time() != null) sb.append(", ").append(o.time());
                if (o.detail() != null && !o.detail().isBlank()) sb.append(')');
            } else if ("CORP".equals(o.kind())) {
                sb.append("corporate (Germany): ").append(o.title());
                if (o.detail() != null && !o.detail().isBlank()) {
                    sb.append(" [discussed in the room today: ").append(o.detail()).append(']');
                }
            } else if ("CB".equals(o.kind())) {
                sb.append("rate decision: ").append(o.title()).append(" (High)");
            } else {
                if (o.time() != null) sb.append(o.time()).append(' ');
                if (o.detail() != null && !o.detail().isBlank()) sb.append(o.detail()).append(' ');
                sb.append(o.title());
                if (o.impact() != null && !o.impact().isBlank()) sb.append(" (").append(o.impact()).append(')');
            }
        }
        return sb.toString();
    }

    static String colourBlock(WorldStats w) {
        if (w == null) return "";
        StringBuilder sb = new StringBuilder();
        if (w.pegel() != null && w.pegel().centimeters() != null) {
            sb.append("Rhine at Kaub ").append(num(w.pegel().centimeters(), 0)).append(" cm");
            if ("low".equalsIgnoreCase(w.pegel().state())) {
                sb.append(" (LOW water — freight loads shrink, chemistry/steel pay surcharges)");
            }
            sb.append(". ");
        }
        if (w.usDebtUsd() != null) {
            sb.append("US national debt ").append(compact(Math.round(w.usDebtUsd()))).append(" USD. ");
        }
        if (w.exchangeWeather() != null && w.exchangeWeather().temperatureCelsius() != null) {
            sb.append("Actual weather over the Frankfurt exchange: ")
                    .append(num(w.exchangeWeather().temperatureCelsius(), 1)).append(" °C");
            if (w.exchangeWeather().icon() != null && !w.exchangeWeather().icon().isBlank()) {
                sb.append(" (").append(w.exchangeWeather().icon()).append(')');
            }
            sb.append(". ");
        }
        if (w.moon() != null && w.moon().daysToFull() != null) {
            sb.append("Moon: ").append(w.moon().illuminationPercent()).append(" % lit, ")
                    .append(w.moon().daysToFull() == 0 ? "full moon TONIGHT"
                            : w.moon().daysToFull() + " days to full moon")
                    .append('.');
        }
        String body = sb.toString().strip();
        return body.isEmpty() ? "" : "COLOUR (verified oddities, one margin note each): " + body;
    }

    // --- tiny formatters ----------------------------------------------------

    private static String tileLine(String label, List<IndexStat> tiles) {
        if (tiles.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(label).append(": ");
        boolean first = true;
        for (IndexStat ix : tiles) {
            if (!first) sb.append(" · ");
            first = false;
            sb.append(ix.name());
            if (ix.last() != null) sb.append(' ').append(level(ix));
            if (ix.changePercent() != null) sb.append(" (").append(pct(ix.changePercent())).append(')');
        }
        return sb.toString();
    }

    private static String level(IndexStat ix) {
        String ccy = ix.currency();
        if (ccy == null || "PTS".equals(ccy)) return num(ix.last(), ix.last() >= 1000 ? 0 : 2) + " Pkt";
        if ("FX".equals(ccy)) return num(ix.last(), 4);
        if ("PCT".equals(ccy)) return num(ix.last(), 2) + " %";
        return money(ix.last(), ccy);
    }

    private static String money(double v, String ccy) {
        String n = num(v, v < 1 ? 4 : 2);
        if ("EUR".equals(ccy)) return n + " EUR";
        if ("USD".equals(ccy)) return n + " USD";
        return ccy == null ? n : n + " " + ccy;
    }

    private static String pct(double v) {
        return (v > 0 ? "+" : "") + num(v, 1) + " %";
    }

    private static String num(double v, int decimals) {
        return String.format(Locale.GERMANY, "%,." + decimals + "f", v);
    }

    /** 39.414.179.016.130 → "39,4 Bio", 1.234.567 → "1,2 Mio". */
    static String compact(long v) {
        double abs = Math.abs(v);
        if (abs >= 1e12) return num(v / 1e12, 1) + " Bio";
        if (abs >= 1e9) return num(v / 1e9, 1) + " Mrd";
        if (abs >= 1e6) return num(v / 1e6, 1) + " Mio";
        if (abs >= 1e3) return num(v / 1e3, 0) + " Tsd";
        return String.valueOf(v);
    }

    /** Stable band token → a readable word for the material (the UI localizes properly). */
    private static String bandWord(String band) {
        return band.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static void append(StringBuilder sb, String block) {
        if (block == null || block.isEmpty()) return;
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(block);
    }
}
