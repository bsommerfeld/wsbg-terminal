package de.bsommerfeld.wsbg.terminal.core.event;

/**
 * Cross-module events bridging the agent layer and the UI layer.
 * Only events that both modules need to produce or consume belong here.
 */
public class ControlEvents {

    /**
     * Reddit scraper health state. Fired only on transitions between
     * {@code OK} and {@code DEGRADED} so the UI animates a status label
     * in/out instead of redrawing per poll. {@code degradedSinceEpochMs}
     * is {@code 0L} when the state is {@code OK}; otherwise it carries
     * the timestamp of the first failure in the current degraded run so
     * the UI can show the elapsed duration without sampling.
     *
     * <p>
     * State semantics — single anonymous-scrape endpoint today:
     * <ul>
     *   <li>{@code OK} — last scrape succeeded</li>
     *   <li>{@code DEGRADED} — at least one consecutive failure
     *       (HTTP 403/429/timeout). The label fades in.</li>
     * </ul>
     *
     * <p>
     * Future: when {@code degradedSinceEpochMs} exceeds a threshold,
     * the UI will surface a Reddit-OAuth login CTA so the user can switch
     * the scraper to authenticated mode and clear the IP-block class.
     */
    public record RedditHealthEvent(State state, long degradedSinceEpochMs) {
        public enum State { OK, DEGRADED }
    }

    /**
     * Health of the AI endpoint the terminal talks to. Fired only on
     * transitions, like the Reddit one.
     *
     * <p>
     * It exists because of the external endpoint. On our own managed instance a
     * dead server is a state the app REPAIRS - it starts one. Point the terminal
     * at a machine in the user's network and it becomes a state only THEY can
     * repair: the box is asleep, the address has a typo, the model was never
     * pulled. A log line is the wrong place for that, so it travels to the UI.
     *
     * @param state    OK, or which way it is broken
     * @param endpoint the address the failing calls went to - the single most
     *                 useful thing to show, because a typo IS the failure
     * @param managed  whether that endpoint is our own instance
     * @param reason   the server's own words (or the connection error), already
     *                 the shortest honest explanation; never localized - it is
     *                 quoted evidence, not app copy
     */
    public record AiHealthEvent(State state, String endpoint, boolean managed, String reason) {
        /**
         * {@code UNREACHABLE} - nothing answered at all (down, asleep, wrong
         * host/port, firewall). {@code REJECTED} - something answered and
         * refused the request; on a remote endpoint that is almost always a
         * model the server does not have.
         */
        public enum State { OK, UNREACHABLE, REJECTED }
    }

    /**
     * The AI endpoint settings changed and the model handles have to be rebuilt
     * against them.
     *
     * <p>An event rather than a direct call so the UI bridge that collects the
     * new values does not have to reach into the agent to apply them - the same
     * direction every other cross-module signal here takes. Rebuilding is
     * cheap (three handle objects); it is the tag VERIFICATION against the new
     * server that costs an HTTP round trip, which is why the listener does it
     * off the socket thread.
     */
    public record AiEndpointChangedEvent() {
    }
}
