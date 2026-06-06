package de.bsommerfeld.wsbg.terminal.lab;

import com.google.inject.Injector;
import de.bsommerfeld.wsbg.terminal.agent.AgentBrain;
import de.bsommerfeld.wsbg.terminal.agent.ClusterEngine;
import de.bsommerfeld.wsbg.terminal.agent.ClusterEngine.AssignOutcome;
import de.bsommerfeld.wsbg.terminal.agent.ClusterRegistry;
import de.bsommerfeld.wsbg.terminal.agent.EditorialAgent;
import de.bsommerfeld.wsbg.terminal.agent.EditorialAgent.ClusterEditorial;
import de.bsommerfeld.wsbg.terminal.agent.EditorialAgent.EditorialListener;
import de.bsommerfeld.wsbg.terminal.agent.EditorialAgent.SubjectDraft;
import de.bsommerfeld.wsbg.terminal.agent.EditorialAgent.UnitDraft;
import de.bsommerfeld.wsbg.terminal.agent.HeadlineCollator;
import de.bsommerfeld.wsbg.terminal.agent.HeadlineWriter.Draft;
import de.bsommerfeld.wsbg.terminal.agent.InvestigationCluster;
import de.bsommerfeld.wsbg.terminal.agent.SubjectRegistry;
import de.bsommerfeld.wsbg.terminal.agent.SubjectUnit;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.lab.ThreadIngestor.IngestResult;
import de.bsommerfeld.wsbg.terminal.reddit.RedditSource;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooNewsItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The actual editorial harness: ingest given Reddit threads, run the real
 * {@link ClusterEngine} + {@link EditorialAgent} over them, and narrate every
 * step to a line sink. The sink decouples <em>what</em> is reported from
 * <em>where</em> it goes — {@link LabWindow}'s trace area and the boot console
 * both feed off the same renderer.
 *
 * <p>All components are long-lived for the process: a single {@link LabRunner}
 * keeps Ollama warm and the cluster state accumulating across many submissions,
 * so you can build a scenario incrementally and only pay model-load once.
 */
public final class LabRunner {

    private final GlobalConfig config;
    private final AgentBrain brain;
    private final RedditSource source;
    private final RedditRepository redditRepository;
    private final AgentRepository agentRepository;
    private final ClusterEngine clusterEngine;
    private final ClusterRegistry registry;
    private final SubjectRegistry subjects;
    private final EditorialAgent editorial;
    private final HeadlineCollator collator;
    private final ThreadIngestor ingestor;

    public LabRunner(Injector injector, Consumer<String> bootSink) {
        this.config = injector.getInstance(GlobalConfig.class);
        // Forcing AgentBrain up starts our isolated Ollama + loads the editorial
        // and vision models (and confirms embeddinggemma is reachable).
        bootSink.accept("Starting Ollama + loading models...");
        this.brain = injector.getInstance(AgentBrain.class);
        bootSink.accept("Models ready (agent model: " + brain.getAgentModelName() + ").");

        this.source = injector.getInstance(RedditSource.class);
        this.redditRepository = injector.getInstance(RedditRepository.class);
        this.agentRepository = injector.getInstance(AgentRepository.class);
        this.clusterEngine = injector.getInstance(ClusterEngine.class);
        this.registry = injector.getInstance(ClusterRegistry.class);
        this.subjects = injector.getInstance(SubjectRegistry.class);
        this.editorial = injector.getInstance(EditorialAgent.class);
        this.collator = injector.getInstance(HeadlineCollator.class);

        String fallbackSub = config.getReddit().getSubreddits().isEmpty()
                ? "wallstreetbetsGER" : config.getReddit().getSubreddits().get(0);
        this.ingestor = new ThreadIngestor(source, redditRepository, fallbackSub);
    }

    public String agentModelName() {
        return brain.getAgentModelName();
    }

    /**
     * Wipes the accumulated cluster state, threads, and headlines so the next
     * submission starts from a clean slate. Ollama + the loaded models stay up.
     */
    public synchronized void reset(Consumer<String> out) {
        registry.clear();
        subjects.clear();
        redditRepository.clear();
        agentRepository.clear();
        collator.clear();
        out.accept("Reset: cleared all clusters, subject units, threads, headlines, and collation window. Ollama stays warm.");
    }

    /**
     * Ingests + clusters + runs the editorial pass for the given thread URLs,
     * streaming a step-by-step trace to {@code out}. Serialised: one run at a
     * time, matching the production "one tick at a time" discipline.
     */
    public synchronized void run(List<String> urls, Consumer<String> out) {
        // ---- Phase 0: context relief — prune consumed content past the TTL ----
        // The units themselves stand as long as they like; only their already-used
        // content (evidence + published headlines older than the snapshot TTL) is
        // dropped, so the model never re-reads hour-old comments or headlines.
        long ttlMin = config.getReddit().getSnapshotTtlMinutes();
        int pruned = subjects.pruneContentOlderThan(java.time.Duration.ofMinutes(ttlMin));
        if (pruned > 0) {
            out.accept(String.format(
                    "Context relief: pruned %d evidence/headline entr(ies) older than %d min (units kept).%n",
                    pruned, ttlMin));
        }

        // ---- Phase 1: ingest + cluster, thread by thread ----
        section(out, "Ingest + clustering");
        java.util.Set<String> touchedClusters = new java.util.LinkedHashSet<>();
        int n = 0;
        for (String url : urls) {
            n++;
            out.accept(String.format("%n[%d/%d] %s", n, urls.size(), url));
            IngestResult res;
            try {
                res = ingestor.ingest(url);
            } catch (Exception e) {
                out.accept("  ! ingest failed: " + e.getMessage());
                continue;
            }
            if (res.thread() == null) {
                out.accept("  ! could not parse a thread id from this URL — skipped");
                continue;
            }
            RedditThread t = res.thread();
            out.accept(String.format("  source     : %s%s", res.sourceName(),
                    res.fetched() ? "" : "  (fetch returned nothing — using stub)"));
            out.accept(String.format("  thread     : %s  r/%s", t.id(), t.subreddit()));
            out.accept(String.format("  title      : %s", t.title()));
            String body = t.textContent() == null ? "" : t.textContent().replace('\n', ' ');
            if (!body.isBlank()) out.accept(String.format("  body       : %s", truncate(body, 220)));
            out.accept(String.format("  signals    : score=%d, comments=%d (%d fetched), images=%d%s",
                    t.score(), t.numComments(), res.commentCount(), t.imageUrls().size(),
                    t.pollData() != null ? ", poll" : ""));

            if (!t.imageUrls().isEmpty()) {
                out.accept("  vision     : analysing " + t.imageUrls().size() + " image(s)…");
            }
            String visionText = describeImages(t.imageUrls());
            if (!visionText.isBlank()) {
                out.accept(String.format("  vision     : %s", truncate(visionText.replace('\n', ' '), 240)));
            } else if (!t.imageUrls().isEmpty()) {
                out.accept("  vision     : (no readable text in image — chart/graphic?)");
            }

            AssignOutcome outcome = clusterEngine.assign(t, 0, 0, visionText);
            touchedClusters.add(outcome.clusterId());
            out.accept("  → " + describeOutcome(outcome));
        }

        // ---- Phase 2: cluster summary ----
        section(out, "Clusters formed: " + registry.size());
        List<InvestigationCluster> clusters = new ArrayList<>(registry.getAllClusters());
        for (InvestigationCluster c : clusters) {
            out.accept(String.format("%n  cluster %s", c.id));
            out.accept(String.format("    title   : %s", c.initialTitle));
            out.accept(String.format("    threads : %d  %s", c.activeThreadIds.size(), c.activeThreadIds));
            out.accept(String.format("    tickers : %s", c.tickers.isEmpty() ? "(none)" : c.tickers));
            out.accept(String.format("    totals  : score=%d, comments=%d", c.totalScore, c.totalComments));
        }

        // ---- Phase 3 (B, step 1): attribute subjects into the feed-wide registry ----
        // No compose yet — this step is about SEEING the SubjectUnits accumulate
        // (per-unit compose with NEW/UPDATE is step 3). The registry is a
        // singleton, so units persist across runs: submit two NVIDIA threads and
        // watch the NVIDIA unit's evidence grow.
        // Only the clusters that gained/changed a thread THIS run are re-attributed
        // — exactly what production does (PassiveMonitorService processes changed
        // threads, never the whole feed). An untouched cluster's evidence hasn't
        // changed, so re-extracting it would be wasted model + Yahoo calls and
        // (pre-fix) would needlessly re-wake its units.
        section(out, "Subject attribution");
        for (InvestigationCluster c : clusters) {
            if (!touchedClusters.contains(c.id)) {
                out.accept(String.format("%n  cluster %s — unchanged this run, skipped", c.id));
                continue;
            }
            out.accept(String.format("%n  cluster %s — \"%s\"", c.id, c.initialTitle));
            out.accept("    extract → resolve → attribute…");
            try {
                List<ResolvedSubject> resolved = editorial.attributeCluster(c.id, subjects);
                out.accept(String.format("    %d subject(s) attributed", resolved.size()));
            } catch (Exception e) {
                out.accept("    ! attribution failed: " + e.getMessage());
            }
        }

        // ---- Phase 3.5: conservative identity-merge (name unit → ticker unit) ----
        int merged = subjects.mergeIdentities();
        if (merged > 0) {
            out.accept(String.format("%n  identity-merge: folded %d duplicate unit(s) into their ticker", merged));
        }

        // ---- Phase 4: per-unit compose with NEW/UPDATE — streamed live ----
        // Only the units that gained evidence this run are dirty; each composes
        // ONE headline from its accumulated evidence + Yahoo data + its OWN prior
        // headlines, and the model tags it NEW (fresh angle) or UPDATE (continues
        // a prior line). Across runs you see a re-touched unit produce an UPDATE.
        java.util.Set<String> dirty = subjects.drainDirty();
        List<SubjectUnit> toCompose = dirty.stream()
                .map(subjects::get).filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(SubjectUnit::evidenceCount).reversed())
                .toList();
        section(out, "Headlines per subject unit (" + toCompose.size() + " dirty, streaming live)");
        int published = 0;
        for (SubjectUnit u : toCompose) {
            UnitDraft ud = editorial.composeUnit(u);
            boolean stored = renderUnitDraft(out, u, ud);
            if (stored) published++;
        }
        out.accept(String.format("%n  → %d headline(s) published this run", published));

        // ---- Phase 5: the accumulated subject registry ----
        renderSubjectUnits(out);

        section(out, "Done");
    }

    /** Renders one streamed per-unit headline; stores it on the unit when it actually changed. Returns whether stored. */
    private boolean renderUnitDraft(Consumer<String> out, SubjectUnit u, UnitDraft ud) {
        String head = u.isInstrument() ? u.canonicalName() + " → " + u.ticker() : u.canonicalName();
        if (ud.draft() == null || ud.draft().headline() == null || ud.draft().headline().isBlank()) {
            out.accept(String.format("%n  ● %s → keine Headline  [%s]", head, fmtMs(ud.ms())));
            String raw = ud.raw() == null || ud.raw().isBlank() ? "(empty response)" : ud.raw().strip();
            for (String line : truncate(raw, 800).split("\n", -1)) out.accept("      │ " + line);
            return false;
        }
        String text = ud.draft().headline();
        boolean changed = !text.equalsIgnoreCase(u.lastHeadlineText());
        String tag = !changed ? "unverändert" : (ud.isUpdate() ? "UPDATE" : "NEW");
        String salv = (ud.salvaged() ? " [gerettet]" : "")
                + (ud.unverified() ? " [⚠ ungeprüft: Zahl aus Nutzer-Post, nicht aus Yahoo]" : "");
        out.accept(String.format("%n  ● %s  [%s]  [%s]%s", head, tag, fmtMs(ud.ms()), salv));
        out.accept("      " + describeDraft(ud.draft()));
        if (!ud.citedNewsIds().isEmpty()) {
            out.accept("      cites news: " + String.join(", ", ud.citedNewsIds()));
        }
        if (changed) {
            u.addHeadline(text, ud.isUpdate());
            u.markNewsCovered(ud.citedNewsIds()); // 3b: consumed news won't be offered again
            // Collation: a near-duplicate of a still-on-screen headline replaces it
            // in place instead of stacking a new row.
            HeadlineCollator.Decision col = collator.offer(u.id, text);
            if (col.collated()) {
                out.accept(String.format(Locale.ROOT,
                        "      ↻ collated (sim %.2f) → ersetzt frühere Zeile: \"%s\"",
                        col.similarity(), truncate(col.replacedText(), 90)));
            }
            return true;
        }
        return false;
    }

    /** Renders the feed-wide subject registry — the heart of #2 (B). */
    private void renderSubjectUnits(Consumer<String> out) {
        List<SubjectUnit> units = new ArrayList<>(subjects.all());
        units.sort(Comparator.comparingInt(SubjectUnit::evidenceCount).reversed());
        section(out, "Subject units: " + units.size() + " (accumulate across runs)");
        for (SubjectUnit u : units) {
            String head = u.isInstrument()
                    ? String.format("%s → %s", u.canonicalName(), u.ticker())
                    : u.canonicalName() + "  (no ticker — theme/person)";
            MarketSnapshot s = u.snapshot();
            String price = s != null && s.hasPrice()
                    ? String.format(Locale.ROOT, "  %.2f%s (%+.2f%%)", s.price(),
                        s.currency() == null || s.currency().isEmpty() ? "" : " " + s.currency(),
                        s.dayChangePercent())
                    : "";
            out.accept(String.format("%n  ● %s%s", head, price));
            out.accept(String.format("    evidence: %d  ·  news: %d", u.evidenceCount(), u.news().size()));
            for (SubjectUnit.EvidenceRef e : u.evidence()) {
                String where = "vision".equals(e.source()) ? "image in " + e.threadId()
                        : e.commentId() == null ? "post " + e.threadId()
                        : "comment " + e.commentId() + " in " + e.threadId();
                out.accept(String.format("      ├ [%s] %s", where, truncate(e.snippet(), 120)));
            }
        }
    }

    // ---- rendering ----

    /** Renders one streamed per-subject result the moment it lands. */
    private static void renderSubjectDraft(Consumer<String> out, SubjectDraft sd) {
        String salv = sd.salvaged() ? " [aus kaputtem JSON gerettet]" : "";
        if (sd.draft() != null) {
            out.accept(String.format("      • %s  [%s]%s", sd.label(), fmtMs(sd.ms()), salv));
            out.accept("        " + describeDraft(sd.draft()));
        } else {
            // No headline: either the model chose "" (nothing to say) or the
            // reply was unparseable — dump the raw so it's obvious which.
            out.accept(String.format("      • %s → keine Headline  [%s]", sd.label(), fmtMs(sd.ms())));
            String raw = sd.raw() == null || sd.raw().isBlank() ? "(empty response)" : sd.raw().strip();
            for (String line : truncate(raw, 1200).split("\n", -1)) {
                out.accept("        │ " + line);
            }
        }
    }

    private static String describeResolved(ResolvedSubject r) {
        StringBuilder sb = new StringBuilder(r.canonicalName());
        if (r.isInstrument()) {
            sb.append(" → ").append(r.ticker());
            MarketSnapshot s = r.snapshot();
            if (s != null && s.hasPrice()) {
                sb.append(String.format(Locale.ROOT, "  %.2f%s", s.price(),
                        s.currency() == null || s.currency().isEmpty() ? "" : " " + s.currency()));
                if (Double.isFinite(s.dayChangePercent())) {
                    sb.append(String.format(Locale.ROOT, " (%+.2f%% today)", s.dayChangePercent()));
                }
            }
        } else if (!r.news().isEmpty()) {
            sb.append(" → no ticker (theme/person — news only)");
        } else {
            sb.append(" → nothing (rest on cluster sentiment)");
        }
        return sb.toString();
    }

    private static String describeDraft(Draft d) {
        StringBuilder tag = new StringBuilder("[");
        tag.append(d.highlight() == null || d.highlight().isBlank()
                ? "NORMAL" : d.highlight().toUpperCase(Locale.ROOT));
        if (d.tickerSymbol() != null && !d.tickerSymbol().isBlank()) tag.append(' ').append(d.tickerSymbol());
        if (d.priceMovePercent() != null) tag.append(String.format(Locale.ROOT, " %+.1f%%", d.priceMovePercent()));
        if (d.sentiment() != null && !d.sentiment().isBlank()) tag.append(' ').append(d.sentiment());
        tag.append("] ");
        return tag + d.headline();
    }

    private static String describeOutcome(AssignOutcome o) {
        return switch (o.kind()) {
            case NEW -> "NEW cluster " + o.clusterId()
                    + (o.tickers().isEmpty() ? "" : "  tickers=" + o.tickers());
            case JOIN_COSINE -> String.format(Locale.ROOT,
                    "JOINED %s via cosine (sim=%.2f)", o.clusterId(), o.similarity());
            case UPDATE_COSINE -> String.format(Locale.ROOT,
                    "re-touched %s via cosine (sim=%.2f)", o.clusterId(), o.similarity());
            case JOIN_TICKER -> "JOINED " + o.clusterId() + " via ticker overlap " + o.tickers();
            case UPDATE_TICKER -> "re-touched " + o.clusterId() + " via ticker overlap " + o.tickers();
        };
    }

    /** Mirrors PassiveMonitorService.describeAll: first few slides, labelled, joined. */
    private String describeImages(List<String> urls) {
        if (urls.isEmpty()) return "";
        if (urls.size() == 1) return brain.describeImage(urls.get(0));
        int n = Math.min(urls.size(), 4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String desc = brain.describeImage(urls.get(i));
            if (desc == null || desc.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("[IMAGE ").append(i + 1).append('/').append(n).append("] ").append(desc);
        }
        return sb.toString();
    }

    private static void section(Consumer<String> out, String title) {
        out.accept("\n=== " + title + " " + "=".repeat(Math.max(3, 56 - title.length())));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Compact ms → "850ms" / "4.2s". */
    private static String fmtMs(long ms) {
        return ms < 1000 ? ms + "ms" : String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }
}
