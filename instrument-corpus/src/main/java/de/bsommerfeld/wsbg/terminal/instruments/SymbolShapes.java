package de.bsommerfeld.wsbg.terminal.instruments;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Mechanical, list-free-where-possible FORM rules over instrument symbols — the
 * shared vocabulary of the identity hardening (2026-08-13 live-run failure
 * classes): junk crypto tokens with minted number suffixes, numeric-prefixed
 * foreign secondary listings, Morningstar fund pseudo-symbols, bare numeric
 * WKN ids. Lives in {@code instrument-corpus} so both the resolver stages
 * ({@code agent}) and the learned-alias fold can apply the SAME judgment.
 *
 * <p><b>One curated list lives here, openly declared:</b> {@link #MAJOR_CRYPTO}.
 * The measured live ledger showed ~57% wrong referents among crypto verdicts
 * ("gemini" → GEMINI34655-USD, "gta" → GTA6-USD, "claude" → MONET-USD …), while
 * the seven-plus real large coins the room genuinely writes about carried 47%
 * of all crypto occurrences. A coin outside this list is mechanically no
 * subject (news-only) — the doctrine "lieber gar kein Ticker als ein falscher".
 * The set is deliberately tiny and stable; nothing else in this class is a
 * curated noise list (the suffix sets below are venue taxonomy, the same kind
 * {@code VenuePreference} already carries).
 */
public final class SymbolShapes {

    private SymbolShapes() {}

    /**
     * The crypto positive list: the only coins admissible as a SUBJECT. Everything
     * else with a crypto shape is a naming parasite until proven otherwise — and
     * nothing else in the app consumes crypto resolution (Fear&amp;Greed is
     * decoupled, CoinGecko/CryptoDerivs have no callers, assetClass is never set).
     */
    public static final Set<String> MAJOR_CRYPTO =
            Set.of("BTC", "ETH", "XRP", "SOL", "DOGE", "ADA", "DOT", "LTC");

    /** A Yahoo crypto pair symbol: {@code BASE-USD} / {@code BASE-EUR}. */
    private static final Pattern CRYPTO_PAIR = Pattern.compile("^([A-Z0-9]+)-(USD|EUR)$");

    /**
     * An embedded multi-digit run in a coin's base symbol ({@code GEMINI34655},
     * {@code TRUMP31792}, {@code UNI7083}): Yahoo disambiguates minted namesake
     * tokens by appending their internal id — no real large coin carries one.
     * Rule-based, no list.
     */
    private static final Pattern JUNK_NUMBER_RUN = Pattern.compile(".*\\d{4,}.*");

    /**
     * Western venues where a numeric PREFIX marks a foreign secondary line
     * ({@code 1MUV2.MI}, {@code 9YM.F}, {@code 4HEI.TI}, {@code 0DHC.IL},
     * {@code 1ELF.MI}): Borsa Italiana &amp; the German regionals prefix their
     * cross-listings with a digit. Deliberately WITHOUT {@code .DE} — Xetra
     * primaries legitimately start with digits ({@code 1COV.DE} Covestro,
     * {@code 8TRA.DE} Traton).
     */
    private static final Set<String> NUMERIC_SECONDARY_SUFFIXES =
            Set.of("MI", "F", "MU", "DU", "SG", "BE", "HM", "HA", "IL", "TI");

    /** True for a {@code …-USD}/{@code …-EUR} crypto pair symbol. */
    public static boolean isCryptoPair(String symbol) {
        return symbol != null && CRYPTO_PAIR.matcher(norm(symbol)).matches();
    }

    /**
     * The pair's base coin symbol ({@code BTC-USD} → {@code BTC}), or {@code ""}
     * when the symbol is no crypto pair.
     */
    public static String cryptoBase(String symbol) {
        if (symbol == null) return "";
        var m = CRYPTO_PAIR.matcher(norm(symbol));
        return m.matches() ? m.group(1) : "";
    }

    /** True when the crypto pair's base coin is on the {@link #MAJOR_CRYPTO} list. */
    public static boolean isMajorCryptoPair(String symbol) {
        return MAJOR_CRYPTO.contains(cryptoBase(symbol));
    }

    /**
     * The random-number veto: a crypto pair whose base carries an embedded
     * multi-digit run ({@code GEMINI34655-USD}) is a minted namesake token,
     * never a subject. List-free; fires before (and independent of) the
     * positive list.
     */
    public static boolean isJunkNumberedCrypto(String symbol) {
        String base = cryptoBase(symbol);
        return !base.isEmpty() && JUNK_NUMBER_RUN.matcher(base).matches();
    }

    /**
     * The numeric-prefix veto: a leading digit before a WESTERN exchange suffix
     * ({@code 1MUV2.MI}) marks a thin foreign secondary listing — never the
     * stamp while any other candidate exists. Venues with genuine numeric home
     * listings ({@code .HK}/{@code .T}/{@code .KS}/{@code .TW}/{@code .SZ}/
     * {@code .SS}/{@code .SR}) are exempt, as is Xetra (see
     * {@link #NUMERIC_SECONDARY_SUFFIXES}).
     */
    public static boolean isNumericPrefixedWesternSecondary(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        String s = norm(symbol);
        if (s.isEmpty() || !Character.isDigit(s.charAt(0))) return false;
        int dot = s.lastIndexOf('.');
        if (dot <= 0 || dot == s.length() - 1) return false;
        return NUMERIC_SECONDARY_SUFFIXES.contains(s.substring(dot + 1));
    }

    /**
     * The {@code 0P…} veto: Yahoo carries Morningstar fund share classes under
     * {@code 0P}-prefixed pseudo-symbols ({@code 0P0001L7U0.SA} — the Brazilian
     * fund a "Rockstar" once resolved to). Such an id is never the subject of
     * an equity room.
     */
    public static boolean isMorningstarFundId(String symbol) {
        if (symbol == null) return false;
        String s = norm(symbol);
        return s.length() >= 8 && s.startsWith("0P");
    }

    /** A bare numeric identifier (a WKN standing where a symbol should be). */
    public static boolean isBareNumericId(String symbol) {
        if (symbol == null) return false;
        String s = norm(symbol);
        return s.length() >= 4 && s.chars().allMatch(Character::isDigit);
    }

    /**
     * The learned-memory hygiene rule: a remembered reading whose symbol carries
     * one of the FORMALLY wrong shapes (junk-numbered or non-major crypto pair,
     * numeric-prefixed western secondary, Morningstar fund id, bare WKN) is
     * never answered from memory — the one-time ledger muck-out, applied on
     * every fold instead of by rewriting the file (the postings stay in the
     * history, they just stop speaking).
     */
    public static boolean isSuspectAliasSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        String s = norm(symbol);
        if (isCryptoPair(s)) return !isMajorCryptoPair(s);
        return isNumericPrefixedWesternSecondary(s)
                || isMorningstarFundId(s)
                || isBareNumericId(s);
    }

    private static String norm(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
