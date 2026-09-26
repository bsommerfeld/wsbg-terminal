/**
 * TinyReddit: reads Reddit over OAuth, JSON or RSS, whichever currently
 * works, through TinyFetch - see {@link de.bsommerfeld.tinyreddit.api.RedditClient}.
 */
module de.bsommerfeld.tinyreddit {
    requires transitive de.bsommerfeld.tinyfetch;
    requires java.xml;

    exports de.bsommerfeld.tinyreddit.api;
    exports de.bsommerfeld.tinyreddit.model;
}
