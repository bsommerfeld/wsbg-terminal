package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.List;

/**
 * The deep company record behind one stock — everything a compact equity
 * research note wants beyond the live quote: profile (incl. the OFFICIAL
 * company website), multi-year key figures with estimates, balance-sheet
 * history, the boards, a chart-technical read, peers, performance/volatility
 * context and the reference index. Currently fed by Consorsbank's
 * financial-info API (keyless, by ISIN, ONE call); venue-neutral seam.
 *
 * <p>Missing scalars are {@link Double#NaN}; missing strings {@code null};
 * missing lists EMPTY (never null). Year rows carry their label verbatim
 * (e.g. {@code "2026e"} — trailing {@code e} marks an estimate).
 */
public record CompanyDeepDive(
        String isin,
        Profile profile,
        List<KeyFigureYear> keyFigures,
        List<BalanceSheetYear> balanceSheet,
        List<BoardMember> board,
        TechnicalView technicalView,
        List<Peer> peers,
        PerformanceStats performance,
        String indexName,
        long fetchedAtEpochSeconds) {

    /**
     * Company identity + the official website.
     *
     * @param website          the company's OFFICIAL website URL, null unknown
     * @param portrait         a prose company portrait (German), null unknown
     * @param hqCity           headquarters city, null unknown
     * @param hqCountry        headquarters country, null unknown
     * @param marketCapEur     market capitalization, NaN unknown
     * @param sharesOutstanding number of shares, -1 unknown
     */
    public record Profile(String website, String portrait, String hqCity,
            String hqCountry, double marketCapEur, long sharesOutstanding) {
    }

    /**
     * One fiscal year of key figures (actual or estimate — see {@code label}).
     * All monetary figures in EUR unless the source says otherwise.
     */
    public record KeyFigureYear(String label, boolean estimate,
            double eps, double dividendPerShare, double dividendYieldPercent,
            double peRatio, double pegRatio, double bookValuePerShare,
            double ebitMarginPercent, double ebitdaMarginPercent,
            double equityRatioPercent, long employees) {
    }

    /** One fiscal year of balance-sheet aggregates (thousands EUR as reported). */
    public record BalanceSheetYear(String label,
            double turnover, double netIncome, double equityCapital,
            double liabilities, double totalAssets, double cashflowNet,
            double researchExpenses) {
    }

    /** One executive/supervisory board member. */
    public record BoardMember(String name, String role, String board) {
    }

    /**
     * The chart-technical read (TradingCentral): pivot, three supports, three
     * resistances, discrete short/medium-term opinions (-1/0/+1 as delivered)
     * and the analyst's German comment prose. All figures verbatim from the
     * source — the consumer must attribute them, never present them as own work.
     */
    public record TechnicalView(double pivot,
            double support1, double support2, double support3,
            double resistance1, double resistance2, double resistance3,
            Integer shortTermOpinion, Integer mediumTermOpinion,
            String commentText, String asOfIso) {
    }

    /** One suggested peer company. */
    public record Peer(String isin, String name, double marketCapEur,
            double peRatio, double dividendYieldPercent) {
    }

    /**
     * Performance/volatility context. Percent figures; NaN unknown.
     *
     * @param perf1w/perf4w/perf3m/perf6m/perf52w price performance over the window
     * @param vola30d/vola250d annualized volatility over ~30/～250 trading days
     * @param high52w/low52w   52-week extremes with their ISO dates
     */
    public record PerformanceStats(
            double perf1w, double perf4w, double perf3m, double perf6m, double perf52w,
            double vola30d, double vola250d,
            double high52w, String high52wDateIso,
            double low52w, String low52wDateIso) {
    }
}
