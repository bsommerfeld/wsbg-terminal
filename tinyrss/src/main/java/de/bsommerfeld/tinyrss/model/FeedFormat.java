package de.bsommerfeld.tinyrss.model;

/** The family a feed belongs to - how its entries were read, not what they hold. */
public enum FeedFormat {

    /** RSS 0.91 to 2.0: {@code <rss><channel><item>}. */
    RSS,

    /** RSS 1.0 and 0.90, the RDF generation: {@code <rdf:RDF>} with the items beside the channel. */
    RDF,

    /** Atom 1.0 and its draft 0.3: {@code <feed><entry>}. */
    ATOM
}
