package de.bsommerfeld.wsbg.terminal.agent;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStatusEvent;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamStartEvent;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.UserLanguage;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TriggerAgentAnalysisEvent;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper.ThreadAnalysisContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates user-facing chat, analysis pipelines, and vision requests.
 * Bridges {@link AgentBrain} capabilities with the UI event system.
 *
 * <p>
 * Three interaction modes:
 * <ul>
 * <li><strong>Chat</strong> — {@link #sendUserMessage} sends user input and
 * delivers the complete AI response to the UI</li>
 * <li><strong>Analysis</strong> — {@link #analyzeText} runs a reasoning pass
 * and delivers the result in the user's configured language</li>
 * <li><strong>Vision</strong> — {@link #analyzeVision} delegates image
 * interpretation to the vision model</li>
 * </ul>
 */
@Singleton
public class ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

    private final AgentBrain brain;
    private final ApplicationEventBus eventBus;
    private final I18nService i18n;
    private final RedditScraper scraper;
    private final PassiveMonitorService passiveMonitor;
    private final UserLanguage userLanguage;

    @Inject
    public ChatService(AgentBrain brain, ApplicationEventBus eventBus, I18nService i18n,
            GlobalConfig config, RedditScraper scraper, PassiveMonitorService passiveMonitor) {
        this.brain = brain;
        this.eventBus = eventBus;
        this.i18n = i18n;
        this.scraper = scraper;
        this.passiveMonitor = passiveMonitor;
        this.userLanguage = config.getUser().getUserLanguage();
        this.eventBus.register(this);
    }

    // -- Public API --

    /**
     * Handles a direct user chat message. Runs the AI reasoning asynchronously
     * and delivers the complete response to the UI.
     *
     * @param message the user's raw input text
     */
    public void sendUserMessage(String message) {
        LOG.info("User: {}", message);
        eventBus.post(new LogEvent("User: " + message, "INFO"));
        eventBus.post(new LogEvent("Agent thinking...", "INFO"));

        CompletableFuture.supplyAsync(() -> brain.ask("default", message))
                .thenAccept(response -> {
                    if (response == null) {
                        eventBus.post(new LogEvent("Agent Error: Brain not ready", "ERROR"));
                        return;
                    }
                    deliverToUi(response);
                });
    }

    /**
     * Runs a single-pass analysis. Gemma4 reasons and responds directly
     * in the user's language — no separate translation step required.
     *
     * @param prompt the analysis prompt (typically enriched with thread/market
     *               context)
     */
    public void analyzeText(String prompt) {
        String analysisId = "analysis-" + UUID.randomUUID();
        LOG.info("Analyzing [ID={}]: {}", analysisId, prompt);
        eventBus.post(new LogEvent("Agent thinking...", "INFO"));

        CompletableFuture.supplyAsync(() -> brain.ask(analysisId, prompt))
                .thenAccept(response -> {
                    if (response == null) {
                        eventBus.post(new LogEvent("Agent Error: Brain not ready", "ERROR"));
                        return;
                    }
                    deliverToUi(response);
                });
    }

    /**
     * Returns the raw AI response without UI delivery.
     * Used by internal subsystems that need the unprocessed output.
     *
     * @param prompt the prompt to send
     * @return future completing with the full response text
     */
    public CompletableFuture<String> askRaw(String prompt) {
        String id = "raw-" + UUID.randomUUID();
        LOG.info("Raw query [ID={}]: {}", id, prompt);
        return CompletableFuture.supplyAsync(() -> brain.ask(id, prompt));
    }

    /**
     * Submits an image URL for vision model analysis.
     *
     * @param imageUrl publicly accessible URL of the image to analyze
     * @return future completing with the vision model's description
     */
    public CompletableFuture<String> analyzeVision(String imageUrl) {
        LOG.info("Vision analyzing: {}", imageUrl);
        eventBus.post(new LogEvent("Vision Model scanning image...", "INFO"));
        return CompletableFuture.supplyAsync(() -> brain.see(imageUrl));
    }

    // -- Event Handlers --

    /**
     * Reacts to analysis trigger events posted by the UI or other subsystems.
     * Routes to reference-based or freeform analysis depending on the prompt
     * format.
     */
    @Subscribe
    public void onTrigger(TriggerAgentAnalysisEvent event) {
        if (event.prompt() != null && event.prompt().startsWith("analyze-ref:")) {
            String ref = event.prompt().substring(12).trim();
            handleRefAnalysis(ref);
        } else {
            analyzeText(event.prompt());
        }
    }

    // -- Internal --

    /**
     * Delivers a complete AI response to the UI. Posts start and end
     * events so the UI layer can render the result.
     */
    private void deliverToUi(String response) {
        LOG.info("[AI-RESPONSE] {}", response);
        eventBus.post(new AgentStreamStartEvent());
        eventBus.post(new AgentStreamEndEvent(response));
    }

    /**
     * Handles a reference-based analysis. If the reference starts with {@code ID:},
     * attempts to use cached investigation context before falling back to a fresh
     * Reddit fetch.
     */
    private void handleRefAnalysis(String reference) {
        if (reference.startsWith("ID:")) {
            String invId = reference.substring(3);
            String cachedContext = passiveMonitor.getInvestigationContext(invId);

            if (cachedContext != null) {
                eventBus.post(new AgentStatusEvent(i18n.get("log.agent.analyzing_cached_evidence")));
                CompletableFuture.supplyAsync(() -> enrichWithVision(cachedContext))
                        .thenAccept(ctx -> analyzeText(buildObservationPrompt(ctx)));
                return;
            }
            eventBus.post(new LogEvent("Cached evidence expired. Re-fetching...", "WARN"));
        }
        handlePermalinkAnalysis(reference);
    }

    /**
     * Appends vision analysis output to existing context if an
     * {@code IMAGE_AVAILABLE:}
     * marker is present. Fails silently — a missing image should not block
     * analysis.
     */
    private String enrichWithVision(String context) {
        if (!context.contains("IMAGE_AVAILABLE:"))
            return context;

        int start = context.indexOf("IMAGE_AVAILABLE: ") + 17;
        int end = context.indexOf("\n", start);
        if (end == -1)
            end = context.length();
        String url = context.substring(start, end).trim();

        eventBus.post(new LogEvent("Performing on-demand vision analysis...", "INFO"));
        try {
            return context + "\n[ON-DEMAND IMAGE ANALYSIS]: " + brain.see(url);
        } catch (Exception e) {
            LOG.error("On-demand vision failed", e);
            return context;
        }
    }

    /**
     * Fetches the latest thread context from Reddit and builds a complete
     * analysis prompt including title, content, vision data, and top comments.
     */
    private void handlePermalinkAnalysis(String permalink) {
        LOG.info("On-demand analysis for: {}", permalink);
        eventBus.post(new LogEvent("Fetching latest context from Reddit...", "INFO"));

        CompletableFuture.supplyAsync(() -> buildThreadPrompt(permalink))
                .thenAccept(prompt -> {
                    if (prompt.startsWith("ERROR:")) {
                        String msg = prompt.equals("ERROR:EMPTY_CONTEXT")
                                ? "Failed to retrieve thread content."
                                : "Error: " + prompt.substring(6);
                        eventBus.post(new LogEvent("[SYSTEM] " + msg, "ERROR"));
                    } else {
                        analyzeText(prompt);
                    }
                });
    }

    private String buildThreadPrompt(String permalink) {
        try {
            ThreadAnalysisContext context = scraper.fetchThreadContext(permalink);
            if (context.isEmpty())
                return "ERROR:EMPTY_CONTEXT";

            StringBuilder sb = new StringBuilder();
            sb.append(PromptLoader.load("thread-analysis", Map.of(
                    "LANGUAGE", userLanguage.displayName()))).append("\n\nTHREAD DATA:\n");

            if (context.title != null)
                sb.append("Title: ").append(context.title).append("\n\n");

            if (context.imageUrl != null && !context.imageUrl.isEmpty()) {
                eventBus.post(new LogEvent("Performing on-demand vision analysis...", "INFO"));
                try {
                    sb.append("[IMAGE ANALYSIS DATA]: ").append(brain.see(context.imageUrl)).append("\n\n");
                } catch (Exception e) {
                    LOG.error("Vision analysis failed", e);
                }
            }

            if (context.selftext != null)
                sb.append("Content:\n").append(context.selftext).append("\n\n");

            if (!context.comments.isEmpty()) {
                sb.append("Top Comments:\n");
                context.comments.stream().limit(15).forEach(c -> sb.append("- ").append(c).append("\n"));
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.error("Failed to fetch context", e);
            return "ERROR:EXCEPTION:" + e.getMessage();
        }
    }

    private String buildObservationPrompt(String context) {
        return PromptLoader.load("observation-report", Map.of(
                "CONTEXT", context,
                "LANGUAGE", userLanguage.displayName()));
    }
}
