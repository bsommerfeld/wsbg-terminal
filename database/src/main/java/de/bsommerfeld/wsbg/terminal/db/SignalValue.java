package de.bsommerfeld.wsbg.terminal.db;

/**
 * One house-computed quant signal, frozen with the report it fed. The full
 * reading (definition + interpretation) lives only in the material the model
 * saw; the archive keeps the NUMBER so report runs form a time series the UI
 * can chart (entropy over days, divergence with regime shifts, ...) without
 * any separate snapshot machinery - the report archive IS the history.
 *
 * @param id             stable machine key of the signal (e.g. "attention-entropy")
 * @param title          short display title, frozen at generation time
 * @param value          the raw numeric value
 * @param formattedValue display-ready value incl. scale/unit
 */
public record SignalValue(String id, String title, double value, String formattedValue) {
}
