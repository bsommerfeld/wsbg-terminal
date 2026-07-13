package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.briefing.FnRssClient;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.AdhocStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.AnalystActionStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.BetStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CryptoStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DepthStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.IndexStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MacroStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MoverStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.NewsStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OutlookStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RateStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RoomPulse;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ShortVolStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SocialStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TrendingCoin;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WatchlistStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldStats;

import java.util.List;
import java.util.Locale;

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

    private WeatherMaterial() {
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
        append(sb, adhocBlock(w == null ? List.of() : w.adhocs()));
        append(sb, analystBlock(w == null ? List.of() : w.analystActions()));
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
