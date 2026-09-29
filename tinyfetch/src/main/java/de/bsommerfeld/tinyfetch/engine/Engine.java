package de.bsommerfeld.tinyfetch.engine;

import de.bsommerfeld.tinyfetch.api.FetchException;

/**
 * Where TinyFetch's requests are carried out: {@link EngineProcess} in
 * production, a stand-in in tests.
 */
public interface Engine extends AutoCloseable {

    /**
     * Has the engine carry out {@code request} and waits for its answer.
     *
     * @return the answer - an HTTP answer of any status, or the reason there was none
     * @throws FetchException the engine itself is not there, or did not answer in time
     */
    EngineAnswer exchange(EngineRequest request) throws FetchException, InterruptedException;

    /** Stops the engine; requests afterwards fail. */
    @Override
    void close();
}
