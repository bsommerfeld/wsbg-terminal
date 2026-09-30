/**
 * TinyRss: reads any site's RSS or Atom feed through TinyFetch - see
 * {@link de.bsommerfeld.tinyrss.api.FeedReader}. Feeds are read by the
 * module's own tolerant parser, so a broken feed still yields its entries.
 */
module de.bsommerfeld.tinyrss {
    requires transitive de.bsommerfeld.tinyfetch;

    exports de.bsommerfeld.tinyrss.api;
    exports de.bsommerfeld.tinyrss.model;
}
