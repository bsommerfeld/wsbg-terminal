package de.bsommerfeld.wsbg.terminal.reddit;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsommerfeld.wsbg.terminal.core.domain.PollData;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the {@code poll_data} object Reddit emits on poll-type posts.
 */
public final class PollParser {

    private PollParser() {}

    /**
     * Extracts the {@code poll_data} object Reddit emits on poll-type posts.
     * Shape:
     *
     * <pre>
     * "poll_data": {
     *   "options": [
     *     {"id":"abc", "text":"rot",       "vote_count": 13},
     *     {"id":"def", "text":"grün",      "vote_count": 11},
     *     {"id":"ghi", "text":"enthalten", "vote_count": 3}
     *   ],
     *   "total_vote_count": 27,
     *   "voting_end_timestamp": 1779944000000  // milliseconds
     * }
     * </pre>
     *
     * Returns {@code null} when the post isn't a poll. The
     * {@code voting_end_timestamp} is reported by Reddit in milliseconds; we
     * normalise to epoch-seconds to match the rest of the domain.
     */
    public static PollData parse(JsonNode data) {
        JsonNode poll = data.path("poll_data");
        if (poll.isMissingNode() || poll.isNull()) return null;

        JsonNode optionsNode = poll.path("options");
        if (!optionsNode.isArray() || optionsNode.isEmpty()) return null;

        List<PollData.PollOption> options = new ArrayList<>();
        for (JsonNode opt : optionsNode) {
            String text = opt.path("text").asText("").trim();
            if (text.isEmpty()) continue;
            options.add(new PollData.PollOption(
                    opt.path("id").asText(""),
                    text,
                    opt.path("vote_count").asInt(0)));
        }
        if (options.isEmpty()) return null;

        int total = poll.path("total_vote_count").asInt(0);
        // Reddit emits voting_end_timestamp in MILLISECONDS — divide.
        long endsMs = poll.path("voting_end_timestamp").asLong(0L);
        long endsSec = endsMs > 0 ? endsMs / 1000L : 0L;
        return new PollData(options, total, endsSec);
    }
}
