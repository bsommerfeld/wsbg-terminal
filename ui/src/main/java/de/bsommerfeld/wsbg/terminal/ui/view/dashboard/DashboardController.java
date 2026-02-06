package de.bsommerfeld.wsbg.terminal.ui.view.dashboard;

import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import com.google.common.eventbus.Subscribe;
import de.bsommerfeld.wsbg.terminal.agent.ChatService;
import de.bsommerfeld.wsbg.terminal.agent.ChatService.AgentResponseEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.OpenTabEvent;

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
    private javafx.scene.control.ListView<RedditThread> redditList;

    @FXML
    private javafx.scene.control.SplitPane rootSplitPane;

    @FXML
    private javafx.scene.control.Label liveFeedLabel;

    private final DashboardViewModel viewModel;
    private final NewsViewModel newsViewModel;
    private final ApplicationEventBus eventBus;
    private double lastDividerPosition = 0.75;
    private static final double MAX_DIVIDER = 0.7; // 30% sidebar max width
    private static final double MIN_DIVIDER = 0.85; // 15% sidebar (2/4 of max)
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

    @Inject
    public DashboardController(DashboardViewModel viewModel,
            NewsViewModel newsViewModel,
            ApplicationEventBus eventBus,
            Injector injector,
            de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig globalConfig,
            I18nService i18n) {
        this.viewModel = viewModel;
        this.newsViewModel = newsViewModel;
        this.eventBus = eventBus;
        this.injector = injector;
        this.globalConfig = globalConfig;
        this.i18n = i18n;
    }

    @FXML
    public void initialize() {
        // Enforce SplitPane Constraints
        if (rootSplitPane != null) {
            // Prevent dragging divider too far left (e.g. max width of sidebar)
            // Reduced max width by 25% -> Now limited to 0.7 (30% width max)
            if (!rootSplitPane.getDividers().isEmpty()) {
                rootSplitPane.getDividers().get(0).positionProperty().addListener((obs, oldVal, newVal) -> {
                    if (isAnimating)
                        return;
                    if (newVal.doubleValue() < MAX_DIVIDER) {
                        rootSplitPane.setDividerPositions(MAX_DIVIDER);
                    } else if (newVal.doubleValue() > MIN_DIVIDER) {
                        rootSplitPane.setDividerPositions(MIN_DIVIDER);
                    }
                });
            }
        }

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
                    "body { font-family: 'Fira Code', 'Fira Code Retina', 'JetBrains Mono', 'Consolas', monospace; font-size: 13px; color: #e0e0e0; background-color: transparent; margin: 0; padding: 10px; overflow-x: hidden; overflow-y: scroll; }"
                    +
                    // Hardware accelerate entries to prevent repaint lag
                    ".log-entry { margin-bottom: 4px; line-height: 1.4; display: flex; align-items: flex-start; transform: translate3d(0,0,0); }"
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

        // Initialize Reddit List with Filtered List
        filteredThreads = new javafx.collections.transformation.FilteredList<>(newsViewModel.getThreads(), p -> true);
        redditList.setItems(filteredThreads);

        redditList.setCellFactory(param -> new RedditThreadCell());

        // Setup Ghost ScrollBar with robust layout waiting
        Runnable configureScrollBar = () -> {
            Platform.runLater(() -> Platform.runLater(() -> {
                Set<Node> nodes = redditList.lookupAll(".scroll-bar");
                for (Node node : nodes) {
                    if (node instanceof ScrollBar) {
                        ScrollBar bar = (ScrollBar) node;
                        if (bar.getOrientation() == Orientation.VERTICAL) {
                            setupGhostScrollBar(bar);
                        }
                    }
                }
            }));
        };

        if (redditList.getSkin() != null) {
            configureScrollBar.run();
        }
        redditList.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                configureScrollBar.run();
            }
        });

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
            this.sidebarNode = rootSplitPane.getItems().get(1);
        }
        setRedditListVisible(globalConfig.isRedditListVisible());
    }

    private Node sidebarNode;

    @Subscribe
    public void onToggleRedditPanelEvent(
            de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.ToggleRedditPanelEvent event) {
        javafx.application.Platform.runLater(() -> {
            setRedditListVisible(event.visible);
        });
    }

    private void setRedditListVisible(boolean visible) {
        if (sidebarNode == null && rootSplitPane.getItems().size() > 1) {
            sidebarNode = rootSplitPane.getItems().get(1);
        }
        if (sidebarNode == null)
            return;

        // Reset constraints to allow animation (remove min/max width blocks)
        if (sidebarNode instanceof javafx.scene.layout.Region) {
            javafx.scene.layout.Region r = (javafx.scene.layout.Region) sidebarNode;
            r.setMinWidth(0);
            r.setMaxWidth(Double.MAX_VALUE);
        }

        if (visible) {
            if (!rootSplitPane.getItems().contains(sidebarNode)) {
                rootSplitPane.getItems().add(sidebarNode);

                // Re-apply constraint listener to the new divider
                if (!rootSplitPane.getDividers().isEmpty()) {
                    rootSplitPane.getDividers().get(0).positionProperty().addListener((obs, oldVal, newVal) -> {
                        if (isAnimating)
                            return;
                        if (newVal.doubleValue() < MAX_DIVIDER) {
                            rootSplitPane.setDividerPositions(MAX_DIVIDER);
                        } else if (newVal.doubleValue() > MIN_DIVIDER) {
                            rootSplitPane.setDividerPositions(MIN_DIVIDER);
                        }
                    });
                }

                // Start fully closed (1.0)
                rootSplitPane.setDividerPositions(1.0);

                // Animate open to last known position (or default)
                // Ensure last position respects constraint
                double target = Math.max(lastDividerPosition, MAX_DIVIDER);
                animateDivider(1.0, target, null);
            }
        } else {
            if (rootSplitPane.getItems().contains(sidebarNode)) {
                // Save current position before closing
                double[] divs = rootSplitPane.getDividerPositions();
                if (divs != null && divs.length > 0) {
                    lastDividerPosition = divs[0];
                }

                // Animate closed to 1.0, then remove
                double current = lastDividerPosition;
                animateDivider(current, 1.0, () -> {
                    // Double check we are still attempting to close
                    if (!globalConfig.isRedditListVisible()) {
                        rootSplitPane.getItems().remove(sidebarNode);
                    }
                });
            }
        }
    }

    private void animateDivider(double start, double end, Runnable onFinished) {
        isAnimating = true;
        javafx.animation.Transition transition = new javafx.animation.Transition() {
            {
                setCycleCount(1);
                // Snappy: 70ms
                setCycleDuration(javafx.util.Duration.millis(70));
                setInterpolator(javafx.animation.Interpolator.EASE_BOTH);
            }

            @Override
            protected void interpolate(double frac) {
                // Ensure the node is still there before modifying divider
                if (rootSplitPane.getDividers().isEmpty())
                    return;
                double val = start + (end - start) * frac;
                try {
                    rootSplitPane.setDividerPositions(val);
                } catch (Exception e) {
                    // Ignore transient layout errors during rapid toggles
                }
            }
        };
        transition.setOnFinished(e -> {
            isAnimating = false;
            if (onFinished != null) {
                onFinished.run();
            }
        });
        transition.play();
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

        // Also trigger on generic scroll events on the list itself (uses capture phase
        // to ensure detection)
        redditList.addEventFilter(ScrollEvent.ANY, e -> showScrollBar.run());
    }

    private class RedditThreadCell extends ListCell<RedditThread> {
        private final VBox root = new VBox(4);
        private final javafx.scene.text.TextFlow titleFlow = new javafx.scene.text.TextFlow();
        private final javafx.scene.text.Text arrow = new javafx.scene.text.Text("^");
        private final javafx.scene.text.Text score = new javafx.scene.text.Text();
        private final javafx.scene.text.Text bubble = new javafx.scene.text.Text("*");
        private final javafx.scene.text.Text comments = new javafx.scene.text.Text();

        public RedditThreadCell() {
            root.setMaxWidth(Double.MAX_VALUE);
            root.getStyleClass().add("reddit-thread-root");

            // Title Flow Sizing
            titleFlow.prefWidthProperty().bind(redditList.widthProperty().subtract(40));

            // Stats Row
            javafx.scene.layout.HBox stats = new javafx.scene.layout.HBox(16);
            stats.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            // Score Group
            javafx.scene.layout.HBox scoreBox = new javafx.scene.layout.HBox(4);
            scoreBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            arrow.getStyleClass().add("reddit-thread-arrow");
            arrow.setFont(javafx.scene.text.Font.font("Fira Code", javafx.scene.text.FontWeight.BOLD, 14));
            score.getStyleClass().add("reddit-thread-score");
            score.setFont(javafx.scene.text.Font.font("Fira Code", javafx.scene.text.FontWeight.NORMAL, 12));
            scoreBox.getChildren().addAll(arrow, score);

            // Comments Group
            javafx.scene.layout.HBox commentsBox = new javafx.scene.layout.HBox(3);
            commentsBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            bubble.getStyleClass().add("reddit-thread-bubble");
            bubble.setFont(javafx.scene.text.Font.font("Fira Code", javafx.scene.text.FontWeight.BOLD, 14));
            comments.getStyleClass().add("reddit-thread-comments");
            comments.setFont(javafx.scene.text.Font.font("Fira Code", javafx.scene.text.FontWeight.NORMAL, 12));
            commentsBox.getChildren().addAll(bubble, comments);

            stats.getChildren().addAll(scoreBox, commentsBox);
            root.getChildren().addAll(titleFlow, stats);
        }

        @Override
        protected void updateItem(RedditThread item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                setStyle("-fx-background-color: transparent;");
                setOnMouseClicked(null);
            } else {
                // Update Content
                score.setText(String.valueOf(item.getScore()));
                comments.setText(String.valueOf(item.getNumComments()));

                // Highlight Logic for Title
                updateTitleWithHighlight(item.getTitle());

                // Highlight Logic for Content Match (Visual Feedback in Stats)
                if (currentSearchQuery != null && !currentSearchQuery.isEmpty()) {
                    boolean matchContent = item.getTextContent() != null
                            && item.getTextContent().toLowerCase().contains(currentSearchQuery);
                    if (matchContent) {
                        comments.setStyle("-fx-fill: #ffd700; -fx-font-weight: bold;");
                        bubble.setStyle("-fx-fill: #ffd700;");
                    } else {
                        comments.setStyle("");
                        bubble.setStyle("");
                    }
                } else {
                    comments.setStyle("");
                    bubble.setStyle("");
                }

                setGraphic(root);

                // Interaction
                setOnMouseClicked(e -> {
                    if (!redditList.isDisabled()) {
                        redditList.setDisable(true);
                        newsViewModel.analyzeSentiment(item);
                    }
                    e.consume();
                });
            }
        }

        private void updateTitleWithHighlight(String text) {
            titleFlow.getChildren().clear();
            if (currentSearchQuery == null || currentSearchQuery.isEmpty()) {
                javafx.scene.text.Text t = new javafx.scene.text.Text(text);
                t.getStyleClass().add("reddit-thread-title");
                t.setFont(javafx.scene.text.Font.font("Fira Code", javafx.scene.text.FontWeight.BOLD, 13));
                titleFlow.getChildren().add(t);
            } else {
                String lowerText = text.toLowerCase();
                String lowerQuery = currentSearchQuery;
                int lastIndex = 0;
                int index = lowerText.indexOf(lowerQuery);

                while (index >= 0) {
                    if (index > lastIndex) {
                        javafx.scene.text.Text t = new javafx.scene.text.Text(text.substring(lastIndex, index));
                        t.getStyleClass().add("reddit-thread-title");
                        t.setFont(javafx.scene.text.Font.font("Fira Code", javafx.scene.text.FontWeight.BOLD, 13));
                        titleFlow.getChildren().add(t);
                    }

                    javafx.scene.control.Label highlight = new javafx.scene.control.Label(
                            text.substring(index, index + lowerQuery.length()));
                    highlight.setStyle(
                            "-fx-background-color: #ffd700; -fx-text-fill: #000000; -fx-font-family: 'Fira Code', 'Fira Code Retina', 'JetBrains Mono', 'Consolas', monospace; -fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 0;");
                    titleFlow.getChildren().add(highlight);

                    lastIndex = index + lowerQuery.length();
                    index = lowerText.indexOf(lowerQuery, lastIndex);
                }
                if (lastIndex < text.length()) {
                    javafx.scene.text.Text t = new javafx.scene.text.Text(text.substring(lastIndex));
                    t.getStyleClass().add("reddit-thread-title");
                    t.setFont(javafx.scene.text.Font.font("Fira Code", javafx.scene.text.FontWeight.BOLD, 13));
                    titleFlow.getChildren().add(t);
                }
            }
        }

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
            if (redditList != null && !i18n.get("log.source.passive_agent").equals(source)) {
                redditList.setDisable(true);
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
                if (redditList != null && redditList.isDisable()) {
                    // If status is cleared (and not streaming), enable list?
                    // Careful: Stream might be running.
                    // But usually status clear implies idle.
                    // Let's rely on StreamEnd for enabling content logic.
                }
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
                if (redditList != null) {
                    redditList.setDisable(false);
                }
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
            if (redditList != null) {
                redditList.setDisable(false);
            }
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
                redditList.refresh(); // Trigger highlighting update

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

        javafx.animation.SequentialTransition sequence = new javafx.animation.SequentialTransition();

        javafx.scene.paint.Color normalColor = javafx.scene.paint.Color.web("#888888");
        javafx.scene.paint.Color darkColor = javafx.scene.paint.Color.web("#555555");

        // Iteration 1: Scan (Normal -> Dark)
        // Scanner moves through, leaving a Dark trail explicitly
        javafx.animation.Transition scanPass1 = new javafx.animation.Transition() {
            {
                setCycleCount(1);
                setCycleDuration(javafx.util.Duration.seconds(0.6));
                setInterpolator(javafx.animation.Interpolator.LINEAR);
            }

            @Override
            protected void interpolate(double frac) {
                // Trail: Dark, Future: Normal
                applyScanEffect(frac, darkColor, normalColor);
            }
        };

        // Pause
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(
                javafx.util.Duration.seconds(0.4));

        // Iteration 2: Scan (Dark -> Normal)
        // Scanner moves through, restoring Normal color
        javafx.animation.Transition scanPass2 = new javafx.animation.Transition() {
            {
                setCycleCount(1);
                setCycleDuration(javafx.util.Duration.seconds(0.6));
                setInterpolator(javafx.animation.Interpolator.LINEAR);
            }

            @Override
            protected void interpolate(double frac) {
                // Trail: Normal, Future: Dark
                applyScanEffect(frac, normalColor, darkColor);
            }
        };

        sequence.getChildren().addAll(scanPass1, pause, scanPass2);
        // Ensure strictly set to Normal at the end
        sequence.setOnFinished(e -> liveFeedLabel.setTextFill(normalColor));
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
