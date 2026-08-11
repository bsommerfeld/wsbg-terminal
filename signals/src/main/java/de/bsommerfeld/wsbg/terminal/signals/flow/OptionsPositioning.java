package de.bsommerfeld.wsbg.terminal.signals.flow;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the listed options book for the one thing the share tape cannot say:
 * whether positions are being OPENED or closed.
 *
 * <p><b>Method:</b> share volume counts every trade the same way, so a busy
 * session looks identical whether the crowd is arriving or leaving. The
 * options book separates them, because open interest counts CONTRACTS
 * OUTSTANDING, not trades:
 *
 * <ul>
 *   <li><b>Turnover</b> - today's contract volume against the standing open
 *       interest. Above {@value #TURNOVER_UNUSUAL} the day's trading is a
 *       large fraction of everything ever left open in the name: fresh
 *       positioning, not the book breathing.</li>
 *   <li><b>Put/call spread</b> - today's put/call ratio against the book's.
 *       The book is the accumulated past, the day's flow is the present; when
 *       the flow is far more call-heavy than the book, the crowd arriving today
 *       is betting differently from everyone already positioned.</li>
 *   <li><b>Front-end concentration</b> - the share of today's volume in the
 *       nearest expiries. Short-dated contracts are the speculative end of the
 *       book: they are cheap, they expire before any thesis can play out, and a
 *       crowd piling into them is trading the move, not the company.</li>
 * </ul>
 *
 * <p><b>What this kernel deliberately does NOT do:</b> it never reads the
 * put/call ratio as a direction. A put is as often a hedge as a bet, and
 * dealers take the other side of everything - the ratio measures the CHARACTER
 * of the flow, never where the price goes next. The reading lines say so.
 *
 * <p><b>One-day offset:</b> open interest is the previous session's figure by
 * market convention, today's volume is today's. Every ratio here carries that
 * offset; it is fine for dating a wave and wrong for anything intraday.
 */
public final class OptionsPositioning {

    /** Below this many usable contracts the book is too thin to read. */
    private static final int MIN_CONTRACTS = 40;
    /** Below this open interest the ratios are noise. */
    private static final long MIN_OPEN_INTEREST = 500;
    /** From this turnover the day's flow counts as unusual. */
    private static final double TURNOVER_UNUSUAL = 0.20;
    /** From this turnover the day's flow is extraordinary. */
    private static final double TURNOVER_EXTREME = 0.50;
    /**
     * How far today's flow must lean away from the standing book before the
     * lean is worth a word.
     *
     * <p>Calibrated against live books, not intuition: an equity option book
     * carries structurally MORE puts than its daily flow does, because
     * protective puts accumulate and are held while calls are traded and
     * expire. Measured across eight live names on 2026-08-07 the skew was
     * positive for SEVEN of them (+0.19 to +0.59), so a naive threshold of
     * 0.25 fired on six and would have reported "the arriving crowd is
     * positioned differently" as the normal state of the market. Only the two
     * names actually carrying a wave cleared 0.5.
     */
    private static final double SKEW_MARKED = 0.5;
    /** Expiries counted as the speculative front end. */
    private static final int FRONT_EXPIRIES = 2;
    /**
     * From this front-end share the flow is short-dated speculation.
     *
     * <p>Calibrated on live books, like the skew above: weekly expiries carry
     * most of the volume in EVERY equity name, so a high front-end share is
     * the market's normal shape, not a name's character. Across eight live
     * books on 2026-08-07 the share ran 47-79 % with a median near 66, and a
     * threshold of 0.60 fired on six of them - including Coca-Cola, whose
     * options are nobody's speculation. Only a genuinely crowded front end
     * clears 0.75.
     */
    private static final double FRONT_HEAVY = 0.75;

    private OptionsPositioning() {
    }

    /**
     * One contract of the listed book, as any venue's chain reports it.
     *
     * @param call         true for a call
     * @param expiryEpochDay the expiry as an epoch day, {@code Long.MIN_VALUE} unknown
     * @param volume       contracts traded today, negative when unknown
     * @param openInterest contracts outstanding (previous session), negative unknown
     */
    public record Contract(boolean call, long expiryEpochDay, long volume,
            long openInterest) {
    }

    /**
     * What the book says about today's positioning.
     *
     * @param callVolume        today's call contracts
     * @param putVolume         today's put contracts
     * @param callOpenInterest  outstanding calls
     * @param putOpenInterest   outstanding puts
     * @param turnover          today's volume over the standing open interest
     * @param flowPutCall       today's put/call ratio
     * @param bookPutCall       the book's put/call ratio
     * @param frontShare        share of today's volume in the nearest expiries
     * @param expiries          distinct expiries in the book
     */
    public record Book(long callVolume, long putVolume,
            long callOpenInterest, long putOpenInterest,
            double turnover, double flowPutCall, double bookPutCall,
            double frontShare, int expiries) {

        /** Total contracts traded today. */
        public long volume() {
            return callVolume + putVolume;
        }

        /** Total contracts outstanding. */
        public long openInterest() {
            return callOpenInterest + putOpenInterest;
        }

        /**
         * How much more call-heavy today's flow is than the standing book.
         * Positive = the crowd arriving today leans further towards calls than
         * everyone already positioned; negative = further towards puts.
         */
        public double callSkewVsBook() {
            if (!Double.isFinite(flowPutCall) || !Double.isFinite(bookPutCall)) {
                return Double.NaN;
            }
            return bookPutCall - flowPutCall;
        }
    }

    /**
     * Reads the book.
     *
     * @param chain every contract with volume or open interest
     * @return the reading, or empty when the book is too thin to carry one
     */
    public static Optional<Book> measure(List<Contract> chain) {
        if (chain == null || chain.size() < MIN_CONTRACTS) return Optional.empty();
        long cv = 0;
        long pv = 0;
        long co = 0;
        long po = 0;
        List<Long> expiries = new ArrayList<>();
        for (Contract c : chain) {
            if (c == null) continue;
            long v = Math.max(c.volume(), 0);
            long oi = Math.max(c.openInterest(), 0);
            if (c.call()) {
                cv += v;
                co += oi;
            } else {
                pv += v;
                po += oi;
            }
            if (c.expiryEpochDay() != Long.MIN_VALUE && !expiries.contains(c.expiryEpochDay())) {
                expiries.add(c.expiryEpochDay());
            }
        }
        long oiTotal = co + po;
        if (oiTotal < MIN_OPEN_INTEREST) return Optional.empty();

        expiries.sort(Long::compareTo);
        double frontShare = frontShare(chain, expiries);
        long volume = cv + pv;
        return Optional.of(new Book(cv, pv, co, po,
                (double) volume / oiTotal,
                cv > 0 ? (double) pv / cv : Double.NaN,
                co > 0 ? (double) po / co : Double.NaN,
                frontShare, expiries.size()));
    }

    /** Share of today's volume sitting in the nearest {@value #FRONT_EXPIRIES} expiries. */
    private static double frontShare(List<Contract> chain, List<Long> sortedExpiries) {
        if (sortedExpiries.size() <= FRONT_EXPIRIES) return Double.NaN;
        long cutoff = sortedExpiries.get(FRONT_EXPIRIES - 1);
        long front = 0;
        long all = 0;
        for (Contract c : chain) {
            if (c == null || c.volume() <= 0) continue;
            all += c.volume();
            if (c.expiryEpochDay() != Long.MIN_VALUE && c.expiryEpochDay() <= cutoff) {
                front += c.volume();
            }
        }
        return all > 0 ? (double) front / all : Double.NaN;
    }

    // ---- readings ----

    /**
     * Whether today's options flow is opening positions or just churning.
     *
     * @param b the book, may be null
     */
    public static Optional<SignalReading> turnoverReading(Book b) {
        if (b == null) return Optional.empty();
        String interpretation;
        if (b.turnover() >= TURNOVER_EXTREME) {
            interpretation = "EXTRAORDINARY: today alone traded "
                    + MathKit.fmt(b.turnover() * 100, 0) + " % of everything standing open in "
                    + "this name - positions are being built or torn down right now, not held.";
        } else if (b.turnover() >= TURNOVER_UNUSUAL) {
            interpretation = "UNUSUAL: today's contract volume is a large fraction of the "
                    + "standing book - fresh positioning, not the book breathing.";
        } else {
            interpretation = "Ordinary: today's flow is small against the standing book - "
                    + "whatever moves the share, it is not a rush into its options.";
        }
        return Optional.of(new SignalReading(
                "options-turnover",
                "Options turnover against the standing book",
                b.turnover(),
                MathKit.fmt(b.turnover() * 100, 0) + " % of open interest traded today"
                        + " (" + b.volume() + " contracts against " + b.openInterest() + ")",
                "Today's option contract volume divided by the contracts left outstanding "
                        + "from every session before it.",
                interpretation + " Open interest is the PREVIOUS session's figure, so the "
                        + "comparison carries a one-day offset."));
    }

    /**
     * How today's flow leans against the accumulated book - the tell that
     * separates a crowd arriving from a book merely standing there.
     *
     * @param b the book, may be null
     */
    public static Optional<SignalReading> skewReading(Book b) {
        if (b == null || !Double.isFinite(b.callSkewVsBook())) return Optional.empty();
        double skew = b.callSkewVsBook();
        String interpretation;
        if (skew >= SKEW_MARKED) {
            interpretation = "Today's flow is FAR more call-heavy than the standing book, well "
                    + "beyond the lean an equity book normally shows: whoever is arriving today "
                    + "is positioned differently from everyone already in the name.";
        } else if (skew <= -SKEW_MARKED) {
            interpretation = "Today's flow is FAR more put-heavy than the standing book - "
                    + "the rarer direction, since a book normally carries more puts than its "
                    + "flow does. The arriving side is hedging or betting against.";
        } else if (skew < 0) {
            interpretation = "Today's flow leans MORE to puts than the standing book, which is "
                    + "the uncommon direction, but only mildly.";
        } else {
            interpretation = "Today's flow leans to calls against the book by the ordinary "
                    + "margin - an equity book always carries more puts than its daily flow, "
                    + "because protective puts are held while calls are traded and expire. "
                    + "No change of character in who is positioning.";
        }
        return Optional.of(new SignalReading(
                "options-flow-skew",
                "Today's flow against the standing book",
                skew,
                "flow put/call " + MathKit.fmt(b.flowPutCall(), 2) + " against book "
                        + MathKit.fmt(b.bookPutCall(), 2),
                "The day's put/call ratio compared with the put/call ratio of all "
                        + "outstanding contracts.",
                interpretation + " A put/call ratio is NEVER a direction: a put is as often a "
                        + "hedge as a bet. It measures the character of the flow, not where "
                        + "the price goes."));
    }

    /**
     * How far out the flow is buying - the difference between trading the move
     * and holding a view.
     *
     * @param b the book, may be null
     */
    public static Optional<SignalReading> frontReading(Book b) {
        if (b == null || !Double.isFinite(b.frontShare())) return Optional.empty();
        String interpretation = b.frontShare() >= FRONT_HEAVY
                ? "SHORT-DATED: the flow crowds the nearest expiries far beyond the usual "
                        + "weekly-driven share - contracts that expire before any thesis about "
                        + "the company could play out. This is trading the move, not holding "
                        + "a view."
                : "The flow sits across the curve at the ordinary shape - weekly expiries "
                        + "carry most volume in every equity name, so this says nothing "
                        + "particular about who is positioning here.";
        return Optional.of(new SignalReading(
                "options-front-end",
                "How far out the options flow buys",
                b.frontShare(),
                MathKit.fmt(b.frontShare() * 100, 0) + " % of today's volume in the nearest "
                        + FRONT_EXPIRIES + " of " + b.expiries() + " expiries",
                "Share of the day's option volume sitting in the two nearest expiries.",
                interpretation));
    }
}
