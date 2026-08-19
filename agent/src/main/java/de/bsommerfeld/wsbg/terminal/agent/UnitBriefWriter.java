package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.web.article.Article;

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

    /** Cap for the per-item article digest rendered under a news title (a runaway model reply must not eat the brief). */
    static final int DIGEST_CHAR_CAP = 500;

    /**
     * Rough char budget for the fresh-news block (~950 tokens). The item count
     * is already capped, but twelve items with a full digest each are ~7.5k
     * chars — more than the evidence block is allowed, for material that is
     * context rather than the subject itself. Newest are kept, the rest counted
     * off explicitly, exactly as the evidence budget does.
     */
    static final int NEWS_CHAR_BUDGET = 3000;

    /**
     * Secondary budget for the COMPACT tail: once the full-render budget is spent,
     * further fresh items still appear as title-only lines (with their [N#]
     * ordinal, so they stay citable) instead of vanishing into the omitted count.
     * With the fetch cap raised from 6 to {@link NewsBox#MAX_NEWS} items this is
     * what keeps breadth affordable: ~200 tokens buys up to ~6 more headlines
     * where a full render would have cost ~1k.
     */
    static final int NEWS_COMPACT_CHAR_BUDGET = 700;

    /** Already-told titles rendered before the block collapses to the newest few. */
    static final int TOLD_NEWS_SHOWN = 3;

    /**
     * Rough char budget for the room-sheet block (~450 tokens). The sheet is
     * already line-capped ({@link SubjectUnit#MAX_FACT_LINES}), but 14 full lines
     * are ~2.5k chars — orientation must not outweigh the fresh material it
     * frames. Newest lines are kept, the rest counted off explicitly.
     */
    static final int FACT_SHEET_CHAR_BUDGET = 1800;

    /**
     * Rough char budget for the permanent dossier block (~400 tokens). The
     * dossier is consolidation-bounded, not render-bounded — this budget only
     * decides how much of it one brief affords. Newest facts kept, the rest
     * counted off explicitly.
     */
    static final int DOSSIER_CHAR_BUDGET = 1500;

    /** Builds the per-unit brief: Yahoo data + the room's evidence about this subject + its story memory. Static for testability. */
    static String unitBrief(SubjectUnit unit, boolean newsCoverageEnabled) {
        return unitBrief(unit, newsCoverageEnabled, BriefLabels.EN);
    }

    static String unitBrief(SubjectUnit unit, boolean newsCoverageEnabled, BriefLabels lbl) {
        return unitBrief(unit, newsCoverageEnabled, lbl, null);
    }

    static String unitBrief(SubjectUnit unit, boolean newsCoverageEnabled, BriefLabels lbl,
            java.util.function.Function<String, String> digestLookup) {
        return unitBrief(unit, newsCoverageEnabled, lbl, digestLookup, List.of(), List.of());
    }

    static String unitBrief(SubjectUnit unit, boolean newsCoverageEnabled, BriefLabels lbl,
            java.util.function.Function<String, String> digestLookup,
            List<de.bsommerfeld.wsbg.terminal.db.DossierFact> dossier) {
        return unitBrief(unit, newsCoverageEnabled, lbl, digestLookup, dossier, List.of());
    }

    /**
     * Full form: {@code digestLookup} resolves an article link to its cached
     * key-fact digest ({@link NewsDigester#ifCached}) — rendered under the news
     * title in place of the source teaser, so the compose model sees the article's
     * substance. Null (tests, digester off) falls back to the teaser-only brief.
     * {@code dossier} is the subject's permanent news dossier
     * ({@link de.bsommerfeld.wsbg.terminal.db.SubjectDossierArchive#bySubject}),
     * rendered as established knowledge; {@code sentimentDays} the newest
     * archived day-sheets (oldest-first). Empty lists (tests, name units, no
     * archive) skip their blocks.
     */
    static String unitBrief(SubjectUnit unit, boolean newsCoverageEnabled, BriefLabels lbl,
            java.util.function.Function<String, String> digestLookup,
            List<de.bsommerfeld.wsbg.terminal.db.DossierFact> dossier,
            List<de.bsommerfeld.wsbg.terminal.db.SubjectSentimentDayRecord> sentimentDays) {
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
        List<Article> freshNews = NewsProvenance.briefNews(unit, newsCoverageEnabled);
        List<Article> toldNews = new ArrayList<>();
        for (Article n : unit.news()) {
            if (!freshNews.contains(n)) toldNews.add(n);
        }
        if (!freshNews.isEmpty() || !toldNews.isEmpty()) {
            sb.append(lbl.newsHeader());
            // Already-woven items stay VISIBLE — a known fact remains the anchor the
            // room's next development hangs on — but compact (title only) and tagged,
            // so it frames the next line without being re-sold as fresh news. Only the
            // newest few: the eighth already-told title anchors nothing the first three
            // do not, and it competes for the window with news that IS new.
            if (!toldNews.isEmpty()) {
                sb.append(lbl.newsToldNote());
                for (Article n : toldNews.subList(0, Math.min(TOLD_NEWS_SHOWN, toldNews.size()))) {
                    sb.append("  - ").append(lbl.newsToldTag()).append(' ').append(n.title()).append('\n');
                }
            }
            // Char budget over the fresh items, the same economy the evidence block
            // has always run: render newest-first while the budget holds, then say
            // how many were dropped. Without it a unit whose twelve slots all carry
            // a full digest lands ~1.9k tokens of news alone — on the 8k rung of the
            // context ladder that is the difference between a brief and a silently
            // truncated one.
            int newsBudget = NEWS_CHAR_BUDGET;
            int compactBudget = NEWS_COMPACT_CHAR_BUDGET;
            int newsOmitted = 0;
            int newsOrdinal = 0;
            for (Article n : freshNews) {
                if (newsBudget <= 0) {
                    // Compact tail: title-only, still numbered — the model can cite
                    // it, the reader-facing refs stay reachable, and the token cost
                    // is a line, not a digest. Only when even this budget is spent
                    // does an item fall into the omitted count.
                    String title = n.title() == null ? "" : n.title();
                    int cost = title.length() + 12;
                    if (compactBudget - cost < 0) {
                        // Stop rendering entirely from here on: the [N#] ordinals must
                        // stay a strict PREFIX of the fresh list (the citation
                        // resolution indexes into it), so no later, shorter title may
                        // jump the queue.
                        compactBudget = -1;
                        newsOmitted++;
                        continue;
                    }
                    compactBudget -= cost;
                    sb.append("  - [N").append(++newsOrdinal).append("] ").append(title).append('\n');
                    continue;
                }
                int before = sb.length();
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
                // Substance under the title: the article's key-fact digest when the
                // background reader has it (beats the teaser — Yahoo's teaser is even
                // null), else the source teaser as before. Cache-only lookup, never blocks.
                String digest = digestLookup == null ? "" : digestLookup.apply(n.link());
                if (digest != null && !digest.isBlank()) {
                    String dg = digest.replace('\n', ' ').strip();
                    sb.append("\n      ").append(dg.length() > DIGEST_CHAR_CAP
                            ? dg.substring(0, DIGEST_CHAR_CAP) + "…" : dg);
                } else if (n.summary() != null && !n.summary().isBlank()) {
                    String sum = n.summary().replace('\n', ' ').strip();
                    sb.append("\n      ").append(sum.length() > 200 ? sum.substring(0, 200) + "…" : sum);
                }
                sb.append('\n');
                newsBudget -= sb.length() - before;
            }
            if (newsOmitted > 0) {
                sb.append(lbl.newsBudgetOmitted(newsOmitted));
            }
        }

        // The permanent dossier: verified news facts collected across sessions —
        // the subject's established knowledge, framed as NOT news. Facts whose
        // article currently renders fresh above are skipped (their digest already
        // stands under the title); newest facts kept within budget.
        if (dossier != null && !dossier.isEmpty()) {
            java.util.Set<String> freshLinks = new java.util.HashSet<>();
            for (Article n : freshNews) {
                if (n.link() != null && !n.link().isBlank()) freshLinks.add(n.link().trim());
            }
            List<de.bsommerfeld.wsbg.terminal.db.DossierFact> shownFacts = new ArrayList<>();
            for (de.bsommerfeld.wsbg.terminal.db.DossierFact f : dossier) {
                if (f.sourceUrl() != null && freshLinks.contains(f.sourceUrl().trim())) continue;
                shownFacts.add(f);
            }
            if (!shownFacts.isEmpty()) {
                sb.append(lbl.dossierHeader());
                int dStart = shownFacts.size();
                int dBudget = DOSSIER_CHAR_BUDGET;
                while (dStart > 0 && dBudget - shownFacts.get(dStart - 1).text().length() - 24 >= 0) {
                    dStart--;
                    dBudget -= shownFacts.get(dStart).text().length() + 24;
                }
                if (dStart > 0) {
                    sb.append(lbl.dossierOmitted(dStart));
                }
                for (de.bsommerfeld.wsbg.terminal.db.DossierFact f : shownFacts.subList(dStart, shownFacts.size())) {
                    sb.append("  - [").append(lbl.ago(age(Instant.ofEpochSecond(f.atEpoch()), now)));
                    String src = f.consolidated() ? lbl.dossierConsolidatedTag() : f.sourcePublisher();
                    if (src != null && !src.isBlank()) sb.append(", ").append(src);
                    sb.append("] ").append(f.text().replace('\n', ' ').strip()).append('\n');
                }
            }
        }

        // The daily sentiment history: how the room stood on past days — folded
        // deterministically from the archive, rendered as context. Short by
        // construction (a handful of one-liners), so no budget machinery.
        if (sentimentDays != null && !sentimentDays.isEmpty()) {
            sb.append(lbl.sentimentHistoryHeader());
            for (de.bsommerfeld.wsbg.terminal.db.SubjectSentimentDayRecord d : sentimentDays) {
                sb.append(lbl.sentimentDayLine(d.date(), d.majority(), d.headlineCount(), d.arc()));
            }
        }

        // The unit's room sheet: the room's older story, distilled — orientation
        // and anchor, explicitly framed as NOT news. Rendered before the raw
        // evidence so the fresh material below reads as the development ON TOP of
        // this known state. Newest lines kept within budget, the same economy as
        // every other block.
        List<SubjectUnit.FactLine> facts = unit.factSheet();
        if (!facts.isEmpty()) {
            sb.append(lbl.factSheetHeader());
            int factStart = facts.size();
            int factBudget = FACT_SHEET_CHAR_BUDGET;
            while (factStart > 0 && factBudget - facts.get(factStart - 1).text().length() - 16 >= 0) {
                factStart--;
                factBudget -= facts.get(factStart).text().length() + 16;
            }
            if (factStart > 0) {
                sb.append(lbl.factSheetOmitted(factStart));
            }
            for (SubjectUnit.FactLine f : facts.subList(factStart, facts.size())) {
                sb.append("  - [").append(lbl.ago(age(Instant.ofEpochSecond(f.atEpoch()), now)))
                        .append("] ").append(f.text()).append('\n');
            }
        }

        // Coverage boundary: evidence added on/before the unit's most recent
        // published headline was already in view when that line was written, so it
        // must NOT seed another headline. We OMIT that covered material here — the
        // story-memory headlines below ARE its context — and show only what arrived
        // SINCE the last headline. Time-based (not model-citation-based): a 4B model
        // under-cites sources, but the unit's own evidence + headline timestamps are
        // exact. Mirrors the per-cluster ReportBuilder coverage. Evidence already
        // distilled into the fact sheet (the watermark) is omitted the same way —
        // its substance stands condensed above, so showing it raw would re-sell it.
        long lastHeadlineEpoch = 0L;
        for (SubjectUnit.UnitHeadline h : unit.headlines()) {
            if (h.atEpoch() > lastHeadlineEpoch) lastHeadlineEpoch = h.atEpoch();
        }
        long absorbedUpTo = unit.factsUpToEpoch();
        long coveredBoundary = Math.max(lastHeadlineEpoch, absorbedUpTo);
        // MOOD evidence never enters this block. The room's undirected chatter is
        // the ROOM SHEET's material — read there as sentiment, where a sample is
        // enough to tell a mood — and it is unbounded, so letting it compete here
        // meant it won: the budget below keeps the newest, and the chatter is
        // appended last (see EventConsolidator). Measured before the split: 150
        // chatter comments pushed all 8 of a subject's real mentions out.
        List<SubjectUnit.EvidenceRef> mentions = new ArrayList<>();
        List<SubjectUnit.EvidenceRef> context = new ArrayList<>();
        int coveredOmitted = 0;
        for (SubjectUnit.EvidenceRef e : unit.evidence()) {
            if (e.isMood()) continue; // room-sheet material, not compose material
            if (coveredBoundary > 0 && e.addedAtEpoch() <= coveredBoundary) {
                coveredOmitted++;
                continue; // already reflected in a prior headline / the room sheet → omit
            }
            (e.isStory() ? mentions : context).add(e);
        }

        // Char budget over the fresh refs: keep the NEWEST that fit, drop the
        // oldest, and say so — never let Ollama truncate the prompt silently.
        // The subject's OWN mentions are served FIRST and the reply chains get
        // what is left. That is a priority inside one budget, not a fixed split:
        // a subject with few mentions still gets its full context rendered. It is
        // needed because the walk runs from the END of the list while the chains
        // are appended after the mentions they belong to — without the priority
        // the chains displace the very mentions they exist to explain (measured:
        // 20 mentions + 40 chain refs → 0 mentions in the brief).
        int budget = EVIDENCE_CHAR_BUDGET;
        int mStart = mentions.size();
        while (mStart > 0 && budget - mentions.get(mStart - 1).snippet().length() - 24 >= 0) {
            mStart--;
            budget -= mentions.get(mStart).snippet().length() + 24;
        }
        int cStart = context.size();
        while (cStart > 0 && budget - context.get(cStart - 1).snippet().length() - 24 >= 0) {
            cStart--;
            budget -= context.get(cStart).snippet().length() + 24;
        }
        boolean haveHeadlines = lastHeadlineEpoch > 0;
        sb.append(lbl.evidenceHeader(haveHeadlines));
        if (coveredOmitted > 0) {
            sb.append(absorbedUpTo > 0
                    ? lbl.absorbedOmitted(coveredOmitted)
                    : lbl.coveredOmitted(coveredOmitted));
        }
        if (mStart > 0) {
            sb.append(lbl.budgetOmitted(mStart));
        }
        for (SubjectUnit.EvidenceRef e : mentions.subList(mStart, mentions.size())) {
            String loc = SubjectUnit.EvidenceRef.VISION.equals(e.source()) ? lbl.visionLoc()
                    : (e.commentId() == null ? e.threadId() : e.commentId());
            sb.append("  - [").append(loc).append(", ")
                    .append(lbl.ago(age(Instant.ofEpochSecond(e.addedAtEpoch()), now))).append("] ")
                    .append(e.snippet()).append('\n');
        }
        if (cStart < context.size()) {
            sb.append(lbl.conversationContext(cStart));
            for (SubjectUnit.EvidenceRef e : context.subList(cStart, context.size())) {
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
