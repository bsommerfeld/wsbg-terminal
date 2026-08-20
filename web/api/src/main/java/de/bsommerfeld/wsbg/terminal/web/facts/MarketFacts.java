package de.bsommerfeld.wsbg.terminal.web.facts;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import java.util.Optional;

/**
 * Everything the facts pipeline could graze for one instrument, in one
 * bundle — the third pipeline's answer beside the article collections.
 * Every block is optional: sources fail or don't cover the instrument, and a
 * partial bundle is a valid answer (what is missing shows in the gateway's
 * per-source outcomes, not as a hole the caller must guess about).
 */
public record MarketFacts(
        Optional<MarketSnapshot> price,
        Optional<VenueStats> venueStats,
        Optional<OrderBookSnapshot> orderBook,
        Optional<InstrumentFacts> profile,
        Optional<AnalystView> analystView,
        Optional<AnalystActions> analystActions,
        Optional<ShortInterest> shortInterest,
        Optional<InsiderDealings> insiderDealings,
        Optional<HedgeFundPopularity> hedgeFundPopularity,
        Optional<UsListingStats> usListingStats) {

    public MarketFacts {
        if (price == null) price = Optional.empty();
        if (venueStats == null) venueStats = Optional.empty();
        if (orderBook == null) orderBook = Optional.empty();
        if (profile == null) profile = Optional.empty();
        if (analystView == null) analystView = Optional.empty();
        if (analystActions == null) analystActions = Optional.empty();
        if (shortInterest == null) shortInterest = Optional.empty();
        if (insiderDealings == null) insiderDealings = Optional.empty();
        if (hedgeFundPopularity == null) hedgeFundPopularity = Optional.empty();
        if (usListingStats == null) usListingStats = Optional.empty();
    }

    /** The bundle with nothing in it — a facts pipeline that found no door. */
    public static final MarketFacts EMPTY = new MarketFacts(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

}
