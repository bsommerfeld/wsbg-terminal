package de.bsommerfeld.tinyreddit.api;

/**
 * A result and the route it came over - which decides what its zeros mean:
 * a score of 0 via {@link Route#RSS} is "unknown", via {@link Route#JSON} it
 * is a score of 0.
 */
public record Fetched<T>(T value, Route route) {
}
