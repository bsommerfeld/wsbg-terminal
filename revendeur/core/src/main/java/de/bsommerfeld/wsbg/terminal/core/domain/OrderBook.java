package de.bsommerfeld.wsbg.terminal.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Collections;

/**
 * Snapshot of the Order Book (Level 2 Data).
 * Contains lists of Bids and Asks sorted by price.
 */
public class OrderBook {

    private final String symbol;
    private final Instant timestamp;
    private final List<Level> bids;
    private final List<Level> asks;

    public OrderBook(String symbol, Instant timestamp, List<Level> bids, List<Level> asks) {
        this.symbol = symbol;
        this.timestamp = timestamp;
        this.bids = Collections.unmodifiableList(bids);
        this.asks = Collections.unmodifiableList(asks);
    }

    public String getSymbol() {
        return symbol;
    }

    public List<Level> getBids() {
        return bids;
    }

    public List<Level> getAsks() {
        return asks;
    }

    /**
     * Best Bid is the first element (highest price).
     */
    public Level getBestBid() {
        return bids.isEmpty() ? null : bids.get(0);
    }

    /**
     * Best Ask is the first element (lowest price).
     */
    public Level getBestAsk() {
        return asks.isEmpty() ? null : asks.get(0);
    }

    /**
     * Represents a single price level in the book.
     */
    public static class Level {
        private final BigDecimal price;
        private final long quantity;

        public Level(BigDecimal price, long quantity) {
            this.price = price;
            this.quantity = quantity;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public long getQuantity() {
            return quantity;
        }
    }
}
