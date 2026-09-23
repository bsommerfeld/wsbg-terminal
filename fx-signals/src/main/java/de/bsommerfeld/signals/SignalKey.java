package de.bsommerfeld.signals;

import java.util.Objects;

/**
 * Identifies one signal: the method {@link #name()} on the class
 * {@link #owner()}. What {@link SignalScope#bind} takes.
 *
 * <p>Keys are made once, by the generated {@code OwnerSignals} class, and
 * compared by identity.
 *
 * @param <P> the payload: the signal method's parameter type, or {@link Void}
 *            for a method without one
 */
public final class SignalKey<P> {

    private final Class<?> owner;
    private final String name;

    private SignalKey(Class<?> owner, String name) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.name = Objects.requireNonNull(name, "name");
    }

    /** For generated code: the key of the signal method {@code name} on {@code owner}. */
    public static <P> SignalKey<P> of(Class<?> owner, String name) {
        return new SignalKey<>(owner, name);
    }

    public Class<?> owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    /** {@code CalculatorViewModel#resultShown}. */
    @Override
    public String toString() {
        return owner.getSimpleName() + "#" + name;
    }
}
