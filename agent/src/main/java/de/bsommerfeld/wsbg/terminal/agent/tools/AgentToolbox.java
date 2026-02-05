package de.bsommerfeld.wsbg.terminal.agent.tools;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper;

import dev.langchain4j.agent.tool.Tool;
import jakarta.inject.Inject;

@Singleton
public class AgentToolbox {

    private final RedditScraper redditScraper;

    private final ApplicationEventBus eventBus;

    @Inject
    public AgentToolbox(RedditScraper redditScraper,
            ApplicationEventBus eventBus) {
        this.redditScraper = redditScraper;
        this.eventBus = eventBus;
    }

    // @Tool("Get the current price and volume for a given stock symbol or asset
    // (e.g. Gold, Silver)")
    public String getStockPrice(String symbol) {
        return "Stock price search is currently disabled.";
    }

    // @Tool("Scan a specific subreddit for sentiment analysis")
    public String scanSubreddit(String subredditName) {
        redditScraper.scanSubreddit(subredditName);
        return "Scanned " + subredditName + ". Check database for new threads.";
    }

    // @Tool("Search the web for news on a specific topic")
    public String searchWeb(String query) {
        // Silent execution - no UI logging
        return "Web search is disabled.";
    }

    // @Tool("Open a specific tab in the UI")
    public void openTab(String tabName) {
        eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.OpenTabEvent(tabName));
    }

}
