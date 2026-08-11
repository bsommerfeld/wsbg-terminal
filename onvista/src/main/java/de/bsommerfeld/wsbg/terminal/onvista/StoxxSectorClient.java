package de.bsommerfeld.wsbg.terminal.onvista;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The EUROPEAN sector board: the STOXX Europe 600 supersectors, quoted in ONE
 * request through onvista's bulk endpoint (live-probed 2026-08-11).
 *
 * <p>The house measured a German share's sector against a US sector ETF -
 * against a market that is closed when Frankfurt opens and that prices a
 * different set of companies in a different currency. These are the indices
 * the European desks themselves quote, and the ones a reader recognises by
 * name.
 *
 * <p>The catalog below is an index UNIVERSE, not an editorial selection: it is
 * the supersector family as its provider defines it, and every entry was
 * resolved live against onvista's own instrument search (ISIN and id printed
 * with it, so a moved id is one lookup away from repair). Nothing here maps a
 * SUBJECT onto a sector - that judgement stays where it belongs, with the
 * branch board that reads an issuer's own industry label.
 */
@Singleton
public class StoxxSectorClient extends OnvistaApi {

    private static final Logger LOG = LoggerFactory.getLogger(StoxxSectorClient.class);

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /**
     * One catalogued supersector.
     *
     * @param isin the index's identity of record
     * @param id   onvista's entity value - the address the bulk quote needs
     * @param name the index as its provider names it
     */
    record Supersektor(String isin, String id, String name) {}

    /** The nineteen supersectors onvista carries, resolved live 2026-08-11. */
    static final List<Supersektor> KATALOG = List.of(
            new Supersektor("EU0009658681", "193781", "Automobiles & Parts"),
            new Supersektor("EU0009658806", "193793", "Banks"),
            new Supersektor("EU0009658624", "193775", "Basic Resources"),
            new Supersektor("EU0009658608", "193773", "Chemicals"),
            new Supersektor("EU0009658889", "193799", "Construction & Materials"),
            new Supersektor("EU0009658848", "193797", "Financial Services"),
            new Supersektor("EU0009658749", "193787", "Food & Beverage"),
            new Supersektor("EU0009658723", "193785", "Health Care"),
            new Supersektor("EU0009658905", "193801", "Industrial Goods & Services"),
            new Supersektor("EU0009658822", "193795", "Insurance"),
            new Supersektor("EU0009658640", "193777", "Media"),
            new Supersektor("EU0009658780", "193791", "Oil & Gas"),
            new Supersektor("CH0019112330", "10529422", "Personal & Household Goods"),
            new Supersektor("CH0043274395", "24856237", "Real Estate"),
            new Supersektor("CH0019112553", "10529426", "Retail"),
            new Supersektor("EU0009658921", "193803", "Technology"),
            new Supersektor("EU0009658947", "193805", "Telecommunications"),
            new Supersektor("CH0019112744", "10529430", "Travel & Leisure"),
            new Supersektor("EU0009658962", "193807", "Utilities"));

    /**
     * One supersector's reading.
     *
     * @param name    the index name
     * @param isin    its ISIN, so a reference can be followed
     * @param stand   last index level
     * @param tagPct  day performance in percent, NaN when the index did not quote
     * @param jahrPct one-year performance in percent, or NaN
     * @param rang    place on the day's board, 1 = strongest
     */
    public record Sektorstand(String name, String isin, double stand, double tagPct,
            double jahrPct, int rang) {}

    /** The board of one moment, strongest first. */
    public record Board(List<Sektorstand> sektoren, Instant stand) {

        public static final Board LEER = new Board(List.of(), null);

        public boolean isEmpty() {
            return sektoren.isEmpty();
        }
    }

    private volatile Board cached = Board.LEER;
    private volatile long cachedAtMs;

    /** Test/default: plain direct transport - the bulk endpoint carries no wall. */
    public StoxxSectorClient() {
        this(new de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher());
    }

    @Inject
    public StoxxSectorClient(@DirectFirst WebFetcher fetcher) {
        super(fetcher);
    }

    /**
     * The whole European supersector board. ONE POST for all nineteen; empty
     * when the endpoint could not be reached (never cached).
     */
    public Board board() {
        Board hit = cached;
        if (!hit.isEmpty() && System.currentTimeMillis() - cachedAtMs < CACHE_TTL.toMillis()) {
            return hit;
        }
        StringBuilder body = new StringBuilder("{\"list\":[");
        for (int i = 0; i < KATALOG.size(); i++) {
            if (i > 0) body.append(',');
            body.append("{\"entityType\":\"INDEX\",\"entityValue\":\"")
                    .append(KATALOG.get(i).id()).append("\"}");
        }
        JsonNode root = postJson("v1/instruments/quote_list", body.append("]}").toString());
        if (root == null) return Board.LEER;
        List<Sektorstand> staende = new ArrayList<>(KATALOG.size());
        int i = 0;
        for (JsonNode q : listOf(root, "list")) {
            if (i >= KATALOG.size()) break;
            // The bulk reply keeps the order it was asked in - the entity
            // value rides along, so the seat is checked rather than assumed.
            Supersektor sektor = KATALOG.get(i++);
            String answered = text(q, "entityValue");
            if (answered != null && !answered.equals(sektor.id())) {
                LOG.warn("[stoxx] bulk reply out of order at {} - asked {}, got {}",
                        i - 1, sektor.id(), answered);
                continue;
            }
            staende.add(new Sektorstand(sektor.name(), sektor.isin(),
                    num(q, "last"), num(q, "performancePct"), num(q, "performance1YearPct"), 0));
        }
        if (staende.isEmpty()) {
            LOG.debug("[stoxx] bulk quote answered no supersector");
            return Board.LEER;
        }
        staende.sort(Comparator.comparingDouble(
                (Sektorstand s) -> Double.isNaN(s.tagPct()) ? Double.NEGATIVE_INFINITY : s.tagPct())
                .reversed());
        List<Sektorstand> ranked = new ArrayList<>(staende.size());
        for (int r = 0; r < staende.size(); r++) {
            Sektorstand s = staende.get(r);
            ranked.add(new Sektorstand(s.name(), s.isin(), s.stand(), s.tagPct(), s.jahrPct(),
                    r + 1));
        }
        Board board = new Board(List.copyOf(ranked), Instant.now());
        cached = board;
        cachedAtMs = System.currentTimeMillis();
        LOG.info("[stoxx] European supersector board: {} of {} indices quoted",
                ranked.size(), KATALOG.size());
        return board;
    }
}
