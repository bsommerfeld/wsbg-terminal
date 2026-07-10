package de.bsommerfeld.wsbg.terminal.db;

import java.util.List;

/**
 * One archived daily Wetterbericht: the AI-written day report plus the market
 * stats frozen at generation time. The stats are captured WITH the report on
 * purpose — a report re-opened days later must show the day it describes, not
 * whatever the market does at viewing time.
 *
 * <p>Plain record, no Jackson annotations — round-trips through the vanilla
 * {@code ObjectMapper} by component name, exactly like {@link HeadlineRecord}.
 */
public record WeatherReportRecord(
        String date,
        long generatedAt,
        String text,
        String language,
        int headlineCount,
        int importantCount,
        List<IndexStat> indices,
        List<TickerStat> tickers,
        List<NewsStat> news) {

    public WeatherReportRecord {
        indices = indices == null ? List.of() : List.copyOf(indices);
        tickers = tickers == null ? List.of() : List.copyOf(tickers);
        news = news == null ? List.of() : List.copyOf(news);
    }

    /** A broad-market index at day's end: last level (points), day move, day volume, intraday spark. */
    public record IndexStat(
            String name,
            String symbol,
            Double last,
            Double changePercent,
            Long volume,
            List<Double> spark) {

        public IndexStat {
            spark = spark == null ? List.of() : List.copyOf(spark);
        }
    }

    /** One of the day's most-discussed instruments on the wire, with its frozen quote. */
    public record TickerStat(
            String ticker,
            String name,
            int headlineCount,
            int importantCount,
            Double price,
            String currency,
            Double changePercent,
            Long volume) {
    }

    /** A news item the day's headlines actually leaned on, ranked by citations. */
    public record NewsStat(
            String title,
            String source,
            int citations) {
    }
}
