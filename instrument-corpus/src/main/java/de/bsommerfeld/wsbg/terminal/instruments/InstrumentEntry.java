package de.bsommerfeld.wsbg.terminal.instruments;

/**
 * One listed instrument in the local corpus: the ground truth the resolver's
 * identity judge can be fed instead of relying on the model's training-time
 * memory of a name. {@code isin} may be null (the SEC feed carries none),
 * {@code exchange}/{@code type} are best-effort labels from the source.
 */
public record InstrumentEntry(
        String symbol,
        String name,
        String isin,
        String exchange,
        String type,
        String source) {

    /** The judge-facing candidate line: name + the hard facts we hold. */
    public String candidateLine() {
        StringBuilder b = new StringBuilder(name);
        boolean hasType = type != null && !type.isBlank();
        boolean hasExch = exchange != null && !exchange.isBlank();
        if (hasType || hasExch) {
            b.append(" — ");
            if (hasType) b.append(type.toUpperCase(java.util.Locale.ROOT));
            if (hasType && hasExch) b.append(", ");
            if (hasExch) b.append(exchange);
        }
        return b.toString();
    }
}
