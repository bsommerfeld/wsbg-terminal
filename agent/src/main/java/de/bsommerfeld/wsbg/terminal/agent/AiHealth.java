package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.AiHealthEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.AiHealthEvent.State;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The AI endpoint's health, and the circuit breaker that keeps a dead one from
 * dragging the pipeline down with it.
 *
 * <h3>Why a breaker</h3>
 * {@link ChatGateway} retries a connect failure on a ~45 s backoff ladder,
 * which is right for what it was built for: our own server restarting its
 * runner (measured 2026-07-14 - a ConnectException killed four of five
 * concurrent calls within six seconds, and they all came back). It is wrong for
 * an endpoint that is simply not there. A sleeping box in the basement is not
 * transient, and every lane then pays 45 s per call to rediscover it: the
 * terminal does not fail, it goes quiet, which is the worse failure because
 * nothing says why.
 *
 * <p>So: the FIRST failure still buys the full ladder - transients deserve
 * their patience. Once it is exhausted the endpoint is marked down, and while
 * it is down the ladder is skipped: every call makes exactly one attempt, fails
 * in a connect timeout, and moves on. That single attempt is also the probe -
 * the first one that succeeds clears the state, so recovery needs no separate
 * polling thread and no guess at how often to check.
 *
 * <h3>Why it publishes</h3>
 * See {@link AiHealthEvent} - with an external endpoint, only the user can fix
 * this, so it has to reach them. Transitions only; the UI animates a state, it
 * does not want a heartbeat.
 */
@Singleton
public final class AiHealth {

    private static final Logger LOG = LoggerFactory.getLogger(AiHealth.class);

    private final ApplicationEventBus eventBus;

    /** Last published state - the transition filter. */
    private volatile AiHealthEvent current =
            new AiHealthEvent(State.OK, "", true, "");

    @Inject
    public AiHealth(ApplicationEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /** The current state, for a UI that connected after the last transition. */
    public AiHealthEvent snapshot() {
        return current;
    }

    /**
     * Whether the retry ladder should be skipped. True exactly while the
     * endpoint is known unreachable - see the class javadoc for why the first
     * failure is not enough to get here.
     */
    boolean tripped() {
        return current.state() == State.UNREACHABLE;
    }

    /** A call went through: the endpoint is alive, whatever it was before. */
    void noteOk(String endpoint, boolean managed) {
        publish(new AiHealthEvent(State.OK, endpoint, managed, ""));
    }

    /** Nothing answered, and the retry ladder is spent. */
    void noteUnreachable(String endpoint, boolean managed, String reason) {
        publish(new AiHealthEvent(State.UNREACHABLE, endpoint, managed, reason));
    }

    /** Something answered and refused - typically a model the server does not have. */
    void noteRejected(String endpoint, boolean managed, String reason) {
        publish(new AiHealthEvent(State.REJECTED, endpoint, managed, reason));
    }

    /**
     * Publishes on state change only. The endpoint address and reason are part
     * of the identity: swapping the endpoint in the settings while the old one
     * is down must reach the UI, or it keeps naming an address nobody is
     * calling any more.
     */
    private synchronized void publish(AiHealthEvent next) {
        AiHealthEvent previous = current;
        if (previous.state() == next.state()
                && previous.endpoint().equals(next.endpoint())
                && previous.reason().equals(next.reason())) {
            return;
        }
        current = next;
        if (next.state() == State.OK) {
            if (previous.state() != State.OK) {
                LOG.info("[ai-health] {} is answering again", next.endpoint());
            }
        } else {
            LOG.error("[ai-health] {} is {} - {}", next.endpoint(), next.state(), next.reason());
        }
        eventBus.post(next);
    }
}
