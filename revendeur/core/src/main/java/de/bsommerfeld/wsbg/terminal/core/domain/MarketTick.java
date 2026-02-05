package de.bsommerfeld.wsbg.terminal.core.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a uniform market tick (price update).
 * Used across Data (fetch), DB (persist), and Algo (analyze) layers.
 */
public class MarketTick {

    private final String symbol;
    private final Instant timestamp;
    private final BigDecimal price;
    private final long volume;

    public MarketTick(String symbol, Instant timestamp, BigDecimal price, long volume) {
        this.symbol = symbol;
        this.timestamp = timestamp;
        this.price = price;
        this.volume = volume;
    }

    public String getSymbol() {
        return symbol;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getVolume() {
        return volume;
    }

    @Override
    public String toString() {
        return String.format("Tick{sym=%s, t=%s, p=%s, v=%d}", symbol, timestamp, price, volume);
    }
}
