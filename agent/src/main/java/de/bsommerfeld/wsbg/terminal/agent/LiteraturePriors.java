package de.bsommerfeld.wsbg.terminal.agent;

import java.util.Map;
import java.util.Optional;

/**
 * The market memory's PRIOR layer: the empirically documented base rates per
 * event class, with source and study period — the default statement while the
 * house's own register hasn't accumulated {@link BaseRates#MIN_N_FOR_MEAN}
 * events of a class yet (two-layer design, recherche doc 2026-07-14). These
 * are attributed third-party findings, never house measurements: the material
 * line carries the citation, and every line names its period, because two of
 * the best-known effects (PEAD, index effect) demonstrably shrank over the
 * last decades — an old prior is a prior, not a current rate.
 *
 * <p>Cross-cutting priors that apply to EVERY class travel separately
 * ({@link #CROSS_CUTTING}): negative news reacts stronger and drifts longer
 * than good news, and nearly every effect is a multiple at small caps of its
 * large-cap size — exactly the room's universe.
 */
final class LiteraturePriors {

    private LiteraturePriors() {
    }

    /** Applies to every class; rides once per base-rate block. */
    static final String CROSS_CUTTING =
            "Cross-cutting (literature): negative events react stronger and drift longer than "
                    + "positive ones; nearly all effects are a multiple at small caps vs large caps.";

    private static final Map<String, String> PRIORS = Map.ofEntries(
            Map.entry("DOWNGRADE",
                    "Analyst downgrade: -4.7 % event reaction (3 days), -9.1 % drift over 6 months "
                            + "(Womack 1996, US, 1989-1991)."),
            Map.entry("UPGRADE",
                    "Analyst upgrade: +3.0 % event reaction, drift only +2.4 % and short-lived - "
                            + "downgrades act stronger and longer (Womack 1996, US, 1989-1991)."),
            Map.entry("GEWINNWARNUNG",
                    "Profit warning: -8 to -13 % on the announcement day, qualitative warnings hit "
                            + "harder than quantified ones, no reversal afterwards "
                            + "(Jackson/Madura 2003, US, 1998-2001)."),
            Map.entry("EARNINGS_BEAT",
                    "Earnings surprise (PEAD): historical top-decile drift ~+2 % over 60 trading "
                            + "days; the effect has shrunk since ~2006 and survives mainly at small "
                            + "caps (Bernard/Thomas 1989; Martineau 2022)."),
            Map.entry("EARNINGS_MISS",
                    "Earnings surprise (PEAD): historical bottom-decile drift ~-2 % over 60 trading "
                            + "days; the effect has shrunk since ~2006 and survives mainly at small "
                            + "caps (Bernard/Thomas 1989; Martineau 2022)."),
            Map.entry("UEBERNAHME_TARGET",
                    "M&A announcement, target: ~+16 % (3-day CAR) "
                            + "(Andrade/Mitchell/Stafford 2001, US, 1973-1998)."),
            Map.entry("UEBERNAHME_VOLLZUG",
                    "M&A, acquirer side: ~0 to -1 % at announcement, stock-financed deals ~-2 to "
                            + "-3 % (Andrade/Mitchell/Stafford 2001, US, 1973-1998)."),
            Map.entry("KAPITALERHOEHUNG",
                    "Seasoned equity offering: ~-3 % at announcement plus long-run underperformance "
                            + "(Asquith/Mullins 1986; Loughran/Ritter)."),
            Map.entry("VERWAESSERUNG",
                    "Unregistered equity sale / PIPE dilution: reaction akin to a seasoned offering, "
                            + "~-3 % at announcement (Asquith/Mullins 1986; Loughran/Ritter)."),
            Map.entry("INDEX_AUFNAHME",
                    "S&P 500 inclusion: +3.4 % in the 1980s, +7.6 % in the 1990s, only +0.8 % since "
                            + "2010 - the effect has practically disappeared "
                            + "(Greenwood/Sammon, NBER w30748)."),
            Map.entry("FDA_APPROVAL",
                    "Positive FDA/clinical event: large caps only +0.3 to +1 % (largely priced in), "
                            + "small-cap biotech single- to double-digit positive "
                            + "(PLOS One event study, US)."),
            Map.entry("FDA_SETBACK",
                    "Negative FDA/clinical event (CRL, phase-III failure): median ~-2 % at large "
                            + "caps, small-cap biotech -20 to -75 % single-day observed "
                            + "(PLOS One event study, US)."),
            Map.entry("RESTATEMENT",
                    "Financial restatement (non-reliance): among the most toxic classes, high "
                            + "single-digit negative reaction on average, worse when fraud-related "
                            + "(Palmrose et al. 2004, US)."),
            Map.entry("INSOLVENZ",
                    "Bankruptcy filing: severe negative reaction; measured means UNDERSTATE the "
                            + "damage because delisted names drop out of price series "
                            + "(survivorship floor, recherche 2026-07-14)."));

    /** The attributed prior line for a class, when the literature carries one. */
    static Optional<String> priorFor(String eventClass) {
        return Optional.ofNullable(PRIORS.get(eventClass));
    }
}
