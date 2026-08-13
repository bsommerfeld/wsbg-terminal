package de.bsommerfeld.wsbg.terminal.core.debug;

/**
 * The ONE debug switch, as a JIT-foldable constant.
 *
 * <p>Every instrumentation point in the codebase is written as
 * <pre>{@code
 * if (Debug.ENABLED) {
 *     SomeRegistry.get().record(...);
 * }
 * }</pre>
 *
 * <p><b>Why a {@code static final boolean} and not a method call or a mutable
 * flag:</b> after {@code Debug}'s class initialisation, HotSpot treats the
 * field as a constant. In a shipped build ({@code ENABLED == false}) the JIT
 * compiles the guarded block away entirely — no branch, no string, no
 * allocation, no map touch, no lock. The registry classes referenced inside
 * the dead branch are never even class-loaded, because HotSpot resolves class
 * references lazily at first execution and the branch never executes.
 *
 * <p>The price is that the mode cannot change at runtime — deliberate: a
 * toggleable flag would put a real branch (and a memory read) into every hot
 * path, which is exactly what the top rule of the instrumentation layer
 * forbids.
 *
 * <p><b>Contract for instrumentation points</b> (the four rules):
 * <ol>
 *   <li>ZERO cost when off — everything behind {@code if (Debug.ENABLED)},
 *       including {@code System.nanoTime()} reads
 *       ({@code long t0 = Debug.ENABLED ? System.nanoTime() : 0L;}).</li>
 *   <li>No behaviour change — record what already happened; never fetch,
 *       never call, never reorder.</li>
 *   <li>No extended lifetimes — registries hold primitives and (length-capped)
 *       strings in bounded rings, NEVER references to domain objects.</li>
 *   <li>No new lock ordering — registry locks are leaves: a registry method
 *       never calls back out into application code.</li>
 * </ol>
 */
public final class Debug {

    /** True on developer runs ({@link DevMode}); constant for the process. */
    public static final boolean ENABLED = DevMode.active();

    private Debug() {
    }
}
