package de.bsommerfeld.wsbg.terminal.canvas;

/**
 * Where a widget starts on the canvas, in logical pixels - multiples of the
 * grid pitch keep its edges between the dots.
 *
 * @param lines how many skeleton lines stand in for the content
 */
public record WidgetSpec(String id, double x, double y, double width, double height, int lines) {
}
