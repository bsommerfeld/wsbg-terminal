package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.List;

/**
 * One order-book snapshot: the visible resting interest per price level —
 * literally "how many orders wait where, with how many shares". The market
 * memory's structure layer reads this beside the volume profile: the profile
 * says where volume TRADED, the book says who is standing there NOW.
 *
 * <p>Depth honesty: keyless sources only ever expose a WINDOW of the book
 * (Börse Frankfurt: 10 levels of the Frankfurt floor specialist book, probed
 * 2026-07-14) — never the consolidated market depth, which is a paid product.
 * Consumers must present it as such.
 *
 * @param isin     the instrument the book belongs to
 * @param time     the venue's own snapshot time string (as shipped)
 * @param bids     buy side, best price first
 * @param asks     sell side, best price first
 */
public record OrderBookSnapshot(String isin, String time,
        List<Level> bids, List<Level> asks) {

    public OrderBookSnapshot {
        bids = bids == null ? List.of() : List.copyOf(bids);
        asks = asks == null ? List.of() : List.copyOf(asks);
    }

    /**
     * One price level of one side.
     *
     * @param price  the limit price
     * @param orders number of resting orders at this level (0 = not published)
     * @param units  total shares/units resting at this level
     */
    public record Level(double price, int orders, long units) {
    }
}
