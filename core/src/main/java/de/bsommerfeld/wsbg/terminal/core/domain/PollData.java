package de.bsommerfeld.wsbg.terminal.core.domain;

import java.util.List;

/**
 * Snapshot of a Reddit poll attached to a poll-type post.
 *
 * <p>
 * Reddit's API exposes polls on the post JSON under {@code poll_data}:
 * each option has a stable id, the visible text, and a running
 * {@code vote_count}. {@link #totalVoteCount} is the sum and
 * {@link #votingEndsAtEpoch} is the unix timestamp (seconds) when voting
 * closes — handy to know whether a poll is still live.
 *
 * <p>
 * Polls are high-signal sentiment data even at one minute old: a
 * „rot vs. grün vs. enthalten" question with 27 votes in 60 seconds
 * already tells the trader-reader where the room is leaning, long
 * before comments accumulate. The editorial agent reads polls as a
 * vote distribution, not as engagement.
 */
public record PollData(
        List<PollOption> options,
        int totalVoteCount,
        long votingEndsAtEpoch) {

    /** One option in a Reddit poll — visible text plus running vote count. */
    public record PollOption(String id, String text, int voteCount) {
    }
}
