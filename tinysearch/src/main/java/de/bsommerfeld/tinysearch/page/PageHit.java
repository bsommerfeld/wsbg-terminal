package de.bsommerfeld.tinysearch.page;

import java.net.URI;

/** One hit as a single engine's result page shows it. */
public record PageHit(URI url, String title, String snippet) {
}
