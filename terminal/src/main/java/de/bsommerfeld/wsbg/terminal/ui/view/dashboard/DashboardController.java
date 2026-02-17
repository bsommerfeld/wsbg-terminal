package de.bsommerfeld.wsbg.terminal.ui.view.dashboard;

import com.google.common.eventbus.Subscribe;
import de.bsommerfeld.wsbg.terminal.agent.ChatService;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TriggerAgentAnalysisEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.ToggleGraphViewEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.ClearTerminalEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.SearchEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.SearchNextEvent;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphController;
import jakarta.inject.Inject;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ScrollBar;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import java.time.LocalTime;
import javafx.scene.layout.StackPane;

public class DashboardController {



    @FXML
    private StackPane mainContentStack;

    @FXML
    private WebView logWebView;
    private WebEngine webEngine;

    private final DashboardViewModel viewModel;
    private final ApplicationEventBus eventBus;
    private final I18nService i18n;
    private final GraphController graphController;

    // Log Aggregation State
    private boolean lastLogWasReddit = false;
    private int accRedditThreads = 0;
    private int accRedditUpvotes = 0;
    private int accRedditComments = 0;

    @Inject
    public DashboardController(DashboardViewModel viewModel,
            ApplicationEventBus eventBus,
            I18nService i18n,
            GraphController graphController) {
        this.viewModel = viewModel;
        this.eventBus = eventBus;
        this.i18n = i18n;
        this.graphController = graphController;
    }

    @FXML
    public void initialize() {
        // Enforce SplitPane Constraints
        // Enforce SplitPane Constraints - REMOVED (No split pane anymore)

        // Initialize WebView
        if (logWebView != null) {
            webEngine = logWebView.getEngine();
            logWebView.setPageFill(Color.TRANSPARENT);
            logWebView.setContextMenuEnabled(false);

            // Build @font-face CSS pointing to bundled font resources
            String fontUrlRegular = getClass().getResource("/fonts/FiraCode-Regular.ttf").toExternalForm();
            String fontUrlBold = getClass().getResource("/fonts/FiraCode-Bold.ttf").toExternalForm();
            String fontUrlRetina = getClass().getResource("/fonts/FiraCode-Retina.ttf").toExternalForm();

            String fontFaceCss = "@font-face { font-family: 'Fira Code'; src: url('" + fontUrlRegular
                    + "'); font-weight: normal; font-style: normal; }"
                    + "@font-face { font-family: 'Fira Code Retina'; src: url('" + fontUrlRetina
                    + "'); font-weight: normal; font-style: normal; }"
                    + "@font-face { font-family: 'Fira Code'; src: url('" + fontUrlBold
                    + "'); font-weight: bold; font-style: normal; }";

            webEngine.loadContent(WebViewLoader.buildTerminalHtml(fontFaceCss));

            // Handle Clicks via Alert Interception
            webEngine.setOnAlert(event -> {
                String data = event.getData();
                if (data != null && data.startsWith("CMD:ANALYZE_REF:")) {
                    String ref = data.substring(16);
                    eventBus.post(new TriggerAgentAnalysisEvent("analyze-ref:" + ref));
                }
            });
        }

        // Log Flow Binding
        viewModel.getLogs().addListener((ListChangeListener<LogMessage>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (LogMessage log : change.getAddedSubList()) {
                        processLogEntry(log);
                    }
                }
            }
        });

        eventBus.register(this);



        // Graph View — always initialized, toggleable via ToggleGraphViewEvent
        if (mainContentStack != null) {
            mainContentStack.getChildren().add(graphController.getView());
            graphController.getView().setVisible(false);
            graphController.start();
        }
        if (logWebView != null) {
            logWebView.setVisible(true);
        }
    }

    @Subscribe
    public void onToggleGraphView(ToggleGraphViewEvent event) {
        Platform.runLater(() -> {
            boolean isGraphVisible = graphController.getView().isVisible();
            if (isGraphVisible) {
                graphController.stop();
                graphController.getView().setVisible(false);
                if (logWebView != null) logWebView.setVisible(true);
            } else {
                if (logWebView != null) logWebView.setVisible(false);
                graphController.getView().setVisible(true);
                graphController.start();
            }
        });
    }

    private void setupGhostScrollBar(ScrollBar bar) {
        // Initial State: Invisible
        bar.setOpacity(0.0);

        // Animation Configuration
        PauseTransition delay = new PauseTransition(Duration.millis(1000));
        FadeTransition fadeOut = new FadeTransition(Duration.millis(1000), bar);
        fadeOut.setFromValue(0.8);
        fadeOut.setToValue(0.0);

        // Chain: Delay -> Fade Out
        delay.setOnFinished(e -> fadeOut.play());

        // Action: Show ScrollBar
        Runnable showScrollBar = () -> {
            fadeOut.stop();
            delay.stop();
            bar.setOpacity(0.8);
            delay.playFromStart();
        };

        // Triggers
        bar.valueProperty().addListener((obs, oldVal, newVal) -> showScrollBar.run());
    }

    @FXML
    public void onTriggerAnalysis() {
        viewModel.appendToConsole(i18n.get("system.diagnostic"));
        eventBus.post(new TriggerAgentAnalysisEvent(
                i18n.get("analysis.prompt")));
    }

    @Subscribe
    public void onClearTerminalEvent(ClearTerminalEvent event) {
        Platform.runLater(() -> {
            if (webEngine != null) {
                webEngine.executeScript("clearLogs()");
            }
        });
    }

    // Streaming Logic Flags
    private boolean ignoreStreamedLog = false;

    @Subscribe
    public void onAgentStreamStart(ChatService.AgentStreamStartEvent event) {
        Platform.runLater(() -> {
            // Prepare Header
            LocalTime now = LocalTime.now();
            String timestamp = String.format("[%02d:%02d:%02d]", now.getHour(), now.getMinute(), now.getSecond());

            // Default Source
            String source = i18n.get("log.source.agent");
            String sourceClass = "source-AI";

            // Override from Event
            if (event.source() != null)
                source = event.source();
            if (event.cssClass() != null)
                sourceClass = event.cssClass();

            String headerHtml = "<span class=\"timestamp\">" + timestamp + "</span> " +
                    "<span class=\"log-source " + sourceClass + "\">[" + source + "]:</span> ";

            // webEngine.executeScript("setStatus('')"); // REMOVED: Keep "Translating..."
            // status until stream ends
            boolean isPassive = i18n.get("log.source.passive_agent").equals(source);
            String extraStreamClass = isPassive ? "" : "log-type-source-AI";
            webEngine.executeScript("startStreamLog('" + escapeJs(headerHtml) + "', '" + extraStreamClass + "')");

            // Only disable UI for interactive Main Agent, not background Passive Agent
            if (source.equals("WSBG-TERMINAL")) {
                // Keep UI interactive
            }

        });
    }

    @Subscribe
    public void onAgentToken(ChatService.AgentTokenEvent event) {
        Platform.runLater(() -> {
            // Check null or empty
            if (event.token() != null) {
                // Must double escape: First for HTML content, then for JS string
                webEngine.executeScript("appendStreamToken('" + escapeJs(escapeHtml(event.token())) + "')");
            }
        });
    }

    @Subscribe
    public void onAgentStatus(ChatService.AgentStatusEvent event) {
        Platform.runLater(() -> {
            if (event.status() == null || event.status().isEmpty()) {
                webEngine.executeScript("setStatus('')");
            } else {
                webEngine.executeScript("setStatus('" + escapeJs(event.status()) + "')");
            }
        });

    }

    @Subscribe
    public void onAgentStreamEnd(ChatService.AgentStreamEndEvent event) {
        Platform.runLater(() -> {
            boolean isPassive = event.fullMessage().contains("||PASSIVE||");

            webEngine.executeScript("removeCurrentStream()");

            // Only interact with Status/UI if this was a Main Agent task (not Passive)
            if (!isPassive) {
                webEngine.executeScript("setStatus('')"); // FORCE RESET STATUS
                // Re-enable UI

            }

            // Add to history, and allow display (replacing the removed stream)
            ignoreStreamedLog = false;
            // Use LogType.DEFAULT to ensure validity and persistence
            viewModel.appendToConsole("||AI_FINAL||" + event.fullMessage(), LogType.DEFAULT);
        });
    }




    @Subscribe
    public void onLogEvent(LogEvent event) {
        Platform.runLater(() -> {
            LogType type = LogType.DEFAULT;
            try {
                type = LogType.valueOf(event.type());
            } catch (IllegalArgumentException e) {
                // Keep default
            }
            processLogEntry(new LogMessage(event.message(), type));
        });
    }

    // --- Smart Terminal Logic (WebView) ---

    private void processLogEntry(LogMessage log) {
        Platform.runLater(() -> {
            if (webEngine == null)
                return;

            // Handle Streamed Replay Prevention
            if (log.type() == LogType.STREAMED) {
                if (ignoreStreamedLog) {
                    ignoreStreamedLog = false;
                    return; // Skip visual append
                }
            }

            String originalMsg = log.message().trim();

            // Banner Logic
            if (originalMsg.startsWith("||BANNER||")) {
                String bannerContent = originalMsg.substring(10);

                // Color Parsing Logic for "Germany G" and "WSB Blue"
                // We assume the input string contains markers: {{B}} (Blue/Default), {{K}}
                // (Black/Grey), {{R}} (Red), {{Y}} (Gold)
                // First, escape HTML to be safe
                String safeContent = escapeHtml(bannerContent);

                // Now replace markers with spans
                // We use a specific class for the base font
                // {{B}} Changed from Blue to Default Grey (#e0e0e0) per user request ("Farbe
                // der restlichen Software")
                String styledContent = safeContent
                        .replace("{{B}}",
                                "<span style='color: #e0e0e0; text-shadow: 0 0 10px rgba(224, 224, 224, 0.1);'>")
                        .replace("{{K}}", "<span style='color: #444444; text-shadow: none;'>") // Dark Grey for "Black"
                                                                                               // visibility
                        .replace("{{R}}",
                                "<span style='color: #ff3333; text-shadow: 0 0 10px rgba(255, 51, 51, 0.4);'>")
                        .replace("{{Y}}",
                                "<span style='color: #ffcc00; text-shadow: 0 0 10px rgba(255, 204, 0, 0.4);'>")
                        .replace("{{BLUE}}",
                                "<span style='color: #3399ff; text-shadow: 0 0 10px rgba(51, 153, 255, 0.6);'>")
                        .replace("{{X}}", "</span>");

                // Classic Terminal Integration
                StringBuilder html = new StringBuilder();
                // Container: Align left (flex-start), reduced padding to fit flow
                html.append(
                        "<div style='display: flex; flex-direction: column; align-items: flex-start; padding: 10px 0 20px 0;'>");

                // 2. The Banner Output (Split by line and animate)
                String[] lines = styledContent.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    if (line.trim().isEmpty())
                        continue;

                    // Animation: slideInLeft
                    String delay = (i * 80) + "ms";
                    html.append(
                            "<div style='white-space: pre; font-family: \"Fira Code\", \"Consolas\", monospace; font-weight: bold; font-size: 20px; line-height: 1.0; text-align: left; opacity: 0; animation: slideInLeft 0.5s cubic-bezier(0.2, 0.8, 0.2, 1) forwards; animation-delay: "
                                    + delay + ";'>");
                    html.append(line);
                    html.append("</div>");
                }

                html.append("</div>");

                webEngine.executeScript("appendLog('" + escapeJs(html.toString()) + "', 'log-banner')");
                return;
            }

            int finalTagIndex = originalMsg.indexOf("||AI_FINAL||");
            boolean isAiFinal = finalTagIndex >= 0;
            String displayMsg = isAiFinal ? originalMsg.substring(finalTagIndex + 12) : originalMsg;

            // Check for passive tag
            boolean isPassive = false;
            String contextData = ""; // Used for Ref
            if (isAiFinal) {
                int passiveTagIndex = displayMsg.indexOf("||PASSIVE||");
                if (passiveTagIndex >= 0) {
                    isPassive = true;
                    displayMsg = displayMsg.substring(passiveTagIndex + 11); // Remove ||PASSIVE||
                }
            }

            // Context/Ref Split Logic (Invisible Payload)
            if (isPassive && displayMsg.contains("||REF||")) {
                String[] parts = displayMsg.split("\\|\\|REF\\|\\|");
                displayMsg = parts[0];
                if (parts.length > 1) {
                    contextData = parts[1]; // This is the Permalink
                }
            }

            // Suppress specialized messages - Feed updates
            if (originalMsg.contains("News Feed Updated")) {
                return;
            }

            // Status Logic
            // Only treat as status if it's NOT a final AI report
            boolean isStatus = !isAiFinal && (originalMsg.contains("Agent thinking") ||
                    originalMsg.contains("Image Analysis Result") ||
                    originalMsg.contains("TranslateGemma") ||
                    originalMsg.contains("SEARCHING WEB") ||
                    originalMsg.contains("GLM-OCR") ||
                    originalMsg.contains("Vision") ||
                    originalMsg.contains("semantic highlighting") ||
                    originalMsg.contains("Translating to German") ||
                    (originalMsg.contains("Translate") && !originalMsg.contains("User")));

            if (isStatus) {
                String cleanStatus = displayMsg.replaceAll("[*#`_]", "").trim();
                // Map status text if bundle keys exist, otherwise use raw
                if (cleanStatus.contains("Image Analysis") || cleanStatus.contains("Vision")
                        || cleanStatus.contains("GLM-OCR"))
                    cleanStatus = i18n.get("status.vision_analysis");
                else if (cleanStatus.contains("Agent thinking"))
                    cleanStatus = i18n.get("status.agent_thinking");
                else if (cleanStatus.contains("Translate") || cleanStatus.contains("TranslateGemma")
                        || cleanStatus.contains("Translating to German"))
                    cleanStatus = i18n.get("status.translating");
                else if (cleanStatus.contains("semantic highlighting"))
                    cleanStatus = i18n.get("status.highlighting");
                else if (cleanStatus.contains("SEARCHING WEB"))
                    cleanStatus = i18n.get("status.searching");

                // Update status in WebView
                webEngine.executeScript("setStatus('" + escapeJs(cleanStatus) + "')");
                return;
            }

            // Only clear status if this is a FINAL response from the Active Agent.
            // This prevents background logs (Reddit, Cleanup) or Passive Agent reports
            // from clearing the visual status indicator of the running Active Agent.
            if (isAiFinal && !isPassive) {
                webEngine.executeScript("setStatus('')");
            }

            // Format Log
            LocalTime now = LocalTime.now();
            String timestamp = String.format("[%02d:%02d:%02d]", now.getHour(), now.getMinute(), now.getSecond());

            String source = i18n.get("log.source.system");
            String sourceClass = "source-SYSTEM";

            if (originalMsg.startsWith("User:")) {
                lastLogWasReddit = false;
                source = i18n.get("log.source.user");
                sourceClass = "source-USER";
            } else if (isAiFinal) {
                lastLogWasReddit = false;
                if (isPassive) {
                    source = i18n.get("log.source.passive_agent");

                    // Priority Parse Logic (Robust Regex)
                    String upperMsg = displayMsg.toUpperCase();

                    // Match any tag containing priority keywords
                    if (upperMsg.matches("(?s).*?\\[.*?(LOW|NIEDRIG).*?\\].*")) {
                        sourceClass = "source-passive-low";
                        // Remove the tag aggressively
                        displayMsg = displayMsg.replaceFirst("(?i)\\[.*?(LOW|NIEDRIG).*?\\]", "").trim();
                    } else if (upperMsg.matches("(?s).*?\\[.*?(MED|NEUTRAL|MITTEL).*?\\].*")) {
                        sourceClass = "source-passive-med";
                        displayMsg = displayMsg.replaceFirst("(?i)\\[.*?(MED|NEUTRAL|MITTEL).*?\\]", "").trim();
                    } else if (upperMsg.matches("(?s).*?\\[.*?(HIGH|HOCH).*?\\].*")) {
                        sourceClass = "source-passive-high";
                        displayMsg = displayMsg.replaceFirst("(?i)\\[.*?(HIGH|HOCH).*?\\]", "").trim();
                    } else {
                        // Fallback: If strict parsing fails but we are Passive,
                        // check if it starts with bracket to cleanup, or assume High
                        if (displayMsg.trim().startsWith("[")) {
                            displayMsg = displayMsg.replaceFirst("\\[.*?\\]", "").trim();
                        }
                        sourceClass = "source-passive-high";
                    }

                    // Final cleanup if colon remains
                    if (displayMsg.startsWith(":"))
                        displayMsg = displayMsg.substring(1).trim();

                } else {
                    // Active Agent (AI Final but not Passive)
                    source = i18n.get("log.source.agent");
                    sourceClass = "source-AI";
                }

            } else {
                if (log.type() == LogType.ERROR) {
                    lastLogWasReddit = false;
                    source = i18n.get("log.source.error");
                    sourceClass = "source-ERROR";
                } else if (log.type() == LogType.CLEANUP) {
                    lastLogWasReddit = false;
                    source = i18n.get("log.source.cleanup");
                    sourceClass = "source-CLEANUP";
                    try {
                        displayMsg = i18n.get("log.cleanup.message", displayMsg);
                    } catch (Exception e) {
                    }
                } else if (log.type() == LogType.REDDIT) {
                    source = i18n.get("log.source.reddit");
                    sourceClass = "source-REDDIT";
                    try {
                        String[] args = displayMsg.split(",");
                        if (args.length >= 3) {
                            int t = Integer.parseInt(args[0].trim());
                            int u = Integer.parseInt(args[1].trim());
                            int c = Integer.parseInt(args[2].trim());

                            if (lastLogWasReddit) {
                                accRedditThreads += t;
                                accRedditUpvotes += u;
                                accRedditComments += c;

                                // Format aggregated message
                                displayMsg = i18n.get("log.reddit.message",
                                        "((" + accRedditThreads + "))",
                                        "((" + accRedditUpvotes + "))",
                                        "((" + accRedditComments + "))");

                                // Direct Update: Modify DOM and Return (Skip standard append)
                                String formattedBody = formatMessageToHtml(displayMsg);
                                webEngine.executeScript("updateLastRedditLog('" + escapeJs(formattedBody) + "')");
                                return;
                            } else {
                                // First of a new block
                                lastLogWasReddit = true;
                                accRedditThreads = t;
                                accRedditUpvotes = u;
                                accRedditComments = c;

                                displayMsg = i18n.get("log.reddit.message",
                                        "((" + args[0].trim() + "))",
                                        "((" + args[1].trim() + "))",
                                        "((" + args[2].trim() + "))");
                            }
                        } else {
                            // Fallback for malformed
                            lastLogWasReddit = false;
                        }
                    } catch (Exception e) {
                        lastLogWasReddit = false;
                    }
                } else {
                    // Default / Other types (fallback)
                    lastLogWasReddit = false;
                }
            }

            // Clean message for display
            String cleanBody = displayMsg.trim();
            String formattedBody = formatMessageToHtml(cleanBody);

            // INTERACTIVITY: Wrap Passive Reports for Click-to-Analyze (Ref Based)
            if (isPassive) {
                // contextData is the REF (Permalink)
                String refPayload = contextData.isEmpty() ? "" : contextData;
                formattedBody = "<span class=\"interactive-report\" onclick=\"analyzeRef('" + escapeJs(refPayload)
                        + "')\">"
                        + formattedBody + "</span>";
            }

            // Construct HTML snippet
            String html = "<span class=\"timestamp\">" + timestamp + "</span> " +
                    "<span class=\"log-source " + sourceClass + "\">[" + source + "]:</span> " +
                    "<span class=\"content\">" + formattedBody + "</span>";

            boolean isEilmeldung = sourceClass.contains("passive");
            String extraClass = "log-type-" + sourceClass
                    + (sourceClass.contains("CLEANUP") || sourceClass.contains("REDDIT")
                            || sourceClass.contains("SYSTEM")
                                    ? " log-dimmed"
                                    : "")
                    + (isEilmeldung ? " eilmeldung" : "");

            webEngine.executeScript("appendLog('" + escapeJs(html) + "', '" + extraClass + "')");
        });
    }

    private String formatMessageToHtml(String text) {
        if (text == null)
            return "";
        StringBuilder sb = new StringBuilder();
        int len = text.length();
        boolean inSubject = false;
        boolean inBold = false; // Optional support for existing markdown
        boolean inAutoSubject = false; // Auto u/Username detection

        // URL Detection Helper
        // We will scan ahead to skip URLs entirely from formatting, treating them as
        // plain strings (or links)

        for (int i = 0; i < len; i++) {
            // Check for URL start (http://, https://)
            if (!inSubject && !inAutoSubject && !inBold && i + 4 < len) {
                String lookahead = text.substring(i, Math.min(i + 8, len));
                if (lookahead.startsWith("http:") || lookahead.startsWith("https:")) {
                    // Fast-forward through URL until whitespace
                    int urlEnd = i;
                    while (urlEnd < len && !Character.isWhitespace(text.charAt(urlEnd))) {
                        urlEnd++;
                    }
                    String url = text.substring(i, urlEnd);
                    sb.append(escapeHtml(url)); // Append pure URL, no internal formatting
                    i = urlEnd - 1; // Advance loop
                    continue;
                }
            }

            // Handle Auto-Subject (u/Username) logic continuously
            if (inAutoSubject) {
                char c = text.charAt(i);
                // Valid stats: alphanumeric, -, _
                if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                    sb.append(c);
                    continue;
                } else {
                    // End of username
                    sb.append("</span>");
                    inAutoSubject = false;
                    // Fall through to process this punctuation/space normally
                }
            }

            // Check for Bearish [[
            if (i + 1 < len && text.charAt(i) == '[' && text.charAt(i + 1) == '[') {
                sb.append("<span class='bearish'>");
                i++; // Skip second character
            }
            // Check for Bearish ]]
            else if (i + 1 < len && text.charAt(i) == ']' && text.charAt(i + 1) == ']') {
                sb.append("</span>");
                i++;
            }
            // Check for Bullish <<
            else if (i + 1 < len && text.charAt(i) == '<' && text.charAt(i + 1) == '<') {
                sb.append("<span class='bullish'>");
                i++;
            }
            // Check for Bullish >>
            else if (i + 1 < len && text.charAt(i) == '>' && text.charAt(i + 1) == '>') {
                sb.append("</span>");
                i++;
            }
            // Check for Animated Value ((
            else if (i + 1 < len && text.charAt(i) == '(' && text.charAt(i + 1) == '(') {
                sb.append("<span class='animated-val'>");
                i++;
            }
            // Check for Animated Value ))
            else if (i + 1 < len && text.charAt(i) == ')' && text.charAt(i + 1) == ')') {
                sb.append("</span>");
                i++;
            }
            // Check for Subject `
            else if (text.charAt(i) == '`') {
                if (inSubject) {
                    sb.append("</span>");
                    inSubject = false;
                } else {
                    sb.append("<span class='subject'>");
                    inSubject = true;
                }
            }
            // Check for Auto-Subject Start (u/...)
            else if (!inSubject && !inAutoSubject && i + 1 < len && text.charAt(i) == 'u'
                    && text.charAt(i + 1) == '/') {
                sb.append("<span class='subject'>u/");
                inAutoSubject = true;
                i++; // Skip '/'
            }
            // Check for Bold ** (Legacy Support)
            else if (i + 1 < len && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                if (inBold) {
                    sb.append("</b>");
                    inBold = false;
                } else {
                    sb.append("<b>");
                    inBold = true;
                }
                i++;
            } else {
                // Escape HTML chars
                char c = text.charAt(i);
                switch (c) {
                    case '&':
                        sb.append("&amp;");
                        break;
                    case '<':
                        sb.append("&lt;");
                        break;
                    case '>':
                        sb.append("&gt;");
                        break;
                    case '"':
                        sb.append("&quot;");
                        break;
                    case '\'':
                        sb.append("&#39;");
                        break;
                    default:
                        sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private String escapeJs(String text) {
        if (text == null)
            return "";
        return text.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n").replace("\r",
                "");
    }

    private String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Subscribe
    public void onSearchEvent(SearchEvent event) {
        Platform.runLater(() -> {
            String query = event.query() == null ? "" : event.query().toLowerCase().trim();
            if (webEngine != null) {
                webEngine.executeScript("highlightSearchTerms('" + escapeJs(query) + "')");
            }
        });
    }

    @Subscribe
    public void onSearchNextEvent(SearchNextEvent event) {
        Platform.runLater(() -> {
            if (webEngine != null) {
                webEngine.executeScript("findNextSearchTerm()");
            }
        });
    }

}
