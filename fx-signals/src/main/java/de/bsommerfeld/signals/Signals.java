package de.bsommerfeld.signals;

import java.util.Objects;

/**
 * The entry point for whoever creates signalling instances - an injector, a
 * view framework: which class to instantiate, and how to connect the instance.
 *
 * <pre>{@code
 * CalculatorViewModel model = injector.getInstance(Signals.implementationOf(CalculatorViewModel.class));
 * Signals.connect(model, scope);
 * }</pre>
 */
public final class Signals {

    /** The generated class beside the owner: {@code Owner} → {@code OwnerSignals}. */
    static final String GENERATED_SUFFIX = "Signals";
    /** The generated subclass, nested in the generated class. */
    static final String IMPLEMENTATION_NAME = "Emitting";

    private static final ClassValue<Class<?>> IMPLEMENTATIONS = new ClassValue<>() {
        @Override
        protected Class<?> computeValue(Class<?> type) {
            return findImplementation(type);
        }
    };

    private Signals() {
    }

    /**
     * The class to instantiate for {@code type}: the generated subclass that
     * reports signal calls if {@code type} declares signals, {@code type}
     * itself otherwise.
     */
    @SuppressWarnings("unchecked")
    public static <T> Class<? extends T> implementationOf(Class<T> type) {
        return (Class<? extends T>) IMPLEMENTATIONS.get(Objects.requireNonNull(type, "type"));
    }

    /**
     * Sends {@code instance}'s signals to {@code emitter}. Does nothing for an
     * instance that declares no signals.
     */
    public static void connect(Object instance, SignalEmitter emitter) {
        Objects.requireNonNull(emitter, "emitter");
        if (instance instanceof Signaling signaling) {
            signaling.connectSignals(emitter);
        }
    }

    /**
     * {@code a.b.Outer$Inner} → {@code a.b.Outer_InnerSignals$Emitting}. The
     * processor names what it generates the same way.
     */
    static String implementationName(Class<?> type) {
        String packageName = type.getPackageName();
        String packagePrefix = packageName.isEmpty() ? "" : packageName + ".";
        String flatName = type.getName().substring(packagePrefix.length()).replace('$', '_');
        return packagePrefix + flatName + GENERATED_SUFFIX + "$" + IMPLEMENTATION_NAME;
    }

    private static Class<?> findImplementation(Class<?> type) {
        try {
            Class<?> generated = Class.forName(implementationName(type), false, type.getClassLoader());
            return type.isAssignableFrom(generated) ? generated : type;
        } catch (ClassNotFoundException e) {
            return type;
        }
    }
}
