package de.bsommerfeld.wsbg.terminal.db;

/**
 * A single named instrument referenced inside a headline.
 *
 * <p>
 * A headline can carry zero, one, or many of these — multi-ticker
 * sentences like "CrowdStrike, Palo Alto Networks und Zscaler vor
 * Earnings" produce three subjects, each with its own visible name
 * and ticker symbol.
 *
 * <p>
 * The UI uses {@link #name()} as the substring to wrap with the
 * glow + hover-flip animation and {@link #ticker()} as the text
 * the flip transforms into (and the value copied to clipboard on
 * click). The agent must guarantee that {@code name} appears verbatim
 * in the headline text — otherwise the wrap silently no-ops.
 */
public record HeadlineSubject(String name, String ticker) {
}
