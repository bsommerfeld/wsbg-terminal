package de.bsommerfeld.tinyreddit.model;

import java.util.List;

/**
 * A post's poll.
 *
 * @param endsUtc epoch seconds (Reddit sends milliseconds; normalised here)
 */
public record Poll(List<Option> options, int totalVotes, long endsUtc) {

    public Poll {
        options = List.copyOf(options);
    }

    /** One choice; {@code votes} is 0 while Reddit hides counts before the poll ends. */
    public record Option(String id, String text, int votes) {
    }
}
