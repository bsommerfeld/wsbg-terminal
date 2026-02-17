package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
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
     * Merges the new report data with any previously cached context, creating
     * a rolling window. Older context is trimmed to 4000 chars to keep the
     * prompt within token limits while preserving temporal continuity.
     */
    String buildCombinedContext(InvestigationCluster inv, String reportData) {
        String existing = inv.cachedContext != null ? inv.cachedContext : "";
        if (existing.length() > 4000) {
            existing = existing.substring(existing.length() - 4000);
        }
        LocalTime now = LocalTime.now();
        String timeStr = String.format("[%02d:%02d]", now.getHour(), now.getMinute());
        return existing + "\n\n=== UPDATE " + timeStr + " ===\n" + reportData;
    }

    /**
     * Assembles the headline prompt with a binary VERDICT gate.
     * The prompt forces the AI to explicitly ACCEPT or REJECT before
     * generating any headline — small models (4b) reliably fail to
     * return bare "-1" but can handle structured VERDICT responses.
     *
     * @param historyBlock previous headlines to prevent repetition
     * @param context      combined cluster evidence
     * @param showAll      whether topic filtering is disabled
     * @param topics       user-defined topic keywords (may be null/empty)
     */
    String buildHeadlinePrompt(String historyBlock, String context,
            boolean showAll, List<String> topics) {

        String topicFilter;
        String topicInstruction;

        if (showAll || topics == null || topics.isEmpty()) {
            topicFilter = "";
            topicInstruction = "No topic restriction — all financial content is relevant.";
        } else {
            topicFilter = "USER TOPICS: " + String.join(", ", topics) + "\n"
                    + "JARGON: 'Eselmetalle'/'Edelmetalle' = precious metals (Gold, Silber). "
                    + "'die Fetten' = US market. "
                    + "'Hebel' = leveraged derivative. "
                    + "'KO'/'Knockout' = barrier option.";
            topicInstruction = "Is this cluster DIRECTLY about one of the user's topics? "
                    + "Use semantic understanding (e.g., 'Zinsen' = interest rates). "
                    + "Indirect connections do NOT count — 'stocks fell so gold might move' is NOT relevant. "
                    + "If NOT directly relevant, output VERDICT: REJECT";
        }

        return PromptLoader.load("headline-generation", Map.of(
                "HISTORY", historyBlock,
                "CONTEXT", context,
                "TOPIC_FILTER", topicFilter,
                "TOPIC_INSTRUCTION", topicInstruction));
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
     * Expects the format {@code REPORT: [PRIORITY] headline text}.
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
            sb.append("[VISION ANALYSIS]: ").append(result).append("\n");
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
