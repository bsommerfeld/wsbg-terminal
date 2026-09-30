/**
 * TinyReddit: reads Reddit over JSON or RSS, whichever currently
 * works, through TinyFetch - see {@link de.bsommerfeld.tinyreddit.api.RedditClient}.
 * The feeds are read by TinyRss.
 */
module de.bsommerfeld.tinyreddit {
    requires transitive de.bsommerfeld.tinyfetch;
    requires de.bsommerfeld.tinyrss;

    exports de.bsommerfeld.tinyreddit.api;
    exports de.bsommerfeld.tinyreddit.model;
}
