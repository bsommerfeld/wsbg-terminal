package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders the per-unit compose brief: Yahoo market data + the room's evidence about
 * one {@link SubjectUnit} + its story memory (published headlines + sentiment arc)
 * that survives the evidence prune. Pairs with {@link BriefLabels}. Extracted
 * verbatim from {@link EditorialAgent}.
 */
final class UnitBriefWriter {

    private UnitBriefWriter() {}

    /**
     * News older than this is still shown (a quiet subject's only context may be
     * old news) but tagged {@code [STALE]} so the model never sells it as a fresh
     * catalyst. User-chosen range was 24–48h; 36h is the middle. Tunable.
     */
    static final Duration NEWS_STALE_AFTER = Duration.ofHours(36);

    /** Full prior headlines rendered in the brief; older ones collapse into a digest line. */
    static final int PRIOR_HEADLINES_SHOWN = 3;

    /**
     * Rough char budget for the evidence block (~1.5k tokens). A hot unit can pile
     * up more mentions within the TTL than num_ctx absorbs — Ollama would then
     * truncate the prompt SILENTLY, which reads as the model getting dumb. Oldest
     * mentions are dropped first, with an explicit "omitted" line so the model
     * knows the story is longer than what it sees.
     */
    static final int EVIDENCE_CHAR_BUDGET = 4500;

    /** Builds the per-unit brief: Yahoo data + the room's evidence about this subject + its story memory. Static for testability. */
    static String unitBrief(SubjectUnit unit, boolean newsCoverageEnabled) {
        return unitBrief(unit, newsCoverageEnabled, BriefLabels.EN);
    }

    static String unitBrief(SubjectUnit unit, boolean newsCoverageEnabled, BriefLabels lbl) {
        StringBuilder sb = new StringBuilder();
        sb.append(lbl.subjectHeader(unit.canonicalName(), unit.isInstrument() ? unit.ticker() : null));

        Instant now = Instant.now();
        MarketSnapshot s = unit.snapshot();
        if (s != null && s.hasPrice()) {
            // Multi-source now (L&S / Deutsche Börse / NASDAQ / Yahoo) — name the venue,
            // don't hard-code "Yahoo", so the model never mislabels an EUR L&S price.
            String venue = s.exchangeName() == null || s.exchangeName().isBlank() ? lbl.defaultVenue() : s.exchangeName();
            if ("PTS".equals(s.currency())) {
                // A stock index is quoted in points, not a currency — tell the model
                // so the headline reads „DAX unter 24.000 Punkte", never „… Euro".
                sb.append(lbl.liveDataIndex(venue, s.price()));
            } else {
                sb.append(lbl.liveData(venue, s.price(),
                        s.currency() == null || s.currency().isEmpty() ? "" : " " + s.currency()));
            }
            if (Double.isFinite(s.dayChangePercent())) {
                sb.append(lbl.dayMove(s.dayChangePercent()));
            }
            // Multi-day arc (L&S series=history): 5-day + 1-month move and the gap to
            // the 52-week high — raw numbers, no reading. This is what lets the model
            // tell "ran for days, corrects today" from a plain red day, and gives the
            // RUNNER/BREAKOUT/EXTREME_DIRECTION triggers verified data to stand on.
            Double offHigh = (s.hasPrice() && Double.isFinite(s.fiftyTwoWeekHigh())
                    && s.fiftyTwoWeekHigh() > 0 && s.price() < s.fiftyTwoWeekHigh())
                    ? (s.price() - s.fiftyTwoWeekHigh()) / s.fiftyTwoWeekHigh() * 100.0 : null;
            sb.append(lbl.trend(s.changeOverTradingDays(5), s.changeOverTradingDays(21), offHigh));
            // Off-hours honesty: a quote older than 30 min is a last close, not live.
            long quoteAge = now.getEpochSecond() - s.marketTimeEpochSeconds();
            if (s.marketTimeEpochSeconds() > 0 && quoteAge > 1800) {
                sb.append(lbl.marketClosed());
            }
            // Price anchor: where the subject stood when the room first surfaced
            // it. Survives the evidence prune — the "since first mention" arc is
            // story memory, not a Reddit claim (both prices are Yahoo's own).
            Double anchor = unit.firstPrice();
            if (anchor != null && anchor > 0 && unit.firstPriceAt() != null) {
                double sinceFirst = (s.price() - anchor) / anchor * 100.0;
                sb.append(lbl.sinceFirstMention(age(unit.firstPriceAt(), now), sinceFirst, anchor, s.price()));
            }
            sb.append('\n');
        } else if (!unit.isInstrument()) {
            sb.append(lbl.noTicker());
        }

        // News not yet cited by a prior headline for THIS subject (covered ones are
        // filtered so two headlines never rest on the same item). Each carries a
        // small [N#] ordinal the model echoes back in newsUsed — small integers on a
        // short numbered list, the same proven mechanism as derivedFrom (the long
        // uuids of the old sourceNewsIds field were uncitable for a 4B).
        // Old items are kept (no fresh news is also a situation worth reporting
        // from) but tagged STALE so they're never sold as a fresh catalyst.
        List<RawNewsItem> freshNews = NewsProvenance.briefNews(unit, newsCoverageEnabled);
        List<RawNewsItem> toldNews = new ArrayList<>();
        for (RawNewsItem n : unit.news()) {
            if (!freshNews.contains(n)) toldNews.add(n);
        }
        if (!freshNews.isEmpty() || !toldNews.isEmpty()) {
            sb.append(lbl.newsHeader());
            // Already-woven items stay VISIBLE — a known fact remains the anchor the
            // room's next development hangs on — but compact (title only) and tagged,
            // so it frames the next line without being re-sold as fresh news.
            for (RawNewsItem n : toldNews) {
                sb.append("  - ").append(lbl.newsToldTag()).append(' ').append(n.title()).append('\n');
            }
            int newsOrdinal = 0;
            for (RawNewsItem n : freshNews) {
                sb.append("  - [N").append(++newsOrdinal).append("] ");
                if (n.publishedAt() != null) {
                    sb.append(lbl.ago(age(n.publishedAt(), now))).append(' ');
                    if (Duration.between(n.publishedAt(), now).compareTo(NEWS_STALE_AFTER) > 0) {
                        sb.append("[STALE] ");
                    }
                    sb.append("— ");
                }
                sb.append(n.title());
                if (n.publisher() != null && !n.publisher().isEmpty()) sb.append(" · ").append(n.publisher());
                if (n.summary() != null && !n.summary().isBlank()) {
                    String sum = n.summary().replace('\n', ' ').strip();
                    sb.append("\n      ").append(sum.length() > 200 ? sum.substring(0, 200) + "…" : sum);
                }
                sb.append('\n');
            }
        }

        // Coverage boundary: evidence added on/before the unit's most recent
        // published headline was already in view when that line was written, so it
        // must NOT seed another headline. We OMIT that covered material here — the
        // story-memory headlines below ARE its context — and show only what arrived
        // SINCE the last headline. Time-based (not model-citation-based): a 4B model
        // under-cites sources, but the unit's own evidence + headline timestamps are
        // exact. Mirrors the per-cluster ReportBuilder coverage.
        long lastHeadlineEpoch = 0L;
        for (SubjectUnit.UnitHeadline h : unit.headlines()) {
            if (h.atEpoch() > lastHeadlineEpoch) lastHeadlineEpoch = h.atEpoch();
        }
        List<SubjectUnit.EvidenceRef> visible = new ArrayList<>();
        int coveredOmitted = 0;
        for (SubjectUnit.EvidenceRef e : unit.evidence()) {
            if (lastHeadlineEpoch > 0 && e.addedAtEpoch() <= lastHeadlineEpoch) {
                coveredOmitted++;
                continue; // already reflected in a prior headline → omit
            }
            visible.add(e);
        }

        // Char budget over the VISIBLE (fresh) refs: keep the NEWEST that fit, drop
        // the oldest, and say so — never let Ollama truncate the prompt silently.
        int start = visible.size();
        int budget = EVIDENCE_CHAR_BUDGET;
        while (start > 0 && budget - visible.get(start - 1).snippet().length() - 24 >= 0) {
            start--;
            budget -= visible.get(start).snippet().length() + 24;
        }
        boolean haveHeadlines = lastHeadlineEpoch > 0;
        sb.append(lbl.evidenceHeader(haveHeadlines));
        if (coveredOmitted > 0) {
            sb.append(lbl.coveredOmitted(coveredOmitted));
        }
        if (start > 0) {
            sb.append(lbl.budgetOmitted(start));
        }
        List<SubjectUnit.EvidenceRef> context = new ArrayList<>();
        for (SubjectUnit.EvidenceRef e : visible.subList(start, visible.size())) {
            if ("reddit-context".equals(e.source())) {
                context.add(e); // a reply chain this subject was named in — rendered below
                continue;
            }
            String loc = "vision".equals(e.source()) ? lbl.visionLoc()
                    : (e.commentId() == null ? e.threadId() : e.commentId());
            sb.append("  - [").append(loc).append(", ")
                    .append(lbl.ago(age(Instant.ofEpochSecond(e.addedAtEpoch()), now))).append("] ")
                    .append(e.snippet()).append('\n');
        }
        if (!context.isEmpty()) {
            sb.append(lbl.conversationContext());
            for (SubjectUnit.EvidenceRef e : context) {
                sb.append("    ↳ ").append(e.snippet()).append('\n');
            }
        }

        appendStoryMemory(sb, unit.headlines(), now, lbl);
        return sb.toString();
    }

    /**
     * The unit's story memory: the last {@link #PRIOR_HEADLINES_SHOWN} headlines in
     * full (with age + sentiment), older ones as a count digest, plus the sentiment
     * arc across the whole history. This block is what survives the evidence prune —
     * without it, a unit older than the TTL looked brand-new and the "no prior
     * headlines → always write" rule re-published the old story verbatim.
     */
    private static void appendStoryMemory(StringBuilder sb, List<SubjectUnit.UnitHeadline> prior,
            Instant now, BriefLabels lbl) {
        if (prior.isEmpty()) return;
        sb.append(lbl.storyMemoryHeader());
        int shownFrom = Math.max(0, prior.size() - PRIOR_HEADLINES_SHOWN);
        if (shownFrom > 0) {
            SubjectUnit.UnitHeadline first = prior.get(0);
            sb.append(lbl.earlierHeadlines(shownFrom, age(Instant.ofEpochSecond(first.atEpoch()), now)));
        }
        // Numbered so the compose reply can CITE the prior lines this one builds on
        // ("derivedFrom": [2]) — provenance chaining: the cited lines' news sources
        // are inherited onto the new line. Ordinals are 1-based within the SHOWN
        // window and re-derived identically in inheritedRefs().
        int ordinal = 1;
        for (SubjectUnit.UnitHeadline h : prior.subList(shownFrom, prior.size())) {
            sb.append("  - #").append(ordinal++)
                    .append(" [").append(lbl.ago(age(Instant.ofEpochSecond(h.atEpoch()), now)));
            if (h.sentiment() != null && !h.sentiment().isBlank()) sb.append(", ").append(h.sentiment());
            sb.append("] ").append(h.text()).append('\n');
        }
        String arc = sentimentArc(prior);
        if (!arc.isEmpty()) sb.append(lbl.sentimentArcPrefix()).append(arc).append('\n');
    }

    /**
     * The unit's sentiment trajectory ("BULLISH → MIXED → BEARISH") across its
     * published headlines, consecutive duplicates collapsed. Empty when fewer than
     * two distinct steps exist — a one-word arc carries no information the
     * headline list doesn't. Package-private for testing.
     */
    static String sentimentArc(List<SubjectUnit.UnitHeadline> prior) {
        List<String> steps = new ArrayList<>();
        for (SubjectUnit.UnitHeadline h : prior) {
            String sent = h.sentiment() == null ? "" : h.sentiment().trim().toUpperCase(Locale.ROOT);
            if (sent.isEmpty()) continue;
            if (steps.isEmpty() || !steps.get(steps.size() - 1).equals(sent)) steps.add(sent);
        }
        return steps.size() < 2 ? "" : String.join(" → ", steps);
    }

    /** Compact relative age: "5m", "3h", "2d". Clamps negative (clock skew) to "0m". */
    static String age(Instant then, Instant now) {
        long mins = Math.max(0, Duration.between(then, now).toMinutes());
        return mins < 60 ? mins + "m" : mins < 1440 ? (mins / 60) + "h" : (mins / 1440) + "d";
    }
}
