package de.bsommerfeld.wsbg.terminal.agent;

/**
 * The development kill switch for the model: with {@code WSBG_NO_AI=true} in
 * the environment this run makes no model calls at all.
 *
 * <p>It exists for one reason - the resident model is what empties a laptop
 * battery, so working on the terminal away from a socket is otherwise not
 * possible. Deliberately an environment variable and not a setting: it is a
 * property of a single dev run ({@code ./.script/run-no-ai.sh}), not a state an
 * installed terminal should be able to sit in, and nothing in the UI offers it.
 *
 * <p>Two places read it, and together they are the whole switch:
 * {@link AgentBrain#start()} never brings the server up, and {@link ChatGateway}
 * refuses every call before it reaches the wire - so a server that happens to be
 * listening anyway (an orphan from an earlier run) cannot be talked into loading
 * the model either. Everything downstream then behaves exactly as it does
 * against a dead endpoint, which is a path the pipeline is already built for:
 * the lanes go quiet, the terminal keeps running.
 */
final class AiSwitch {

    /** Read once - an environment variable cannot change under a running JVM. */
    private static final boolean OFF = "true".equalsIgnoreCase(System.getenv("WSBG_NO_AI"));

    private AiSwitch() {
    }

    /** Whether this run is to make no model calls at all. */
    static boolean off() {
        return OFF;
    }
}
