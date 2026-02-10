package de.bsommerfeld.wsbg.terminal.ui.view.dashboard;

import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import com.google.common.eventbus.Subscribe;
import de.bsommerfeld.wsbg.terminal.agent.ChatService;
import de.bsommerfeld.wsbg.terminal.agent.ChatService.AgentResponseEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.OpenTabEvent;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphController;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.ToggleGraphViewEvent;

import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TriggerAgentAnalysisEvent;
import jakarta.inject.Inject;
import com.google.inject.Injector;
import de.bsommerfeld.wsbg.terminal.ui.view.news.NewsViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;

import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.scene.web.WebEngine;
import javafx.collections.ListChangeListener;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.scene.input.ScrollEvent;
import javafx.application.Platform;
import javafx.scene.control.ScrollBar;
import javafx.util.Duration;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import java.util.Set;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;

public class DashboardController {

    @FXML
    private javafx.scene.control.SplitPane rootSplitPane;

    @FXML
    private javafx.scene.control.Label liveFeedLabel;

    @FXML
    private javafx.scene.layout.StackPane mainContentStack;

    private final DashboardViewModel viewModel;
    private final NewsViewModel newsViewModel;
    private final ApplicationEventBus eventBus;
    private boolean isAnimating = false;

    // Log Aggregation State
    private boolean lastLogWasReddit = false;
    private int accRedditThreads = 0;
    private int accRedditUpvotes = 0;
    private int accRedditComments = 0;

    @FXML
    private WebView logWebView;
    private WebEngine webEngine;

    private final Injector injector;
    private final I18nService i18n;
    private final de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig globalConfig;
    private final GraphController graphController;

    @Inject
    public DashboardController(DashboardViewModel viewModel,
            NewsViewModel newsViewModel,
            ApplicationEventBus eventBus,
            Injector injector,
            de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig globalConfig,
            I18nService i18n,
            GraphController graphController) {
        this.viewModel = viewModel;
        this.newsViewModel = newsViewModel;
        this.eventBus = eventBus;
        this.injector = injector;
        this.globalConfig = globalConfig;
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
            // Transparent background
            logWebView.setPageFill(javafx.scene.paint.Color.TRANSPARENT);
            logWebView.setContextMenuEnabled(false);

            // Load Font URLs for WebView
            String fontUrlRegular = getClass().getResource("/fonts/FiraCode-Regular.ttf").toExternalForm();
            String fontUrlBold = getClass().getResource("/fonts/FiraCode-Bold.ttf").toExternalForm();
            String fontUrlRetina = getClass().getResource("/fonts/FiraCode-Retina.ttf").toExternalForm();

            String fontFaceCss = "@font-face { font-family: 'Fira Code'; src: url('" + fontUrlRegular
                    + "'); font-weight: normal; font-style: normal; }" +
                    "@font-face { font-family: 'Fira Code Retina'; src: url('" + fontUrlRetina
                    + "'); font-weight: normal; font-style: normal; }" +
                    "@font-face { font-family: 'Fira Code'; src: url('" + fontUrlBold
                    + "'); font-weight: bold; font-style: normal; }";

            String initialHtml = "<html><head><style>" +
                    ":root { --source-width: 40px; }" +
                    fontFaceCss +
                    "body { box-sizing: border-box; font-family: 'Fira Code', 'Fira Code Retina', 'JetBrains Mono', 'Consolas', monospace; font-size: 14px; width: 100%; max-width: 100%; color: #e0e0e0; background-color: transparent; margin: 0; padding: 10px; overflow-x: hidden; overflow-y: scroll; }"
                    +
                    // Hardware accelerate entries to prevent repaint lag
                    "@keyframes fadeInUp { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }"
                    +
                    "@keyframes slideInLeft { 0% { opacity: 0; transform: translateX(-100px); } 100% { opacity: 1; transform: translateX(0); } }"
                    +
                    "@keyframes bounceIn { 0% { opacity: 0; transform: scale(0.3); } 50% { opacity: 1; transform: scale(1.05); } 70% { transform: scale(0.9); } 100% { transform: scale(1); } }"
                    +
                    ".log-entry { margin-bottom: 4px; line-height: 1.4; display: flex; align-items: flex-start; animation: fadeInUp 0.3s cubic-bezier(0.2, 0.8, 0.2, 1) forwards; }"
                    +

                    // Column 1: Timestamp
                    ".timestamp { color: #a0a0a0; font-weight: normal; flex: 0 0 70px; font-size: 11px; user-select: none; padding-top: 2px; margin-right: 4px; }"
                    +

                    // Column 2: Source
                    ".log-source { font-weight: bold; flex: 0 0 var(--source-width); text-align: right; margin-right: 10px; user-select: none; }"
                    +

                    // Column 3: Content
                    ".content { color: #e0e0e0; white-space: pre-wrap; flex: 1; overflow-wrap: break-word; }" +

                    // Source Colors
                    ".source-SYSTEM { color: #5c7cfa; }" +
                    ".source-USER { color: #69db7c; font-weight: bold; }" +
                    ".source-AI { color: #ffa94d; font-weight: bold; }" +
                    ".source-ERROR { color: #ff6b6b; font-weight: bold; }" +
                    ".source-CLEANUP { color: #555555; }" +
                    ".source-REDDIT { color: #555555; }" +

                    // Status Line
                    "#status { color: #888; font-style: italic; margin-top: 4px; display: flex; align-items: center; padding-left: calc(80px + var(--source-width)); }"
                    +
                    "#spinner { color: #4CAF50; font-weight: bold; margin-left: 8px; font-family: 'Fira Code', 'Fira Code Retina', 'JetBrains Mono', 'Consolas', monospace; }"
                    +
                    ".subject { color: #ffb86c; font-weight: bold; }" +
                    ".bullish { color: #50fa7b; background-color: rgba(80, 250, 123, 0.15); padding: 2px 0px; border-radius: 4px; }"
                    +
                    ".bearish { color: #ff5555; background-color: rgba(255, 85, 85, 0.15); padding: 2px 0px; border-radius: 4px; }"
                    +
                    ".interactive-report { cursor: pointer !important; display: inline-block; }" +
                    ".interactive-report:hover { background-color: rgba(255, 255, 255, 0.1); border-radius: 4px; }"
                    +
                    ".search-highlight { background-color: #ffd700; color: #000000; font-weight: bold; }" +

                    // Passive Agent Priority Colors
                    ".source-passive-low { color: rgba(110, 74, 46, 0.6); font-weight: bold; }" +
                    ".source-passive-med { color: rgba(214, 143, 0, 0.85); font-weight: bold; }" +
                    ".source-passive-high { color: #ff9f00; font-weight: bold; text-shadow: 0 0 10px rgba(255, 159, 0, 0.7); }"
                    +

                    // Dimmed Content Styles
                    ".log-dimmed .content { color: #666; }" +

                    // Assistant Background (Subtle)
                    ".log-type-source-AI { background-color: rgba(255, 255, 255, 0.015); border-radius: 6px; padding: 4px 8px; margin: 0 -8px; }"
                    +

                    // Revert to EXACT original CSS (proven to work)
                    "::-webkit-scrollbar { width: 10px; }" +
                    "::-webkit-scrollbar-track { background: transparent; }" +
                    // Default State (Invisible)
                    "::-webkit-scrollbar-thumb { background-color: rgba(68, 68, 68, 0.0); border-radius: 4px; border: 2px solid transparent; background-clip: content-box; transition: none; }"
                    +
                    // Active Scrolling (Instant Visible)
                    "body.scrolling ::-webkit-scrollbar-thumb, body.scrolling::-webkit-scrollbar-thumb { background-color: rgba(68, 68, 68, 0.8) !important; transition: none; }"
                    +

                    "#sentinel { height: 1px; width: 1px; }" +
                    "</style>" +
                    "<script>" +
                    "var maxSourceWidth = 40;" +
                    "function checkWidth(text) {" +
                    "   var tester = document.getElementById('width-tester');" +
                    "   if (!tester) return;" +
                    "   tester.textContent = text;" +
                    "   var w = tester.getBoundingClientRect().width;" +
                    "   var req = Math.ceil(w);" +
                    "   if (req > maxSourceWidth) {" +
                    "       maxSourceWidth = req;" +
                    "       document.documentElement.style.setProperty('--source-width', maxSourceWidth + 'px');" +
                    "   }" +
                    "}" +
                    "var spinnerFrames = ['|', '/', '-', '\\\\'];" +
                    "var frameIndex = 0;" +
                    "var spinnerInterval = null;" +
                    "var isAtBottom = true;" +
                    "var scrollTimeout = null;" +

                    // Keep IntersectionObserver (This fixes the 'lag'/'hinterher' issue)
                    "var observer = new IntersectionObserver(function(entries) {" +
                    "   isAtBottom = entries[0].isIntersecting;" +
                    "});" +

                    "window.addEventListener('load', function() {" +
                    "   var sentinel = document.getElementById('sentinel');" +
                    "   if(sentinel) observer.observe(sentinel);" +
                    "});" +

                    // Scroll Listener maintains the 'scrolling' class but works passively
                    "window.addEventListener('scroll', function() {" +
                    "   document.body.classList.add('scrolling');" +
                    "   if (scrollTimeout) clearTimeout(scrollTimeout);" +
                    "   scrollTimeout = setTimeout(function() {" +
                    "       document.body.classList.remove('scrolling');" +
                    "   }, 1000);" +
                    "}, { passive: true });" +

                    "function startSpinner() {" +
                    "   if (spinnerInterval) return;" +
                    "   spinnerInterval = setInterval(function() {" +
                    "       var el = document.getElementById('spinner');" +
                    "       if (el) {" +
                    "           el.innerText = spinnerFrames[frameIndex];" +
                    "           frameIndex = (frameIndex + 1) % spinnerFrames.length;" +
                    "       }" +
                    "   }, 100);" +
                    "}" +
                    "function stopSpinner() {" +
                    "   if (spinnerInterval) {" +
                    "       clearInterval(spinnerInterval);" +
                    "       spinnerInterval = null;" +
                    "   }" +
                    "}" +
                    "var activeSearchTerm = '';" +
                    "function highlightTextInNode(rootNode, term) {" +
                    "   if (!term || term.trim() === '') return;" +
                    "   var safeTerm = term.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&');" +
                    "   var walker = document.createTreeWalker(rootNode, NodeFilter.SHOW_TEXT, null, false);" +
                    "   var textNodes = [];" +
                    "   while(walker.nextNode()) textNodes.push(walker.currentNode);" +
                    "   var regex = new RegExp('(' + safeTerm + ')', 'gi');" +
                    "   textNodes.forEach(function(node) {" +
                    "       if (node.parentNode.classList.contains('timestamp') || node.parentNode.classList.contains('source')) return;"
                    +
                    "       var text = node.nodeValue;" +
                    "       if (text.match(regex)) {" +
                    "           var span = document.createElement('span');" +
                    "           span.innerHTML = text.replace(regex, '<span class=\"search-highlight\">$1</span>');" +
                    "           node.parentNode.replaceChild(span, node);" +
                    "       }" +
                    "   });" +
                    "}" +
                    "function appendLog(html, extraClass) {" +
                    "   var content = document.getElementById('content');" +
                    "   var div = document.createElement('div');" +
                    "   div.className = 'log-entry ' + (extraClass || '');" +
                    "   div.innerHTML = html;" +
                    "   var src = div.querySelector('.log-source');" +
                    "   if (src) checkWidth(src.textContent);" +
                    "   if (activeSearchTerm) highlightTextInNode(div, activeSearchTerm);" +
                    "   content.appendChild(div);" +
                    "   if (isAtBottom) window.scrollTo(0, document.body.scrollHeight);" +
                    "}" +
                    "var currentStreamDiv = null;" +
                    "function startStreamLog(headerHtml, extraClass) {" +
                    "   var content = document.getElementById('content');" +
                    "   currentStreamDiv = document.createElement('div');" +
                    "   currentStreamDiv.className = 'log-entry stream-active ' + (extraClass || '');" +
                    "   currentStreamDiv.innerHTML = headerHtml + '<span class=\"content\"></span>';" +
                    "   var src = currentStreamDiv.querySelector('.log-source');" +
                    "   if (src) checkWidth(src.textContent);" +
                    "   content.appendChild(currentStreamDiv);" +
                    "   if (isAtBottom) window.scrollTo(0, document.body.scrollHeight);" +
                    "}" +
                    "function appendStreamToken(tokenHtml) {" +
                    "   if (currentStreamDiv) {" +
                    "       var contentSpan = currentStreamDiv.querySelector('.content');" +
                    "       if (contentSpan) contentSpan.innerHTML += tokenHtml;" +
                    "       if (isAtBottom) window.scrollTo(0, document.body.scrollHeight);" +
                    "   }" +
                    "}" +
                    "function endStreamLog() { currentStreamDiv = null; }" +
                    "function removeCurrentStream() {" +
                    "   if (currentStreamDiv) { currentStreamDiv.remove(); currentStreamDiv = null; }" +
                    "   var zombies = document.querySelectorAll('.stream-active');" +
                    "   zombies.forEach(function(el) { el.remove(); });" +
                    "}"
                    +
                    "function updateLastRedditLog(htmlContent) {" +
                    "   var content = document.getElementById('content');" +
                    "   var last = content.lastElementChild;" +
                    "   if (last && last.classList.contains('log-type-source-REDDIT')) {" +
                    "       var contentSpan = last.querySelector('.content');" +
                    "       if (contentSpan) {" +
                    "           var oldVals = [];" +
                    "           contentSpan.querySelectorAll('.animated-val').forEach(function(s) { oldVals.push(parseInt(s.innerText)); });"
                    +
                    "           contentSpan.innerHTML = htmlContent;" +
                    "           var newSpans = contentSpan.querySelectorAll('.animated-val');" +
                    "           newSpans.forEach(function(span, i) {" +
                    "               if (i < oldVals.length) {" +
                    "                   var start = oldVals[i];" +
                    "                   var end = parseInt(span.innerText);" +
                    "                   if (!isNaN(start) && !isNaN(end) && start !== end) {" +
                    "                       animateValue(span, start, end);" +
                    "                   }" +
                    "               }" +
                    "           });" +
                    "       }" +
                    "   }" +
                    "}" +
                    "function animateValue(obj, start, end) {" +
                    "   var delta = end - start;" +
                    "   if (delta === 0) return;" +
                    "   var duration = Math.min(Math.abs(delta) * 100, 2500);" + // 100ms per unit, max 2.5s
                    "   if (duration < 600) duration = 600;" + // Min duration for effect
                    "   var startTime = null;" +
                    "   function step(timestamp) {" +
                    "       if (!startTime) startTime = timestamp;" +
                    "       var progress = timestamp - startTime;" +
                    "       var pct = Math.min(progress / duration, 1.0);" +
                    "       obj.innerText = Math.floor(start + (delta * pct));" +
                    "       if (progress < duration) {" +
                    "           window.requestAnimationFrame(step);" +
                    "       } else {" +
                    "           obj.innerText = end;" +
                    "       }" +
                    "   }" +
                    "   window.requestAnimationFrame(step);" +
                    "}"
                    +
                    "function setStatus(text) {" +
                    "   var st = document.getElementById('status');" +
                    "   if (!text) {" +
                    "       st.innerHTML = '';" +
                    "       stopSpinner();" +
                    "   } else {" +
                    "       st.innerHTML = text + '<span id=\"spinner\">|</span>';" +
                    "       startSpinner();" +
                    "   }" +
                    "   if (isAtBottom) window.scrollTo(0, document.body.scrollHeight);" +
                    "}" +
                    "function clearLogs() {" +
                    "   document.getElementById('content').innerHTML = '';" +
                    "   setStatus('');" +
                    "}" +
                    "var currentMatchIndex = -1;" +
                    "function findNextSearchTerm() {" +
                    "   var content = document.getElementById('content');" +
                    "   if (!content) return;" +
                    "   var highlights = content.querySelectorAll('.search-highlight');" +
                    "   if (highlights.length === 0) return;" +
                    "   if (currentMatchIndex >= 0 && currentMatchIndex < highlights.length) {" +
                    "       highlights[currentMatchIndex].style.outline = 'none';" +
                    "       highlights[currentMatchIndex].style.backgroundColor = '#ffd700';" +
                    "   }" +
                    "   currentMatchIndex = (currentMatchIndex + 1) % highlights.length;" +
                    "   var el = highlights[currentMatchIndex];" +
                    "   el.scrollIntoView({behavior: 'smooth', block: 'center'});" +
                    "   el.style.backgroundColor = '#ffd700';" +
                    "   el.style.outline = 'none';" +
                    "}" +
                    "function highlightSearchTerms(term) {" +
                    "   var content = document.getElementById('content');" +
                    "   if (!content) return;" +
                    "   var highlights = content.querySelectorAll('.search-highlight');" +
                    "   highlights.forEach(function(el) {" +
                    "      var parent = el.parentNode;" +
                    "      if (parent) { parent.replaceChild(document.createTextNode(el.textContent), el); parent.normalize(); }"
                    +
                    "   });" +
                    "   currentMatchIndex = -1;" +
                    "   activeSearchTerm = term;" +
                    "   if (!term || term.trim() === '') return;" +
                    "   highlightTextInNode(content, term);" +
                    "   findNextSearchTerm();" +
                    "}" +
                    "function analyzeRef(ref) {" +
                    "   alert('CMD:ANALYZE_REF:' + ref);" +
                    "}" +
                    "</script></head><body>" +
                    "<div id='content'></div>" +
                    "<div id='status'></div>" +
                    "<div id='sentinel'></div>" +
                    "<div id='width-tester' style='position:absolute; visibility:hidden; white-space:nowrap; font-family: \"Fira Code\", \"Fira Code Retina\", \"JetBrains Mono\", \"Consolas\", monospace; font-size: 13px; font-weight: bold;'></div>"
                    +
                    "</body></html>";

            webEngine.loadContent(initialHtml);

            // Handle Clicks via Alert Interception
            webEngine.setOnAlert(event -> {
                String data = event.getData();
                if (data != null) {
                    if (data.startsWith("CMD:ANALYZE_REF:")) {
                        String ref = data.substring(16);
                        // Trigger analysis using the reference (Permalink)
                        eventBus.post(new TriggerAgentAnalysisEvent("analyze-ref:" + ref));
                    }
                }
            });
        }

        // Initialize Reddit List - REMOVED
        filteredThreads = new javafx.collections.transformation.FilteredList<>(newsViewModel.getThreads(), p -> true);

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

        // Register for events
        eventBus.register(this);

        // Initial Visibility State
        // Store reference to the sidebar (2nd item)
        if (rootSplitPane.getItems().size() > 1) {
            // Remove sidebar if present (Reddit List is gone)
            rootSplitPane.getItems().remove(1);
        }

        // Initialize Graph View
        if (mainContentStack != null && globalConfig.getAgent().isAllowGraphView()) {
            mainContentStack.getChildren().add(graphController.getView());
            graphController.getView().setVisible(false); // Default OFF (Terminal first)
            graphController.start(); // Start immediately to pre-load and settle
            // GraphController self-manages loops. It will run in background initially.

            // Ensure WebView is visible
            if (logWebView != null) {
                logWebView.setVisible(true);
            }
        } else if (logWebView != null) {
            logWebView.setVisible(true);
        }
    }

    @Subscribe
    public void onToggleGraphView(ToggleGraphViewEvent event) {
        if (!globalConfig.getAgent().isAllowGraphView())
            return; // Ignore if disabled

        javafx.application.Platform.runLater(() -> {
            boolean isGraphVisible = graphController.getView().isVisible();
            if (isGraphVisible) {
                // Hide Graph, Show Terminal
                graphController.stop();
                graphController.getView().setVisible(false);
                if (logWebView != null)
                    logWebView.setVisible(true);
            } else {
                // Show Graph, Hide Terminal
                if (logWebView != null)
                    logWebView.setVisible(false);
                graphController.getView().setVisible(true);
                graphController.start();
            }
        });
    }

    private Node sidebarNode;

    @Subscribe
    public void onToggleRedditPanelEvent(
            de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.ToggleRedditPanelEvent event) {
        // No-op: Reddit List removed
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
    public void onClearTerminalEvent(de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.ClearTerminalEvent event) {
        javafx.application.Platform.runLater(() -> {
            if (webEngine != null) {
                webEngine.executeScript("clearLogs()");
            }
        });
    }

    // Streaming Logic Flags
    private boolean ignoreStreamedLog = false;

    @Subscribe
    public void onAgentStreamStart(ChatService.AgentStreamStartEvent event) {
        javafx.application.Platform.runLater(() -> {
            // Prepare Header
            java.time.LocalTime now = java.time.LocalTime.now();
            String timestamp = String.format("[%02d:%02d:%02d]", now.getHour(), now.getMinute(), now.getSecond());

            // Default Source
            String source = i18n.get("log.source.agent");
            String sourceClass = "source-AI";

            // Override from Event
            if (event.source != null)
                source = event.source;
            if (event.cssClass != null)
                sourceClass = event.cssClass;

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
        javafx.application.Platform.runLater(() -> {
            // Check null or empty
            if (event.token != null) {
                // Must double escape: First for HTML content, then for JS string
                webEngine.executeScript("appendStreamToken('" + escapeJs(escapeHtml(event.token)) + "')");
            }
        });
    }

    @Subscribe
    public void onAgentStatus(ChatService.AgentStatusEvent event) {
        javafx.application.Platform.runLater(() -> {
            if (event.status == null || event.status.isEmpty()) {
                webEngine.executeScript("setStatus('')");
            } else {
                webEngine.executeScript("setStatus('" + escapeJs(event.status) + "')");
            }
        });

    }

    @Subscribe
    public void onAgentStreamEnd(ChatService.AgentStreamEndEvent event) {
        javafx.application.Platform.runLater(() -> {
            boolean isPassive = event.fullMessage.contains("||PASSIVE||");

            webEngine.executeScript("removeCurrentStream()");

            // Only interact with Status/UI if this was a Main Agent task (not Passive)
            if (!isPassive) {
                webEngine.executeScript("setStatus('')"); // FORCE RESET STATUS
                // Re-enable UI

            }

            // Add to history, and allow display (replacing the removed stream)
            ignoreStreamedLog = false;
            // Use LogType.DEFAULT to ensure validity and persistence
            viewModel.appendToConsole("||AI_FINAL||" + event.fullMessage, LogType.DEFAULT);
        });
    }

    @Subscribe
    public void onAgentResponse(AgentResponseEvent event) {
        // Marker for internal detection to strip "AI:"
        // Only handle if not covered by StreamEnd (but ChatService doesn't send this
        // anymore for streams)
        viewModel.appendToConsole("||AI_FINAL||" + event.message);
        javafx.application.Platform.runLater(() -> {
            // Re-enable UI on error/completion

        });
    }

    @Subscribe
    public void onOpenTab(OpenTabEvent event) {
        javafx.application.Platform.runLater(() -> {
            viewModel.appendToConsole(i18n.get("system.nav_disabled"));
        });
    }

    @Subscribe
    public void onLogEvent(LogEvent event) {
        javafx.application.Platform.runLater(() -> {
            LogType type = LogType.DEFAULT;
            try {
                type = LogType.valueOf(event.type);
            } catch (IllegalArgumentException e) {
                // Keep default
            }
            processLogEntry(new LogMessage(event.message, type));
        });
    }

    // --- Smart Terminal Logic (WebView) ---

    private void processLogEntry(LogMessage log) {
        javafx.application.Platform.runLater(() -> {
            if (webEngine == null)
                return;

            // Handle Streamed Replay Prevention
            if (log.getType() == LogType.STREAMED) {
                if (ignoreStreamedLog) {
                    ignoreStreamedLog = false;
                    return; // Skip visual append
                }
            }

            String originalMsg = log.getMessage().trim();

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

                // 1. The Prompt (Context anchor)
                // Use a unique ID if needed or just styling
                html.append(
                        "<div style='font-family: \"Fira Code\", monospace; font-size: 14px; margin-bottom: 15px; width: 100%; text-align: left;'>");
                html.append(
                        "<span style='color: #50fa7b; font-weight: bold;'>root@wsbg-term</span>:<span style='color: #bd93f9;'>~</span>$ <span style='color: #f8f8f2;'>./init_sequence.sh --visual</span>");
                html.append("</div>");

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

                // 3. The Result / Anchor
                String footerDelay = ((lines.length * 80) + 200) + "ms";
                html.append(
                        "<div style='margin-top: 15px; font-family: \"Fira Code\", monospace; font-size: 14px; color: #6272a4; opacity: 0; animation: fadeInUp 0.5s ease-out forwards; animation-delay: "
                                + footerDelay + ";'>");
                html.append("[ <span style='color: #50fa7b;'>OK</span> ] Core systems loaded successfully.");
                html.append("</div>");

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
                animateLiveFeedHeader();
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
            java.time.LocalTime now = java.time.LocalTime.now();
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
                if (log.getType() == LogType.ERROR) {
                    lastLogWasReddit = false;
                    source = i18n.get("log.source.error");
                    sourceClass = "source-ERROR";
                } else if (log.getType() == LogType.CLEANUP) {
                    lastLogWasReddit = false;
                    source = i18n.get("log.source.cleanup");
                    sourceClass = "source-CLEANUP";
                    try {
                        displayMsg = i18n.get("log.cleanup.message", displayMsg);
                    } catch (Exception e) {
                    }
                } else if (log.getType() == LogType.REDDIT) {
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

            String extraClass = "log-type-" + sourceClass +
                    (sourceClass.contains("CLEANUP") || sourceClass.contains("REDDIT") || sourceClass.contains("SYSTEM")
                            ? " log-dimmed"
                            : "");

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

    // --- Search Logic ---
    private String currentSearchQuery = "";
    private javafx.collections.transformation.FilteredList<RedditThread> filteredThreads;

    @Subscribe
    public void onSearchEvent(de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.SearchEvent event) {
        javafx.application.Platform.runLater(() -> {
            this.currentSearchQuery = event.query == null ? "" : event.query.toLowerCase().trim();

            // 1. Filter Reddit List
            if (filteredThreads != null) {
                filteredThreads.setPredicate(thread -> {
                    if (currentSearchQuery.isEmpty())
                        return true;
                    // Search Title and Content
                    boolean matchTitle = thread.getTitle().toLowerCase().contains(currentSearchQuery);
                    boolean matchContent = thread.getTextContent() != null
                            && thread.getTextContent().toLowerCase().contains(currentSearchQuery);

                    // Technically we can't search comments here as they aren't loaded in the model
                    // efficiently yet.
                    // But user request implies it. For now, we search what we have.
                    return matchTitle || matchContent;
                });
                // Notify Search Results Status
                boolean hasResults = !currentSearchQuery.isEmpty() && !filteredThreads.isEmpty();
                eventBus.post(
                        new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.RedditSearchResultsEvent(hasResults));
            }

            // 2. Highlight in Terminal
            if (webEngine != null) {
                webEngine.executeScript("highlightSearchTerms('" + escapeJs(currentSearchQuery) + "')");
            }
        });
    }

    @Subscribe
    public void onSearchNextEvent(de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.SearchNextEvent event) {
        javafx.application.Platform.runLater(() -> {
            if (webEngine != null) {
                webEngine.executeScript("findNextSearchTerm()");
            }
        });
    }

    private void animateLiveFeedHeader() {
        if (liveFeedLabel == null)
            return;

        javafx.animation.FadeTransition fadeOut = new javafx.animation.FadeTransition(javafx.util.Duration.millis(300),
                liveFeedLabel);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.4);

        javafx.animation.FadeTransition fadeIn = new javafx.animation.FadeTransition(javafx.util.Duration.millis(300),
                liveFeedLabel);
        fadeIn.setFromValue(0.4);
        fadeIn.setToValue(1.0);

        javafx.animation.SequentialTransition sequence = new javafx.animation.SequentialTransition(fadeOut, fadeIn);
        sequence.play();
    }

    private void applyScanEffect(double frac, javafx.scene.paint.Color trailColor,
            javafx.scene.paint.Color futureColor) {
        try {
            String text = liveFeedLabel.getText();
            int totalChars = (text != null) ? text.length() : 0;
            if (totalChars < 1)
                return;

            // Width relative to text length (3 chars)
            double scanWidth = 3.0 / (double) totalChars;

            // Map frac (0..1) to a wider range so scanner fully enters and fully exits
            // We want center to travel from roughly (-scanWidth) to (1 + scanWidth)
            // Adding a buffer ensures the orange block completely clears the text
            double rangeMult = 1.0 + (3.0 * scanWidth);
            double offset = 1.5 * scanWidth;

            double center = (frac * rangeMult) - offset;

            double start = center - (scanWidth / 2.0);
            double end = center + (scanWidth / 2.0);

            java.util.List<javafx.scene.paint.Stop> stops = new java.util.ArrayList<>();

            // 1. Trail Section (0 to start)
            stops.add(new javafx.scene.paint.Stop(0, trailColor));

            double clampedStart = Math.min(1, Math.max(0, start));
            double clampedEnd = Math.min(1, Math.max(0, end));

            // Transition Trail -> Scanner
            if (clampedStart > 0) {
                stops.add(new javafx.scene.paint.Stop(clampedStart, trailColor));
            }

            // Scannner Section
            if (clampedEnd > clampedStart) {
                stops.add(new javafx.scene.paint.Stop(clampedStart, javafx.scene.paint.Color.ORANGE));
                stops.add(new javafx.scene.paint.Stop(clampedEnd, javafx.scene.paint.Color.ORANGE));
            }

            // Transition Scanner -> Future
            if (clampedEnd < 1) {
                stops.add(new javafx.scene.paint.Stop(clampedEnd, futureColor));
                stops.add(new javafx.scene.paint.Stop(1, futureColor));
            } else if (clampedEnd >= 1 && clampedStart < 1) {
                // Scanner covers the end
                // Implicitly handled by Scanner section reaching 1 (Stop(clampedEnd=1, Orange))
            } else if (clampedStart >= 1) {
                // Trail covers everything (handled by initial Stop(0, trail) + Stop(1, trail)
                // implicitly?)
                // If start >= 1, clampedStart=1. Stop(0, trail), Stop(1, trail).
                // Gradient is flat Trail. Correct.
            }

            javafx.scene.paint.LinearGradient lg = new javafx.scene.paint.LinearGradient(
                    0, 0, 1, 0, true, javafx.scene.paint.CycleMethod.NO_CYCLE, stops);
            liveFeedLabel.setTextFill(lg);
        } catch (Exception e) {
            // e.printStackTrace();
        }
    }
}
