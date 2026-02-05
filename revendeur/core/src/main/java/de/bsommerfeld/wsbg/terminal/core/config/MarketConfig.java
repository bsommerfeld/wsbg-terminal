package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

import java.util.List;

public class MarketConfig {

    @Key("api-key")
    @Comment("Twelve Data API Key")
    private String apiKey = "demo";

    @Key("active-symbols")
    @Comment("List of symbols to trade/monitor")
    private List<String> activeSymbols = List.of("AAPL", "TSLA", "NVDA", "BTC/USD");

    @Key("provider")
    @Comment("Market Data Provider Settings")
    private String provider = "yahoo";

    @Key("update-interval")
    @Comment("Data update interval in seconds")
    private int updateInterval = 60;

    public String getApiKey() {
        return apiKey;
    }

    public List<String> getActiveSymbols() {
        return activeSymbols;
    }

    public String getProvider() {
        return provider;
    }

    public int getUpdateInterval() {
        return updateInterval;
    }
}
