package de.bsommerfeld.wsbg.terminal.signals.flow;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * WHO is trading, for names that have no listed options book to ask.
 *
 * <p>A German small cap has no US options chain, so the axis that separates
 * money entering from money leaving is simply absent. What its home venue does
 * publish - and almost no other venue does - is the NUMBER OF EXECUTIONS
 * beside the turnover. Their quotient is the average ticket, and that number
 * says something no ratio against a baseline can: a venue clearing a few
 * hundred euros per trade is being used by private investors, one clearing
 * six figures is being used by desks.
 *
 * <p>This is the rare flow figure readable as a LEVEL rather than as a
 * deviation, because the euro amounts are anchored in what a person can
 * plausibly spend. It still says nothing about direction - a thousand small
 * buys and a thousand small sells look identical here.
 *
 * <p><b>Terminal inputs:</b> the German retail venue's own day statistics
 * (turnover and execution count), which the report already holds.
 */
public final class RetailTicketSize {

    /** Below this many executions the average is one loud trade, not a picture. */
    private static final int MIN_EXECUTIONS = 30;
    /** Up to this average ticket the venue's day is private-investor shaped. */
    private static final double RETAIL_CEILING = 5_000;
    /** From this average ticket the day is desk shaped. */
    private static final double INSTITUTIONAL_FLOOR = 25_000;

    private RetailTicketSize() {
    }

    /**
     * Measures the day's average ticket at a retail venue.
     *
     * @param turnover   the venue's day turnover in its own currency
     * @param executions the venue's day execution count
     * @param currency   the venue's currency for the reading line, may be null
     * @return the reading, or empty when the day is too thin to average
     */
    public static Optional<SignalReading> measure(double turnover, long executions,
            String currency) {
        if (!Double.isFinite(turnover) || turnover <= 0) return Optional.empty();
        if (executions < MIN_EXECUTIONS) return Optional.empty();
        double ticket = turnover / executions;
        if (!Double.isFinite(ticket) || ticket <= 0) return Optional.empty();
        String unit = currency == null || currency.isBlank() ? "" : " " + currency;

        String interpretation;
        if (ticket <= RETAIL_CEILING) {
            interpretation = "PRIVATE INVESTORS: the average trade at this venue is small enough "
                    + "to be a person's order. A crowd showing up here shows up as MANY small "
                    + "tickets, which is exactly what this number counts.";
        } else if (ticket >= INSTITUTIONAL_FLOOR) {
            interpretation = "DESKS: the average trade is far above what a private order looks "
                    + "like - whatever moves here is not a retail crowd.";
        } else {
            interpretation = "Mixed: the average trade sits between a private order and a desk's "
                    + "- no side of the market owns this venue's day.";
        }
        return Optional.of(new SignalReading(
                "retail-ticket-size",
                "Average ticket at the retail venue",
                ticket,
                MathKit.fmt(ticket, 0) + unit + " per trade (" + executions
                        + " executions)",
                "The venue's day turnover divided by its number of executions - the size of "
                        + "the average order actually filled there.",
                interpretation + " It says WHO trades, never which way: a thousand small buys "
                        + "and a thousand small sells look identical here."));
    }
}
