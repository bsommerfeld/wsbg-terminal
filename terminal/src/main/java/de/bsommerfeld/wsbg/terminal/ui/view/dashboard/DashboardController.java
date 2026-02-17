package de.bsommerfeld.wsbg.terminal.ui.view.dashboard;

import com.google.common.eventbus.Subscribe;
import de.bsommerfeld.wsbg.terminal.agent.ChatService;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TriggerAgentAnalysisEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.ToggleGraphViewEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.TerminalBlinkEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.ClearTerminalEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.SearchEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.SearchNextEvent;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphController;
import jakarta.inject.Inject;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.time.LocalTime;

/**
 * Bridges the WebView terminal and the graph view.
 * Receives log events and agent stream events, formats them as HTML,
 * and injects them into the WebView via JavaScript calls.
 */
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

    // Reddit log aggregation — consecutive REDDIT entries are merged into a
    // single updating DOM row instead of appending separate lines.
    private boolean lastLogWasReddit = false;
    private int accRedditThreads = 0;
    private int accRedditUpvotes = 0;
    private int accRedditComments = 0;

    /**
     * @param viewModel       log buffer and chat dispatch
     * @param eventBus        application-wide event bus (Guava)
     * @param i18n            localized string resolver
     * @param graphController graph view lifecycle and sidebar
     */
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

    /**
     * FXML lifecycle callback.
     * Configures the WebView (fonts, HTML shell, alert interception),
     * binds the log list listener, registers on the EventBus, and
     * attaches the graph view into the main content stack.
     */
    @FXML
    public void initialize() {

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

        if (mainContentStack != null) {
            mainContentStack.getChildren().add(graphController.getView());
            graphController.getView().setVisible(false);
            graphController.start();
        }
        if (logWebView != null) {
            logWebView.setVisible(true);
        }
    }

    /** Toggles between the terminal WebView and the graph visualization. */
    @Subscribe
    public void onToggleGraphView(ToggleGraphViewEvent event) {
        Platform.runLater(() -> {
            boolean isGraphVisible = graphController.getView().isVisible();
            if (isGraphVisible) {
                graphController.stop();
                graphController.getView().setVisible(false);
                if (logWebView != null)
                    logWebView.setVisible(true);

                // User returned to terminal — stop the attention blink
                eventBus.post(new TerminalBlinkEvent(false));
            } else {
                if (logWebView != null)
                    logWebView.setVisible(false);
                graphController.getView().setVisible(true);
                graphController.start();
            }
        });
    }

    /** Fires an agent analysis request from the FXML action button. */
    @FXML
    public void onTriggerAnalysis() {
        viewModel.appendToConsole(i18n.get("system.diagnostic"));
        eventBus.post(new TriggerAgentAnalysisEvent(
                i18n.get("analysis.prompt")));
    }

    /** Wipes all log entries from the WebView DOM. */
    @Subscribe
    public void onClearTerminalEvent(ClearTerminalEvent event) {
        Platform.runLater(() -> {
            if (webEngine != null) {
                webEngine.executeScript("clearLogs()");
            }
        });
    }

    // When true the next STREAMED log is suppressed — prevents duplicating
    // a stream that was already displayed as a live token feed.
    private boolean ignoreStreamedLog = false;

    /**
     * Opens a streaming log row in the WebView. The header (timestamp +
     * source label) is injected immediately; subsequent tokens arrive via
     * {@link #onAgentToken}.
     */
    @Subscribe
    public void onAgentStreamStart(ChatService.AgentStreamStartEvent event) {
        Platform.runLater(() -> {
            // Prepare Header
            LocalTime now = LocalTime.now();
            String timestamp = String.format("[%02d:%02d:%02d]", now.getHour(), now.getMinute(), now.getSecond());

            // Default Source
            String source = i18n.get("log.source.agent");
            String sourceClass = "source-AI";

            if (event.source() != null)
                source = event.source();
            if (event.cssClass() != null)
                sourceClass = event.cssClass();

            String headerHtml = "<span class=\"timestamp\">" + timestamp + "</span> " +
                    "<span class=\"log-source " + sourceClass + "\">[" + source + "]:</span> ";

            boolean isPassive = i18n.get("log.source.passive_agent").equals(source);
            String extraStreamClass = isPassive ? "" : "log-type-source-AI";
            webEngine.executeScript("startStreamLog('" + escapeJs(headerHtml) + "', '" + extraStreamClass + "')");

        });
    }

    /** Appends a single token to the currently open stream row. */
    @Subscribe
    public void onAgentToken(ChatService.AgentTokenEvent event) {
        Platform.runLater(() -> {
            if (event.token() != null) {
                // Double escape: HTML content first, then JS string literal
                webEngine.executeScript("appendStreamToken('" + escapeJs(escapeHtml(event.token())) + "')");
            }
        });
    }

    /** Updates or clears the transient status spinner in the WebView footer. */
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

    /**
     * Closes the open stream row. The complete message is persisted into
     * the log buffer via the ViewModel so the entry survives a clear-and-redraw.
     */
    @Subscribe
    public void onAgentStreamEnd(ChatService.AgentStreamEndEvent event) {
        Platform.runLater(() -> {
            webEngine.executeScript("removeCurrentStream()");

            // Discard empty events (e.g. translation timeout)
            if (event.fullMessage() == null || event.fullMessage().isEmpty()) {
                ignoreStreamedLog = false;
                return;
            }

            boolean isPassive = event.fullMessage().contains("||PASSIVE||");

            if (!isPassive) {
                webEngine.executeScript("setStatus('')");
            }

            ignoreStreamedLog = false;
            viewModel.appendToConsole("||AI_FINAL||" + event.fullMessage(), LogType.DEFAULT);
        });
    }

    /** Routes a backend log event into the visual pipeline. */
    @Subscribe
    public void onLogEvent(LogEvent event) {
        Platform.runLater(() -> {
            LogType type = LogType.DEFAULT;
            try {
                type = LogType.valueOf(event.type());
            } catch (IllegalArgumentException ignored) {
            }
            processLogEntry(new LogMessage(event.message(), type));
        });
    }

    /**
     * Central log processing pipeline. Classifies each entry by its embedded
     * tags ({@code ||BANNER||}, {@code ||AI_FINAL||}, {@code ||PASSIVE||},
     * {@code ||REF||}), maps status messages to the spinner, aggregates
     * consecutive Reddit entries, and renders the final HTML row.
     */
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

            if (originalMsg.startsWith("||BANNER||")) {
                String bannerContent = originalMsg.substring(10);
                String safeContent = escapeHtml(bannerContent);

                // Banner color markers: {{B}} base grey, {{K}} dark, {{R}} red, {{Y}} gold,
                // {{BLUE}} blue
                String styledContent = safeContent
                        .replace("{{B}}",
                                "<span style='color: #e0e0e0; text-shadow: 0 0 10px rgba(224, 224, 224, 0.1);'>")
                        .replace("{{K}}", "<span style='color: #444444; text-shadow: none;'>")

                        .replace("{{R}}",
                                "<span style='color: #ff3333; text-shadow: 0 0 10px rgba(255, 51, 51, 0.4);'>")
                        .replace("{{Y}}",
                                "<span style='color: #ffcc00; text-shadow: 0 0 10px rgba(255, 204, 0, 0.4);'>")
                        .replace("{{BLUE}}",
                                "<span style='color: #3399ff; text-shadow: 0 0 10px rgba(51, 153, 255, 0.6);'>")
                        .replace("{{X}}", "</span>");

                StringBuilder html = new StringBuilder();
                html.append(
                        "<div style='display: flex; flex-direction: column; align-items: flex-start; padding: 10px 0 20px 0;'>");

                String[] lines = styledContent.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    if (line.trim().isEmpty())
                        continue;

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

            // Status messages are transient spinners — only if NOT a final AI report.
            // Only the three patterns the agent module actually emits are matched;
            // stale matchers (Image Analysis Result, TranslateGemma, GLM-OCR,
            // SEARCHING WEB, semantic highlighting, Translating to German) were
            // removed because nothing in the codebase produces them.
            boolean isStatus = !isAiFinal && (originalMsg.contains("Agent thinking") ||
                    originalMsg.contains("Vision") ||
                    (originalMsg.contains("Translate") && !originalMsg.contains("User")));

            if (isStatus) {
                String cleanStatus = displayMsg.replaceAll("[*#`_]", "").trim();
                if (cleanStatus.contains("Vision"))
                    cleanStatus = i18n.get("status.vision_analysis");
                else if (cleanStatus.contains("Agent thinking"))
                    cleanStatus = i18n.get("status.agent_thinking");
                else if (cleanStatus.contains("Translate"))
                    cleanStatus = i18n.get("status.translating");

                webEngine.executeScript("setStatus('" + escapeJs(cleanStatus) + "')");
                return;
            }

            // Only clear status for Active Agent finals — background logs
            // must not disturb a running spinner.
            if (isAiFinal && !isPassive) {
                webEngine.executeScript("setStatus('')");
            }

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

                    String upperMsg = displayMsg.toUpperCase();
                    if (upperMsg.matches("(?s).*?\\[.*?(LOW|NIEDRIG).*?\\].*")) {
                        sourceClass = "source-passive-low";
                        displayMsg = displayMsg.replaceFirst("(?i)\\[.*?(LOW|NIEDRIG).*?\\]", "").trim();
                    } else if (upperMsg.matches("(?s).*?\\[.*?(MED|NEUTRAL|MITTEL).*?\\].*")) {
                        sourceClass = "source-passive-med";
                        displayMsg = displayMsg.replaceFirst("(?i)\\[.*?(MED|NEUTRAL|MITTEL).*?\\]", "").trim();
                    } else if (upperMsg.matches("(?s).*?\\[.*?(HIGH|HOCH).*?\\].*")) {
                        sourceClass = "source-passive-high";
                        displayMsg = displayMsg.replaceFirst("(?i)\\[.*?(HIGH|HOCH).*?\\]", "").trim();
                    } else {
                        // Fallback: strip any bracket tag, assume high priority
                        if (displayMsg.trim().startsWith("[")) {
                            displayMsg = displayMsg.replaceFirst("\\[.*?\\]", "").trim();
                        }
                        sourceClass = "source-passive-high";
                    }

                    if (displayMsg.startsWith(":"))
                        displayMsg = displayMsg.substring(1).trim();

                } else {
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
                    lastLogWasReddit = false;
                }
            }

            String cleanBody = displayMsg.trim();

            // Skip empty passive entries (e.g. failed translation)
            if (isPassive && cleanBody.isEmpty())
                return;

            String formattedBody = formatMessageToHtml(cleanBody);

            if (isPassive) {
                String refPayload = contextData.isEmpty() ? "" : contextData;
                formattedBody = "<span class=\"interactive-report\" onclick=\"analyzeRef('" + escapeJs(refPayload)
                        + "')\">"
                        + formattedBody + "</span>";
            }

            String html = "<span class=\"timestamp\">" + timestamp + "</span> " +
                    "<span class=\"log-source " + sourceClass + "\">[" + source + "]:</span> " +
                    "<span class=\"content\">" + formattedBody + "</span>";

            boolean isPassiveReport = sourceClass.contains("passive");
            String extraClass = "log-type-" + sourceClass
                    + (sourceClass.contains("CLEANUP") || sourceClass.contains("REDDIT")
                            || sourceClass.contains("SYSTEM")
                                    ? " log-dimmed"
                                    : "")
                    + (isPassiveReport ? " eilmeldung" : "");

            webEngine.executeScript("appendLog('" + escapeJs(html) + "', '" + extraClass + "')");
        });
    }

    /**
     * Converts inline markup (backtick subjects, `**bold**`, `u/user`,
     * `[[bearish]]`, etc.) to HTML.
     */
    private String formatMessageToHtml(String text) {
        if (text == null)
            return "";
        StringBuilder sb = new StringBuilder();
        int len = text.length();
        boolean inSubject = false;
        boolean inBold = false;
        boolean inAutoSubject = false;

        for (int i = 0; i < len; i++) {
            // Skip URLs — fast-forward through until whitespace
            if (!inSubject && !inAutoSubject && !inBold && i + 4 < len) {
                String lookahead = text.substring(i, Math.min(i + 8, len));
                if (lookahead.startsWith("http:") || lookahead.startsWith("https:")) {
                    int urlEnd = i;
                    while (urlEnd < len && !Character.isWhitespace(text.charAt(urlEnd))) {
                        urlEnd++;
                    }
                    String url = text.substring(i, urlEnd);
                    sb.append(escapeHtml(url)); // Append pure URL, no internal formatting
                    i = urlEnd - 1;
                    continue;
                }
            }

            if (inAutoSubject) {
                char c = text.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                    sb.append(c);
                    continue;
                } else {
                    sb.append("</span>");
                    inAutoSubject = false;
                }
            }

            if (i + 1 < len && text.charAt(i) == '[' && text.charAt(i + 1) == '[') {
                sb.append("<span class='bearish'>");
                i++;
            } else if (i + 1 < len && text.charAt(i) == ']' && text.charAt(i + 1) == ']') {
                sb.append("</span>");
                i++;
            } else if (i + 1 < len && text.charAt(i) == '<' && text.charAt(i + 1) == '<') {
                sb.append("<span class='bullish'>");
                i++;
            } else if (i + 1 < len && text.charAt(i) == '>' && text.charAt(i + 1) == '>') {
                sb.append("</span>");
                i++;
            } else if (i + 1 < len && text.charAt(i) == '(' && text.charAt(i + 1) == '(') {
                sb.append("<span class='animated-val'>");
                i++;
            } else if (i + 1 < len && text.charAt(i) == ')' && text.charAt(i + 1) == ')') {
                sb.append("</span>");
                i++;
            } else if (text.charAt(i) == '`') {
                if (inSubject) {
                    sb.append("</span>");
                    inSubject = false;
                } else {
                    sb.append("<span class='subject'>");
                    inSubject = true;
                }
            } else if (!inSubject && !inAutoSubject && i + 1 < len && text.charAt(i) == 'u'
                    && text.charAt(i + 1) == '/') {
                sb.append("<span class='subject'>u/");
                inAutoSubject = true;
                i++;
            } else if (i + 1 < len && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                if (inBold) {
                    sb.append("</b>");
                    inBold = false;
                } else {
                    sb.append("<b>");
                    inBold = true;
                }
                i++;
            } else {
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

    /**
     * Escapes a string for safe embedding inside a JavaScript single-quoted
     * literal.
     */
    private String escapeJs(String text) {
        if (text == null)
            return "";
        return text.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n").replace("\r",
                "");
    }

    /**
     * Escapes HTML special characters ({@code &}, {@code <}, {@code >}, {@code "}).
     */
    private String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Highlights all occurrences of the search query in the WebView DOM. */
    @Subscribe
    public void onSearchEvent(SearchEvent event) {
        Platform.runLater(() -> {
            String query = event.query() == null ? "" : event.query().toLowerCase().trim();
            if (webEngine != null) {
                webEngine.executeScript("highlightSearchTerms('" + escapeJs(query) + "')");
            }
        });
    }

    /** Scrolls to the next search match in the WebView. */
    @Subscribe
    public void onSearchNextEvent(SearchNextEvent event) {
        Platform.runLater(() -> {
            if (webEngine != null) {
                webEngine.executeScript("findNextSearchTerm()");
            }
        });
    }

}
