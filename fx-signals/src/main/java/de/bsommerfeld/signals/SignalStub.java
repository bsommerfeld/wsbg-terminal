package de.bsommerfeld.signals;

/**
 * Stands for one {@link Signal @Signal} method in the wiring: the method
 * {@code name} on the view {@code owner}. Made by generated code, handed out by
 * {@code signal(Owner::new).name()}.
 *
 * @param <V> the view that declares the signal
 * @param <P> the payload: the method's parameter type, or {@link Void} for a
 *            method without one
 */
public record SignalStub<V, P>(Class<V> owner, String name, SignalType type) {

    /** {@code DashboardView#checkHeadline}. */
    @Override
    public String toString() {
        return owner.getSimpleName() + "#" + name;
    }
}
