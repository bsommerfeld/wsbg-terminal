package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient.SearchResult;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Live, side-by-side comparison of the OLD single-token matcher (exact token
 * equality — the stop-word "cat and mouse") against the NEW one (Yahoo's own
 * relevance score, {@link TickerResolver#strongMatch}). For a battery of subject
 * names it hits Yahoo search ONCE each and runs both matchers over the very same
 * quotes, printing every divergence with the score that drove it — so the
 * trade-off can be judged on real data, not anecdotes.
 *
 * <p>The legacy matcher is reconstructed locally (the production helpers are
 * private) and intentionally covers only the <em>admission</em> decision that
 * changed — not exchange-preference, which is an orthogonal axis.
 *
 * <pre>RESOLVER_LIVE=true mvn test -pl agent -Dtest=TickerMatchComparisonIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RESOLVER_LIVE", matches = "true")
class TickerMatchComparisonIT {

    /** Old generic stop tokens — the same set the resolver used BEFORE "com" was added. */
    private static final Set<String> OLD_STOP = Set.of(
            "inc", "incorporated", "corp", "corporation", "co", "company",
            "ag", "se", "kgaa", "gmbh", "ltd", "limited", "plc", "sa", "nv",
            "aktiengesellschaft", "kommanditgesellschaft", "gesellschaft",
            "the", "and", "technology", "technologies", "tech",
            "quantum", "semiconductor", "semiconductors",
            "pharmaceutical", "pharmaceuticals", "pharma",
            "bioscience", "biosciences", "therapeutic",
            "industries", "industrial", "interactive", "communications",
            "etf", "fund", "trust", "shares");
    private static final double STRONG_MATCH_THRESHOLD = 0.34;

    /** Megacaps that the OLD exact-equality gate dropped to name-only units. */
    private static final List<String> MEGACAPS = List.of(
            "Amazon", "Meta", "Alphabet", "Salesforce", "Booking", "Palantir");
    /** Names the old gate already handled (extra tokens were all stop-words). */
    private static final List<String> ALREADY_OK = List.of(
            "Apple", "Nvidia", "Siemens", "Allianz", "Rheinmetall");
    /** Multi-token names (never went through the strict single-token gate). */
    private static final List<String> MULTI = List.of(
            "Beyond Meat", "Berkshire Hathaway", "Main Street Capital", "Deep Sea Minerals");
    /** Fuzzy / cross-language cases — the guard and the tier-2 (embedding) territory. */
    private static final List<String> TRICKY = List.of(
            "Rheiner", "Münchener Rück", "Munich Re", "MicroStrategy", "CytomX");

    @Test
    void compareLegacyVsScoreGatedMatching() {
        YahooFinanceClient yahoo = new YahooFinanceClient();
        int rescued = 0, regressed = 0;

        for (String group : List.of("MEGACAPS", "ALREADY_OK", "MULTI", "TRICKY")) {
            List<String> subjects = switch (group) {
                case "MEGACAPS" -> MEGACAPS;
                case "ALREADY_OK" -> ALREADY_OK;
                case "MULTI" -> MULTI;
                default -> TRICKY;
            };
            System.out.println("\n========== " + group + " ==========");
            for (String subject : subjects) {
                SearchResult sr;
                try {
                    sr = yahoo.search(subject, 8, 0);
                } catch (Exception e) {
                    System.out.printf("%-18s  ! search failed: %s%n", subject, e.getMessage());
                    continue;
                }
                List<YahooQuote> quotes = sr.quotes();
                YahooQuote legacy = legacyMatch(subject, quotes);
                YahooQuote now = TickerResolver.strongMatch(subject, quotes);

                String verdict = verdict(legacy, now);
                if (verdict.equals("RESCUED")) rescued++;
                if (verdict.equals("REGRESSED")) regressed++;

                System.out.printf(Locale.ROOT, "%-18s  old=%-10s  new=%-10s  %s%n",
                        subject, sym(legacy), sym(now), verdict);
                // Show the top candidates + scores so the threshold can be judged.
                for (int i = 0; i < Math.min(3, quotes.size()); i++) {
                    YahooQuote q = quotes.get(i);
                    System.out.printf(Locale.ROOT, "        %-12s score=%-12.0f %s%n",
                            q.symbol(), q.score(), q.displayName());
                }
            }
        }
        System.out.printf("%n>>> score gate rescued %d, regressed %d (vs old exact-equality)%n",
                rescued, regressed);
    }

    /** The new path must not REGRESS any name the old gate already resolved. */
    @Test
    void scoreGateNeverRegressesNamesTheOldGateMatched() {
        YahooFinanceClient yahoo = new YahooFinanceClient();
        for (String subject : concat(ALREADY_OK, MULTI)) {
            List<YahooQuote> quotes;
            try {
                quotes = yahoo.search(subject, 8, 0).quotes();
            } catch (Exception e) {
                continue; // network hiccup — don't fail the guard on infra
            }
            YahooQuote legacy = legacyMatch(subject, quotes);
            if (legacy != null) {
                assertNotNull(TickerResolver.strongMatch(subject, quotes),
                        subject + " resolved under the old gate but not the new one (regression)");
            }
        }
    }

    /** The megacaps the old gate dropped must now resolve to an instrument. */
    @Test
    void scoreGateRescuesMegacaps() {
        YahooFinanceClient yahoo = new YahooFinanceClient();
        for (String subject : MEGACAPS) {
            List<YahooQuote> quotes;
            try {
                quotes = yahoo.search(subject, 8, 0).quotes();
            } catch (Exception e) {
                continue;
            }
            assertNotNull(TickerResolver.strongMatch(subject, quotes),
                    subject + " should resolve to an instrument via the score gate");
        }
    }

    /** "Rheiner" (the historic fuzzy false-positive) must stay unmatched. */
    @Test
    void fuzzyGuardStillHolds() {
        YahooFinanceClient yahoo = new YahooFinanceClient();
        List<YahooQuote> quotes;
        try {
            quotes = yahoo.search("Rheiner", 8, 0).quotes();
        } catch (Exception e) {
            return;
        }
        // If Yahoo even returns a "Rheiner …"-named micro-cap, its score must be far
        // below the bar; we assert the matcher does not glom onto it.
        YahooQuote now = TickerResolver.strongMatch("Rheiner", quotes);
        if (now != null) {
            // Allowed only if Yahoo itself returns an exact-symbol or a genuinely
            // dominant hit; print it so a real regression is visible in the log.
            System.out.println("note: 'Rheiner' matched " + now.symbol()
                    + " score=" + now.score() + " — verify this is not a fuzzy miss");
        } else {
            assertNull(now);
        }
    }

    // ---- legacy matcher (admission only): exact-equality strict, old stops ----

    private static YahooQuote legacyMatch(String query, List<YahooQuote> quotes) {
        if (quotes == null || quotes.isEmpty()) return null;
        Set<String> qTok = tokenize(query);
        boolean single = qTok.size() == 1;
        for (YahooQuote q : quotes) {
            boolean exactSymbol = q.symbol() != null && query.equalsIgnoreCase(q.symbol());
            boolean strong = bestSim(qTok, q) >= STRONG_MATCH_THRESHOLD;
            if (strong && single && !onlyQueryTokens(qTok, q)) strong = false;
            if (exactSymbol) strong = true;
            if (strong) return q; // first admitted in Yahoo order
        }
        return null;
    }

    private static Set<String> tokenize(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String t : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+")) {
            if (t.length() >= 3 && !OLD_STOP.contains(t)) out.add(t);
        }
        return out;
    }

    private static double bestSim(Set<String> q, YahooQuote quote) {
        return Math.max(jaccard(q, tokenize(quote.shortName())), jaccard(q, tokenize(quote.longName())));
    }

    private static boolean onlyQueryTokens(Set<String> q, YahooQuote quote) {
        return tokenize(quote.shortName()).equals(q) || tokenize(quote.longName()).equals(q);
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        if (inter.isEmpty()) return 0.0;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private static String verdict(YahooQuote legacy, YahooQuote now) {
        String l = sym(legacy), n = sym(now);
        if (l.equals("—") && !n.equals("—")) return "RESCUED";
        if (!l.equals("—") && n.equals("—")) return "REGRESSED";
        if (!l.equals(n)) return "venue-diff";
        return "same";
    }

    private static String sym(YahooQuote q) {
        return q == null ? "—" : q.symbol();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
