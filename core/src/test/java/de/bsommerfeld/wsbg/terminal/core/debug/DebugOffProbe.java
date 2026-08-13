package de.bsommerfeld.wsbg.terminal.core.debug;

/**
 * Forked-JVM probe for the OFF state (started by {@link DebugOffCostTest}
 * with {@code -Dwsbg.debug=false}). It executes millions of exactly the
 * guarded collection-point shapes production uses and measures the running
 * thread's allocated bytes across them. With {@code Debug.ENABLED == false}
 * the guarded branches are dead: no allocation, no registry class load.
 *
 * <p>Exit codes: 0 = off state verified allocation-free; 2 = debug was
 * unexpectedly on; 3 = the loop allocated.
 */
final class DebugOffProbe {

    public static void main(String[] args) {
        if (Debug.ENABLED) {
            System.out.println("FAIL: Debug.ENABLED is true — this probe must run with -Dwsbg.debug=false");
            System.exit(2);
        }
        var bean = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();

        long sink = 0;
        // Warm-up: get past interpreter transitions and any lazy init.
        for (int i = 0; i < 2_000_000; i++) sink += collectionPoint(i);

        long before = bean.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < 5_000_000; i++) sink += collectionPoint(i);
        long allocated = bean.getThreadAllocatedBytes(threadId) - before;

        System.out.println("allocated=" + allocated + " bytes across 5M guarded calls (sink=" + sink + ")");
        // A dead branch allocates exactly nothing; allow a whisper of slack
        // for measurement noise.
        System.exit(allocated <= 4_096 ? 0 : 3);
    }

    /**
     * The exact production shapes: a guarded nanoTime read, a guarded record
     * with string concatenation, a guarded counter — all dead when off.
     */
    private static long collectionPoint(int i) {
        long t0 = Debug.ENABLED ? System.nanoTime() : 0L;
        if (Debug.ENABLED) {
            SourceHealthRegistry.get().record("probe", "DELIVERED", i, "note " + i);
            RedditPollDebug.get().addBucketWait(i);
            LlmDebug.get().record(Thread.currentThread().getName(), 1, 2, i, i);
        }
        return t0 + i;
    }

    private DebugOffProbe() {
    }
}
