/**
 * TinySearch: searches the web on several search engines at once, reading
 * each one's own result page through TinyFetch - see
 * {@link de.bsommerfeld.tinysearch.api.TinySearch}.
 */
module de.bsommerfeld.tinysearch {
    requires transitive de.bsommerfeld.tinyfetch;
    requires org.jsoup;

    exports de.bsommerfeld.tinysearch.api;
}
