package de.bsommerfeld.wsbg.terminal.core.debug;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Named live gauges for state that is otherwise package-private (digest queue
 * depth, active preps, cache sizes …). A component REGISTERS a supplier once
 * at construction (only in dev mode); the debug bridge SAMPLES on demand.
 *
 * <p>Suppliers must be cheap and thread-safe (e.g. {@code queue.size()},
 * {@code AtomicInteger.get()}); they are invoked ONLY when a debug request
 * arrives, never on a schedule. A supplier that throws is reported as
 * {@code -1} rather than breaking the sample.
 *
 * <p>Registration is only ever done behind {@code if (Debug.ENABLED)}, so a
 * shipped build registers nothing and this class never loads.
 */
public final class DebugGauges {

    private static final DebugGauges INSTANCE = new DebugGauges();

    public static DebugGauges get() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<String, LongSupplier> gauges = new ConcurrentHashMap<>();

    /** Registers (or replaces) a gauge. Names are dotted paths, e.g. {@code digest.queue.depth}. */
    public void register(String name, LongSupplier supplier) {
        if (name == null || supplier == null) return;
        gauges.put(name, supplier);
    }

    /** Samples every gauge once — sorted by name for a stable wire shape. */
    public Map<String, Long> sample() {
        Map<String, Long> out = new TreeMap<>();
        gauges.forEach((name, supplier) -> {
            long value;
            try {
                value = supplier.getAsLong();
            } catch (Throwable t) {
                value = -1L;
            }
            out.put(name, value);
        });
        return out;
    }

    /** Test seam. */
    public void reset() {
        gauges.clear();
    }

    private DebugGauges() {
    }
}
