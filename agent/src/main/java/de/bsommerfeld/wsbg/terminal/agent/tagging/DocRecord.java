package de.bsommerfeld.wsbg.terminal.agent.tagging;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tagger's per-article working copy: the analyzed token sequences (title
 * apart from teaser — the strongest salience feature is WHERE a name first
 * appears), the normalized raw text for the compound test, and the hard keys
 * the article itself declares.
 *
 * <p>Hard keys come from FORMAT, never from a word list: a validated ISIN in
 * the text, a {@code $CASHTAG}, an exchange-paren tag {@code (NASDAQ: NVDA)},
 * the source's own {@code relatedTickers} and {@code isin} fields.
 */
final class DocRecord {

    /** ISIN shape in running text; every match is check-digit validated. */
    private static final Pattern ISIN_IN_TEXT =
            Pattern.compile("(?<![A-Z0-9])([A-Z]{2}[A-Z0-9]{9}[0-9])(?![A-Z0-9])");

    /** {@code $NVDA}, {@code $sofi} — the cashtag convention. */
    private static final Pattern CASHTAG =
            Pattern.compile("\\$([A-Za-z]{1,6})(?![A-Za-z0-9])");

    /** {@code (NASDAQ: NVDA)} / {@code (ETR:SAP)} — venue-paren tagging, any venue. */
    private static final Pattern EXCHANGE_PAREN =
            Pattern.compile("\\(\\s*([A-Z]{2,12})\\s*:\\s*([A-Z0-9.\\-]{1,10})\\s*\\)");

    final String id;
    final List<String> titleTokens;
    final List<String> textTokens;
    /** Normalized raw text (case/diacritics folded, hyphens kept) — the compound view. */
    final String rawNorm;
    /** The same normalisation with CASE preserved — the case-statistics view. */
    final String caseNorm;
    /** {@code isin:XX…} / {@code sym:XYZ} keys the article itself declares. */
    final Set<String> hardKeys;
    final Instant publishedAt;

    DocRecord(Article a) {
        this.id = a.identity();
        String title = a.title() == null ? "" : a.title();
        String summary = a.summary() == null ? "" : a.summary();
        this.titleTokens = List.copyOf(TagText.tokens(title));
        List<String> text = new ArrayList<>(titleTokens);
        text.addAll(TagText.tokens(summary));
        this.textTokens = List.copyOf(text);
        this.caseNorm = TagText.normalizeKeepCase(title + " " + summary);
        this.rawNorm = caseNorm.toLowerCase(java.util.Locale.ROOT);
        this.hardKeys = Set.copyOf(extractHardKeys(a, title + " " + summary));
        this.publishedAt = a.publishedAt();
    }

    /** The indexed text — what {@link ArticleIndex} analyzes. */
    static String indexText(Article a) {
        String title = a.title() == null ? "" : a.title();
        String summary = a.summary() == null ? "" : a.summary();
        return summary.isEmpty() ? title : title + " \n " + summary;
    }

    private static Set<String> extractHardKeys(Article a, String rawText) {
        Set<String> keys = new LinkedHashSet<>();
        if (a.isin() != null && !a.isin().isBlank()) {
            Isin.parse(a.isin()).ifPresent(i -> keys.add("isin:" + i.value()));
        }
        if (a.relatedTickers() != null) {
            for (String t : a.relatedTickers()) {
                if (t == null || t.isBlank()) continue;
                keys.add("sym:" + UniverseStats.baseOf(t));
            }
        }
        Matcher isin = ISIN_IN_TEXT.matcher(rawText);
        while (isin.find()) {
            Isin.parse(isin.group(1)).ifPresent(i -> keys.add("isin:" + i.value()));
        }
        Matcher cash = CASHTAG.matcher(rawText);
        while (cash.find()) {
            keys.add("sym:" + cash.group(1).toUpperCase(Locale.ROOT));
        }
        Matcher paren = EXCHANGE_PAREN.matcher(rawText);
        while (paren.find()) {
            keys.add("sym:" + UniverseStats.baseOf(paren.group(2)));
        }
        return keys;
    }

    /** Whether the token (plural-tolerant) occurs in the sequence. */
    static boolean containsToken(List<String> tokens, String t) {
        return firstIndex(tokens, t) >= 0;
    }

    /** First index of the token (plural-tolerant), or -1. */
    static int firstIndex(List<String> tokens, String t) {
        String plural = t + "s";
        for (int i = 0; i < tokens.size(); i++) {
            String tok = tokens.get(i);
            if (tok.equals(t) || tok.equals(plural)) return i;
        }
        return -1;
    }

    /**
     * Whether the token sequence occurs in order within {@code slop} skipped
     * positions — the in-document confirmation of a phrase candidate.
     * Returns the start index or -1.
     */
    static int phraseIndex(List<String> tokens, List<String> phrase, int slop) {
        if (phrase.isEmpty() || tokens.isEmpty()) return -1;
        outer:
        for (int start = 0; start < tokens.size(); start++) {
            if (!matchesAt(tokens, start, phrase.get(0))) continue;
            int pos = start;
            for (int p = 1; p < phrase.size(); p++) {
                int limit = Math.min(tokens.size(), pos + 2 + slop);
                int found = -1;
                for (int i = pos + 1; i < limit; i++) {
                    if (matchesAt(tokens, i, phrase.get(p))) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) continue outer;
                pos = found;
            }
            return start;
        }
        return -1;
    }

    private static boolean matchesAt(List<String> tokens, int i, String t) {
        String tok = tokens.get(i);
        return tok.equals(t) || tok.equals(t + "s");
    }

    /**
     * The compound test (research §3.4): does the token appear in this document
     * ONLY as part of a hyphen compound whose partner is foreign to the
     * instrument ("Sofi-Brille")? A match on a compound part is no mention.
     */
    boolean onlyCompoundBound(String token, Set<String> ownTokens) {
        Pattern occurrence = Pattern.compile(
                "(?<![\\p{L}\\p{N}-])" + Pattern.quote(token) + "s?(?![\\p{L}\\p{N}-])");
        if (occurrence.matcher(rawNorm).find()) return false; // stands alone at least once
        Pattern bound = Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(token) + "s?-([\\p{L}\\p{N}]+)"
                        + "|([\\p{L}\\p{N}]+)-" + Pattern.quote(token) + "s?(?![\\p{L}\\p{N}])");
        Matcher m = bound.matcher(rawNorm);
        boolean anyBound = false;
        while (m.find()) {
            String partner = m.group(1) != null ? m.group(1) : m.group(2);
            if (partner != null && ownTokens.contains(partner)) return false;
            anyBound = true;
        }
        return anyBound;
    }

    /**
     * Whether the token occurs at least once as a PROPER-NAME shape
     * (capital-initial) in this document. Only consulted when the basin-wide
     * case statistic has too few observations to speak — a document that
     * writes "off the side of her desk" does not mention The Trade Desk.
     * Documents without any letter case (CJK, all-lower styles) pass.
     */
    boolean capitalizedSomewhere(String token) {
        boolean docHasUpper = false;
        for (int i = 0; i < caseNorm.length(); i++) {
            if (Character.isUpperCase(caseNorm.charAt(i))) {
                docHasUpper = true;
                break;
            }
        }
        if (!docHasUpper) return true;
        Matcher m = Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(token) + "s?(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE).matcher(caseNorm);
        while (m.find()) {
            char first = caseNorm.charAt(m.start());
            if (Character.isUpperCase(first)) return true;
        }
        return false;
    }

    /** Set view of the text tokens — for profile/collocation arithmetic. */
    Set<String> tokenSet() {
        return new HashSet<>(textTokens);
    }
}
