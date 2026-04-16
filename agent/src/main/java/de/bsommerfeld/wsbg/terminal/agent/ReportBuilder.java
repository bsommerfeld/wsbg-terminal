package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds AI-ready context reports from an {@link InvestigationCluster}
 * by fetching thread/comment data and performing optional vision analysis.
 */
final class ReportBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(ReportBuilder.class);

    private final RedditRepository repository;
    private final AgentBrain brain;

    ReportBuilder(RedditRepository repository, AgentBrain brain) {
        this.repository = repository;
        this.brain = brain;
    }

    /**
     * Builds a structured text block containing cluster metadata, thread content,
     * comments, and vision analysis for up to 3 source threads. This is the raw
     * evidence the AI uses to assess the cluster.
     */
    String buildReportData(InvestigationCluster inv) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- CASE ID: ").append(inv.id).append(" ---\n");
        sb.append("Cluster Topic: ").append(inv.initialTitle).append("\n");
        sb.append("Cluster Age: ").append(formatAge(inv.threadCreatedUTC)).append("\n");
        sb.append("Significance Score: ").append(String.format("%.1f", inv.currentSignificance)).append("\n");
        sb.append("Active Threads: ").append(inv.activeThreadIds.size()).append("\n\n");

        Set<String> targets = new HashSet<>();
        if (inv.bestThreadId != null)
            targets.add(inv.bestThreadId);
        for (String id : inv.activeThreadIds) {
            if (targets.size() >= 3)
                break;
            targets.add(id);
        }

        int idx = 1;
        for (String threadId : targets) {
            try {
                RedditThread thread = repository.getThread(threadId);
                if (thread == null)
                    continue;

                sb.append("=== THREAD SOURCE ").append(idx++).append(" ===\n");
                sb.append("Title: ").append(thread.title()).append("\n");

                appendVisionIfPresent(sb, thread.imageUrl());
                appendTextSnippet(sb, thread.textContent());
                appendComments(sb, threadId);
                sb.append("\n");
            } catch (Exception e) {
                LOG.warn("Failed to fetch context for {}", threadId);
            }
        }
        sb.append("-----------------------------\n\n");
        return sb.toString();
    }

    /**
     * Merges the new report data with previously cached context using
     * a section-aware rolling window. Each scan cycle appends a timestamped
     * UPDATE section. When the combined context exceeds the budget, older
     * sections are condensed to their header line only — preserving the
     * temporal narrative arc for CHL (Continuing Headlines) while giving
     * the most recent evidence full detail.
     */
    String buildCombinedContext(InvestigationCluster inv, String reportData) {
        LocalTime now = LocalTime.now();
        String timeStr = String.format("[%02d:%02d]", now.getHour(), now.getMinute());
        String newSection = "=== UPDATE " + timeStr + " ===\n" + reportData;

        String existing = inv.cachedContext != null ? inv.cachedContext : "";
        String combined = existing + "\n\n" + newSection;

        // Budget: keep the full context under 6000 chars. If over budget,
        // compress oldest sections to header-only until it fits.
        if (combined.length() <= 6000)
            return combined;

        return compressSections(combined, 6000);
    }

    /**
     * Splits context into UPDATE sections and compresses oldest ones
     * to their header line until the total fits within the char budget.
     * The most recent section is never compressed — the AI always sees
     * full evidence for the current evaluation.
     */
    private String compressSections(String context, int budget) {
        String delimiter = "=== UPDATE";
        List<String> sections = new ArrayList<>();

        int pos = 0;
        int nextIdx;
        while ((nextIdx = context.indexOf(delimiter, pos + 1)) != -1) {
            sections.add(context.substring(pos, nextIdx));
            pos = nextIdx;
        }
        sections.add(context.substring(pos));

        // Compress from oldest to newest, skip the last (most recent) section
        for (int i = 0; i < sections.size() - 1; i++) {
            if (totalLength(sections) <= budget)
                break;

            String section = sections.get(i);
            int headerEnd = section.indexOf("\n");
            if (headerEnd > 0) {
                // Keep only the header line as a temporal anchor
                sections.set(i, section.substring(0, headerEnd) + "\n[condensed]\n");
            }
        }

        return String.join("", sections);
    }

    private int totalLength(List<String> sections) {
        int total = 0;
        for (String s : sections)
            total += s.length();
        return total;
    }

    /**
     * Assembles the headline prompt. For first-time evaluations, the full
     * combined context is supplied. For CHL re-evaluations, only the
     * delta (new evidence since the last headline) is passed as
     * {@code deltaContext} — the AI receives the compressed old context
     * summary plus the highlighted new data, reducing noise and forcing
     * focus on what actually changed.
     *
     * @param historyBlock  previous headlines for this cluster
     * @param context       full combined cluster context
     * @param deltaContext  new lines since last evaluation, or empty string for first eval
     * @param language      response language
     */
    String buildHeadlinePrompt(String historyBlock, String context,
            String deltaContext, String language) {
        String effectiveContext = deltaContext.isBlank() ? context
                : context + "\n\n=== NEW SINCE LAST HEADLINE ===\n" + deltaContext;
        return PromptLoader.load("headline-generation", Map.of(
                "HISTORY", historyBlock,
                "CONTEXT", effectiveContext,
                "LANGUAGE", language));
    }

    /**
     * Extracts lines from {@code newContext} that are absent in {@code oldContext}.
     * Used to pass only incremental evidence to the AI on CHL re-evaluations,
     * preventing re-analysis of already-evaluated content.
     */
    String buildDeltaContext(String newContext, String oldContext) {
        if (oldContext == null || oldContext.isBlank())
            return "";
        Set<String> oldLines = new HashSet<>();
        for (String line : oldContext.split("\n"))
            oldLines.add(line.trim());

        StringBuilder delta = new StringBuilder();
        for (String line : newContext.split("\n")) {
            if (!line.isBlank() && !oldLines.contains(line.trim()))
                delta.append(line).append("\n");
        }
        return delta.toString().trim();
    }

    /**
     * Checks whether the AI response contains an explicit acceptance.
     * The prompt demands VERDICT: ACCEPT/REJECT — only explicit
     * acceptance is treated as valid.
     */
    boolean isAccepted(String response) {
        return response.contains("VERDICT: ACCEPT") || response.contains("VERDICT:ACCEPT");
    }

    /**
     * Extracts the headline text from the AI's structured response.
     * Expects the format {@code REPORT: headline text}.
     * Returns empty string if no valid headline is found.
     */
    String extractHeadline(String response) {
        for (String line : response.split("\n")) {
            if (line.trim().startsWith("REPORT:")) {
                String core = line.substring(line.indexOf(":") + 1).trim();
                if (!core.isEmpty() && !core.contains("-1"))
                    return core;
            }
        }
        return "";
    }

    private void appendVisionIfPresent(StringBuilder sb, String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty())
            return;
        sb.append(" [MAIN IMAGE]: ").append(imageUrl).append("\n");
        try {
            String result = brain.see(imageUrl);
            sb.append("[IMAGE ANALYSIS]: ").append(result).append("\n");
        } catch (Exception e) {
            LOG.error("Vision failed: {}", e.getMessage());
        }
    }

    private void appendTextSnippet(StringBuilder sb, String text) {
        if (text == null || text.isEmpty())
            return;
        String snippet = text.length() > 500 ? text.substring(0, 500) + "..." : text;
        sb.append("Content Snippet: ").append(snippet).append("\n");
    }

    private void appendComments(StringBuilder sb, String threadId) {
        List<RedditComment> comments = repository.getCommentsForThread(threadId, 15);
        if (comments.isEmpty())
            return;
        sb.append("RELEVANT COMMENTS:\n");
        for (RedditComment c : comments) {
            String body = c.body() != null ? c.body() : "[deleted]";
            sb.append("- ").append(c.author()).append(" (Score: ")
                    .append(c.score()).append("): ").append(body).append("\n");
            for (String img : c.imageUrls()) {
                sb.append("  [IMAGE]: ").append(img).append("\n");
            }
        }
    }

    private String formatAge(long createdUTC) {
        if (createdUTC <= 0)
            return "Unknown";
        long minutes = Duration.between(Instant.ofEpochSecond(createdUTC), Instant.now()).toMinutes();
        return String.format("%dh %dm", minutes / 60, minutes % 60);
    }
}
