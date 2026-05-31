package de.bsommerfeld.wsbg.terminal.core.event;

/**
 * Cross-module events bridging the agent layer and the UI layer.
 * Only events that both modules need to produce or consume belong here.
 */
public class ControlEvents {

    public record TriggerAgentAnalysisEvent(String prompt) {
    }

    public record LogEvent(String message, String type) {
        public LogEvent(String message) {
            this(message, "INFO");
        }
    }

    /**
     * Fired when the UI language is changed. Components update their localized
     * strings.
     */
    public record LanguageChangedEvent() {
    }

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
}
