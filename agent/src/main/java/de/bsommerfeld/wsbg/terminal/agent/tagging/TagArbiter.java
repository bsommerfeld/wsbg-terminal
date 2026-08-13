package de.bsommerfeld.wsbg.terminal.agent.tagging;

import java.util.List;
import java.util.Optional;

/**
 * The model's ONE seat in the tagging engine: it does not judge articles, it
 * judges SENSES — "articles whose context looks like these terms: are they
 * about this instrument?" — once per (instrument, sense cluster), cached
 * forever after. That is what keeps the shared two-permit LLM gate affordable:
 * the deterministic stages decide the mass, the model decides the ambiguity
 * classes, never the stream.
 *
 * <p>Implementations sit behind the agent's gate machinery; the engine only
 * sees this contract. Fail semantics: {@link Optional#empty()} = abstain (the
 * deterministic outcome stands, nothing is cached), never a guess.
 */
public interface TagArbiter {

    /**
     * Whether articles whose context is characterised by {@code clusterTerms}
     * (with {@code sampleTitles} as concrete evidence) are about the
     * instrument described by {@code instrumentLine}.
     */
    Optional<Boolean> senseAboutInstrument(String instrumentLine, List<String> clusterTerms,
            List<String> sampleTitles);
}
