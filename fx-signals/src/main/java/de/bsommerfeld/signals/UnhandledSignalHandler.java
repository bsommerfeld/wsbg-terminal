package de.bsommerfeld.signals;

/**
 * What the outermost {@link SignalScope} does with a signal nobody binds.
 */
@FunctionalInterface
public interface UnhandledSignalHandler {

    void unhandled(SignalKey<?> key, Object payload);

    /**
     * Throws: a signal nobody listens to is a wiring mistake, and it is
     * cheapest to find where it is sent.
     */
    static UnhandledSignalHandler failing() {
        return (key, payload) -> {
            throw new IllegalStateException("nobody binds the signal " + key);
        };
    }
}
