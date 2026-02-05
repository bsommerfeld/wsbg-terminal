package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.core.ConfigurablePojo;
import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;
import de.bsommerfeld.jshepherd.annotation.Section;

@Comment("Revendeur - Leviathan Global Configuration")
public class GlobalConfig extends ConfigurablePojo<GlobalConfig> {

    @Key("debug-mode")
    @Comment("Enable detailed debug logging and UI inspection")
    private boolean debugMode = false;

    @Key("ui-reddit-visible")
    @Comment("Visibility state of the Reddit List Panel")
    private boolean redditListVisible = true;

    @Section("market")
    @Comment("Market Data Provider Settings")
    private MarketConfig market = new MarketConfig();

    @Section("agent")
    @Comment("AI Agent Settings")
    private AgentConfig agent = new AgentConfig();

    public boolean isRedditListVisible() {
        return redditListVisible;
    }

    public void setRedditListVisible(boolean redditListVisible) {
        this.redditListVisible = redditListVisible;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public MarketConfig getMarket() {
        return market;
    }

    public AgentConfig getAgent() {
        return agent;
    }
}
