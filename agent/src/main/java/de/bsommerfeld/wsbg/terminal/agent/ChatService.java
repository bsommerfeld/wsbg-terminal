package de.bsommerfeld.wsbg.terminal.agent;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TriggerAgentAnalysisEvent;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper.ThreadAnalysisContext;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates user-facing chat, analysis pipelines, and vision requests.
 * Bridges {@link AgentBrain} capabilities with the UI event system.
 *
 * <p>
 * Three interaction modes:
 * <ul>
 * <li><strong>Chat</strong> — {@link #sendUserMessage} streams AI responses
 * directly to the UI</li>
 * <li><strong>Analysis</strong> — {@link #analyzeText} runs an English
 * reasoning pass, then
 * translates to the user's configured language before streaming</li>
 * <li><strong>Vision</strong> — {@link #analyzeVision} delegates image
 * interpretation to
 * the vision model</li>
 * </ul>
 */
@Singleton
public class ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

    private final AgentBrain brain;
    private final ApplicationEventBus eventBus;
    private final I18nService i18n;
    private final GlobalConfig config;
    private final RedditScraper scraper;
    private final PassiveMonitorService passiveMonitor;

    @Inject
    public ChatService(AgentBrain brain, ApplicationEventBus eventBus, I18nService i18n,
            GlobalConfig config, RedditScraper scraper, PassiveMonitorService passiveMonitor) {
        this.brain = brain;
        this.eventBus = eventBus;
        this.i18n = i18n;
        this.config = config;
        this.scraper = scraper;
        this.passiveMonitor = passiveMonitor;
        this.eventBus.register(this);
    }

    // -- Public API --

    /**
     * Handles a direct user chat message. Streams the AI response to the UI
     * in real time via {@link AgentTokenEvent} events.
     *
     * @param message the user's raw input text
     */
    public void sendUserMessage(String message) {
        LOG.info("User: {}", message);
        eventBus.post(new LogEvent("User: " + message, "INFO"));
        eventBus.post(new LogEvent("Agent thinking...", "INFO"));

        TokenStream stream = brain.ask("default", message);
        if (stream == null) {
            eventBus.post(new LogEvent("Agent Error: Brain not ready", "ERROR"));
            return;
        }

        streamToUi(stream);
    }

    /**
     * Runs a two-phase analysis: first collects the AI's English reasoning,
     * then translates to the user's language and streams the result.
     * The two-phase approach forces the AI to reason in English (higher quality
     * for most models) while presenting the result in the user's language.
     *
     * @param prompt the analysis prompt (typically enriched with thread/market
     *               context)
     */
    public void analyzeText(String prompt) {
        String analysisId = "analysis-" + UUID.randomUUID();
        LOG.info("Analyzing [ID={}]: {}", analysisId, prompt);
        eventBus.post(new LogEvent("Agent thinking (English Analysis)...", "INFO"));

        String targetLang = config.getUser().getLanguage();
        TokenStream reasoningStream = brain.ask(analysisId, prompt);
        if (reasoningStream == null) {
            eventBus.post(new LogEvent("Agent Error: Brain not ready", "ERROR"));
            return;
        }

        // English: stream reasoning tokens directly — no collection + translation
        // overhead
        if ("en".equalsIgnoreCase(targetLang)) {
            streamToUi(reasoningStream);
            return;
        }

        // Non-English: collect English reasoning, then translate and stream
        collectStream(reasoningStream)
                .thenAccept(english -> {
                    LOG.info("Translating response...");
                    Locale targetLocale = Locale.forLanguageTag(targetLang);
                    String targetName = targetLocale.getDisplayLanguage(Locale.ENGLISH);
                    TokenStream translationStream = brain.translate(
                            english, "English", "en", targetName, targetLang);
                    if (translationStream == null)
                        return;
                    streamToUi(translationStream);
                });
    }

    /**
     * Returns the raw AI response without UI streaming or translation.
     * Used by internal subsystems that need the unprocessed English output.
     *
     * @param prompt the prompt to send
     * @return future completing with the full response text
     */
    public CompletableFuture<String> askRaw(String prompt) {
        String id = "raw-" + UUID.randomUUID();
        LOG.info("Raw query [ID={}]: {}", id, prompt);
        return collectStream(brain.ask(id, prompt));
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
     * Streams AI tokens to the UI in real time. Posts start/token/end events
     * that the UI layer consumes for live rendering.
     */
    private void streamToUi(TokenStream stream) {
        eventBus.post(new AgentStreamStartEvent());
        AtomicBoolean firstToken = new AtomicBoolean(true);

        stream.onNext(token -> {
            if (firstToken.getAndSet(false)) {
                eventBus.post(new AgentStatusEvent(""));
            }
            eventBus.post(new AgentTokenEvent(token));
        })
                .onComplete(response -> {
                    String fullText = response.content().text();
                    LOG.info("[AI-RESPONSE] Stream complete: {}", fullText);
                    eventBus.post(new AgentStreamEndEvent(fullText));
                })
                .onError(ex -> {
                    LOG.error("Agent stream failed", ex);
                    eventBus.post(new LogEvent("Agent Error: " + ex.getMessage(), "ERROR"));
                })
                .start();
    }

    /**
     * Collects an entire token stream into a single string. Discards individual
     * tokens during collection — use {@link #streamToUi} when live output is
     * needed.
     */
    private CompletableFuture<String> collectStream(TokenStream stream) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (stream == null) {
            future.completeExceptionally(new RuntimeException("Brain returned null stream"));
            return future;
        }
        stream.onNext(token -> {
        })
                .onComplete(response -> future.complete(response.content().text()))
                .onError(future::completeExceptionally)
                .start();
        return future;
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
            return context + "\n[ON-DEMAND VISION ANALYSIS]: " + brain.see(url);
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
            sb.append(PromptLoader.load("thread-analysis")).append("\n\nTHREAD DATA:\n");

            if (context.title != null)
                sb.append("Title: ").append(context.title).append("\n\n");

            if (context.imageUrl != null && !context.imageUrl.isEmpty()) {
                eventBus.post(new LogEvent("Performing on-demand vision analysis...", "INFO"));
                try {
                    sb.append("[VISION ANALYSIS DATA]: ").append(brain.see(context.imageUrl)).append("\n\n");
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
        return PromptLoader.load("observation-report", Map.of("CONTEXT", context));
    }

    // -- Events --

    /**
     * Posted when an AI response stream begins. Optional source label and CSS class
     * for UI styling.
     */
    public record AgentStreamStartEvent(String source, String cssClass) {
        public AgentStreamStartEvent() {
            this(null, null);
        }
    }

    /** Posted for each token received from the AI stream. */
    public record AgentTokenEvent(String token) {
    }

    /**
     * Posted when an AI response stream completes with the full concatenated
     * message.
     */
    public record AgentStreamEndEvent(String fullMessage) {
    }

    /** Posted to update the status indicator in the UI (e.g., "Translating..."). */
    public record AgentStatusEvent(String status) {
    }
}
