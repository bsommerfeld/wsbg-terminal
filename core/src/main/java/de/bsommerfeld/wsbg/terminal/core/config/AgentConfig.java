package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

/**
 * AI Agent configuration. Only runtime-toggleable flags belong here —
 * model names are deployment constants in AgentBrain.
 */
public class AgentConfig {

    @Key("agent.power-mode")
    @Comment("Enable Power Mode (uses larger reasoning model) (default: false)")
    private boolean powerMode = false;

    public boolean isPowerMode() {
        return powerMode;
    }

    public void setPowerMode(boolean powerMode) {
        this.powerMode = powerMode;
    }
}
