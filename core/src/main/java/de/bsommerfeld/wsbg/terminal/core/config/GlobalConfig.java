package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.core.ConfigurablePojo;
import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Section;

@Comment("WSBG Terminal - Global Configuration")
public class GlobalConfig extends ConfigurablePojo<GlobalConfig> {

    @Section("agent")
    @Comment("AI Agent Settings")
    private AgentConfig agent = new AgentConfig();

    @Section("reddit")
    @Comment("Reddit Monitor Settings")
    private RedditConfig reddit = new RedditConfig();

    @Section("headlines")
    @Comment("Headline Filter Settings")
    private HeadlineConfig headlines = new HeadlineConfig();

    @Section("user")
    @Comment("User Preferences")
    private UserConfig user = new UserConfig();

    public AgentConfig getAgent() {
        return agent;
    }

    public RedditConfig getReddit() {
        return reddit;
    }

    public HeadlineConfig getHeadlines() {
        return headlines;
    }

    public UserConfig getUser() {
        return user;
    }
}
