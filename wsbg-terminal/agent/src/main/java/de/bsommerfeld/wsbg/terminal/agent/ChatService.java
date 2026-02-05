package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.TokenStream;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

@Singleton
public class ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);
    private final AgentBrain brain;
    private final ApplicationEventBus eventBus;
    private final I18nService i18n;

    @Inject
    public ChatService(AgentBrain brain, ApplicationEventBus eventBus, I18nService i18n,
            de.bsommerfeld.wsbg.terminal.reddit.RedditScraper scraper,
            de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService passiveMonitor) {
        this.brain = brain;
        this.eventBus = eventBus;
        this.i18n = i18n;
        this.scraper = scraper;
        this.passiveMonitor = passiveMonitor;
        this.eventBus.register(this);
    }

    public void sendUserMessage(String message) {
        LOG.info("User: {}", message);
        eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent("User: " + message, "INFO"));
        eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent("Agent thinking...", "INFO"));

        // Direct Chat: Uses "default" memory ID (Persistent conversation)
        // Now using Streaming Model
        TokenStream stream = brain.ask("default", message);
        if (stream == null) {
            eventBus.post(
                    new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent("Agent Error: Brain not ready",
                            "ERROR"));
            return;
        }

        eventBus.post(new AgentStreamStartEvent());
        AtomicBoolean firstToken = new AtomicBoolean(true);

        stream.onNext(token -> {
            if (firstToken.getAndSet(false)) {
                // Clear status immediately when generation starts
                eventBus.post(new AgentStatusEvent(""));
            }
            eventBus.post(new AgentTokenEvent(token));
        })
                .onComplete(response -> {
                    String fullText = response.content().text();
                    LOG.info("[INTERNAL][AI-RAW-RESPONSE] Agent (Stream Complete): {}", fullText);
                    eventBus.post(new AgentStreamEndEvent(fullText));
                })
                .onError(ex -> {
                    LOG.error("Agent Failed", ex);
                    eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                            "Agent Error: " + ex.getMessage(), "ERROR"));
                })
                .start();
    }

    public void analyzeText(String prompt) {
        // Generate a UNIQUE memory ID for this specific analysis task
        String analysisId = "analysis-" + UUID.randomUUID().toString();

        LOG.info("Analyzing (English) [ID={}]: {}", analysisId, prompt);
        eventBus.post(
                new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                        "Agent thinking (English Analysis)...",
                        "INFO"));

        // Step 1: Analytical Reasoning (English) - Streaming -> Collected
        collectStream(brain.ask(analysisId, prompt))
                .thenApply(englishResponse -> {
                    // Step 2: Translation (German) - Streaming
                    LOG.info("Translating response via Translator Model (Streaming)...");
                    eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                            "Translating to German...", "INFO"));
                    return brain.translate(englishResponse, "English", "en", "German", "de");
                })
                .thenAccept(translationStream -> {
                    if (translationStream == null) {
                        LOG.error("Translation stream was null");
                        return;
                    }

                    LOG.info("Streaming Translation to UI...");
                    eventBus.post(new AgentStreamStartEvent());
                    // Clear status for translation stream start
                    eventBus.post(new AgentStatusEvent(""));

                    StringBuilder fullTranslation = new StringBuilder();

                    translationStream.onNext(token -> {
                        fullTranslation.append(token);
                        eventBus.post(new AgentTokenEvent(token));
                    })
                            .onComplete(response -> {
                                String finalText = fullTranslation.toString();
                                LOG.info("[INTERNAL][AI-RAW-RESPONSE] Translation Stream Complete: {}", finalText);
                                eventBus.post(new AgentStreamEndEvent(finalText));
                            })
                            .onError(ex -> {
                                LOG.error("Translation Stream Failed", ex);
                                eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                                        "Translation Error: " + ex.getMessage(), "ERROR"));
                            })
                            .start();
                })
                .exceptionally(ex -> {
                    LOG.error("Agent Pipeline Failed", ex);
                    eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                            "Pipeline Error: " + ex.getMessage(), "ERROR"));
                    return null;
                });
    }

    private CompletableFuture<String> collectStream(TokenStream stream) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (stream == null) {
            future.completeExceptionally(new RuntimeException("Brain returned null stream"));
            return future;
        }
        stream.onNext(token -> {
        }) // We rely on onComplete for full text, or we could accumulate if needed.
           // LangChain4j response has full text.
                .onComplete(response -> {
                    String text = response.content().text();
                    LOG.info("[INTERNAL][AI-RAW-RESPONSE] English Analysis Complete: {}", text);
                    future.complete(text);
                })
                .onError(future::completeExceptionally)
                .start();
        return future;
    }

    public CompletableFuture<String> analyzeVision(String imageUrl) {
        LOG.info("[INTERNAL][VISION] Analyzing Vision: {}", imageUrl);
        eventBus.post(
                new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent("Vision Model scanning image...",
                        "INFO"));
        return CompletableFuture.supplyAsync(() -> brain.see(imageUrl));
    }

    private final de.bsommerfeld.wsbg.terminal.reddit.RedditScraper scraper;
    private final de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService passiveMonitor;

    @com.google.common.eventbus.Subscribe
    public void onTrigger(de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TriggerAgentAnalysisEvent event) {
        if (event.prompt != null && event.prompt.startsWith("analyze-ref:")) {
            String ref = event.prompt.substring(12).trim();
            handleRefAnalysis(ref);
        } else {
            analyzeText(event.prompt);
        }
    }

    private void handleRefAnalysis(String reference) {
        // Internal ID Lookup Strategy
        if (reference.startsWith("ID:")) {
            String invId = reference.substring(3);
            LOG.info("Looking up internal context for Investigation ID: {}", invId);

            String cachedContext = passiveMonitor.getInvestigationContext(invId);
            if (cachedContext != null) {
                eventBus.post(new AgentStatusEvent(
                        i18n.get("log.agent.analyzing_cached_evidence")));

                // Wrap in async to handle potential vision call
                CompletableFuture.supplyAsync(() -> {
                    String finalContext = cachedContext;
                    // Detect potential image availability
                    if (cachedContext.contains("IMAGE_AVAILABLE:")) {
                        // Extract URL
                        // Pattern: IMAGE_AVAILABLE: https://...
                        int start = cachedContext.indexOf("IMAGE_AVAILABLE: ") + 17;
                        int end = cachedContext.indexOf("\n", start);
                        if (end == -1)
                            end = cachedContext.length();
                        String url = cachedContext.substring(start, end).trim();

                        eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                                "Performing On-Demand Vision Analysis on internal image...", "INFO"));
                        try {
                            String vision = brain.see(url);
                            LOG.info("[INTERNAL][VISION] On-Demand Result: {}", vision);
                            finalContext += "\n[ON-DEMAND VISION ANALYSIS]: " + vision;
                        } catch (Exception e) {
                            LOG.error("On-demand vision failed", e);
                        }
                    }
                    return finalContext;
                }).thenAccept(finalContext -> {
                    String prompt = "MISSION: AGENT OBSERVATION REPORT (SENTIMENT SCAN)\n" +
                            "CONTEXT DATA (Social Sentiment Only):\n" + finalContext + "\n\n" +
                            "DIRECTIVES:\n" +
                            "1. FORMAT: Continuous text ONLY (Fließtext). NO lists, NO bullet points, NO headers.\n" +
                            "2. CONTENT: Summarize the core sentiment, key topics, and visual context. Provide the most relevant info at a glance.\n"
                            +
                            "3. EPISTEMOLOGY: PURE OBSERVATION. You are scanning Reddit. Treat EVERYTHING as user opinion/speculation. NEVER state something as a verified fact.\n"
                            +
                            "   - CORRECT: 'Users are discussing reports of...', 'Sentiment leans negative regarding...', 'The community debates...'\n"
                            +
                            "   - WRONG: 'Company X missed earnings.', 'Inflation is up.'\n" +
                            "4. FORMATTING:\n" +
                            "   - STRICTLY use backticks and 'u/' for users: `u/Username` (e.g. `u/DeepFuckingValue`, `u/Throwaway`).\n"
                            +
                            "5. VISUALS: Integrate 'VISION ANALYSIS' insights seamlessly into the text if present.";
                    analyzeText(prompt);
                });
                return;
            } else {
                eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                        "Cached evidence expired. Re-fetching live data...", "WARN"));
            }
        }

        // Fallback: Permalink Strategy (Legacy or if ID lookup fails but ref isn't an
        // ID)
        handleRefAnalysisPermalink(reference);
    }

    private void handleRefAnalysisPermalink(String permalink) {
        LOG.info("Starting On-Demand Analysis for Ref: {}", permalink);
        eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                "Fetching latest context from Reddit...", "INFO"));

        CompletableFuture.supplyAsync(() -> {
            try {
                de.bsommerfeld.wsbg.terminal.reddit.RedditScraper.ThreadAnalysisContext context = scraper
                        .fetchThreadContext(permalink);

                if (context.isEmpty()) {
                    return "ERROR:EMPTY_CONTEXT";
                }

                StringBuilder promptBuilder = new StringBuilder();
                promptBuilder.append("MISSION: SINGLE THREAD SENTIMENT SCAN\n")
                        .append("DIRECTIVES:\n")
                        .append("1. FORMAT: Continuous text ONLY (Fließtext). NO lists, NO bullet points, NO headers.\n")
                        .append("2. CONTENT: Summarize the poster's statement, the community's reaction, and any visual context.\n")
                        .append("3. EPISTEMOLOGY: PURE OBSERVATION. Treat all claims as user sentiment/rumor. Never present them as confirmed facts.\n")
                        .append("   - Use phrasing like: 'The poster claims...', 'Comments suggest...', 'Users are reacting to...'\n")
                        .append("4. FORMATTING:\n")
                        .append("   - STRICTLY use backticks for users: `u/Username`.\n")
                        .append("5. GOAL: A dense, rapid summary of this specific conversation.\n\n")
                        .append("THREAD DATA:\n");

                if (context.title != null) {
                    promptBuilder.append("Title: ").append(context.title).append("\n\n");
                }

                if (context.imageUrl != null && !context.imageUrl.isEmpty()) {
                    // ... (Vision Logic remains same)
                    eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                            "Performing On-Demand Vision Analysis...", "INFO"));
                    try {
                        String visionResult = brain.see(context.imageUrl);
                        LOG.info("[INTERNAL][VISION] Thread Image Analysis: {}", visionResult);
                        promptBuilder.append("[VISION ANALYSIS DATA]: ").append(visionResult).append("\n\n");
                    } catch (Exception e) {
                        LOG.error("Vision Analysis Failed", e);
                    }
                }

                if (context.selftext != null) {
                    promptBuilder.append("Content:\n").append(context.selftext).append("\n\n");
                }

                if (!context.comments.isEmpty()) {
                    promptBuilder.append("Top Comments:\n");
                    context.comments.stream().limit(15).forEach(c -> promptBuilder.append("- ").append(c).append("\n"));
                }

                return promptBuilder.toString();
            } catch (Exception e) {
                LOG.error("Failed to fetch context", e);
                return "ERROR:EXCEPTION:" + e.getMessage();
            }
        }).thenAccept(prompt -> {
            if (prompt.startsWith("ERROR:")) {
                String errMsg = prompt.equals("ERROR:EMPTY_CONTEXT")
                        ? "Failed to retrieve thread content (Empty or Deleted)."
                        : "Error retrieving data: " + prompt.substring(6);

                eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                        "[SYSTEM] " + errMsg, "ERROR"));
            } else {
                analyzeText(prompt);
            }
        });
    }

    /**
     * Event fired when the Agent generates a response.
     */
    public static class AgentResponseEvent {
        public final String message;

        public AgentResponseEvent(String message) {
            this.message = message;
        }
    }

    // NEW STREAMING EVENTS (Used for Translation)
    public static class AgentStreamStartEvent {
        public final String source;
        public final String cssClass;

        public AgentStreamStartEvent() {
            this(null, null);
        }

        public AgentStreamStartEvent(String source, String cssClass) {
            this.source = source;
            this.cssClass = cssClass;
        }
    }

    public static class AgentTokenEvent {
        public final String token;

        public AgentTokenEvent(String token) {
            this.token = token;
        }
    }

    public static class AgentStreamEndEvent {
        public final String fullMessage;

        public AgentStreamEndEvent(String fullMessage) {
            this.fullMessage = fullMessage;
        }
    }

    public static class AgentStatusEvent {
        public final String status;

        public AgentStatusEvent(String status) {
            this.status = status;
        }
    }
}
