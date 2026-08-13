package de.bsommerfeld.wsbg.terminal.core.debug;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny thread-safe bounded ring buffer — the ONLY storage shape the debug
 * layer uses for event history, so nothing held for diagnostics can ever grow
 * without bound (top rule #3).
 *
 * <p>Synchronisation is the instance monitor and it is a LEAF lock by
 * contract: no method here calls out into application code, so a caller may
 * hold any application lock while appending without creating a lock-order
 * edge (top rule #4).
 *
 * <p>Writers pay one uncontended monitor enter, two array/index writes, exit.
 * Readers copy — a snapshot never exposes the live array.
 */
public final class DebugRing<T> {

    private final Object[] buf;
    /** Index of the next write. */
    private int head;
    private int size;
    private long total;

    public DebugRing(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.buf = new Object[capacity];
    }

    public synchronized void add(T item) {
        buf[head] = item;
        head = (head + 1) % buf.length;
        if (size < buf.length) size++;
        total++;
    }

    /** Oldest-first copy of everything currently held. */
    public synchronized List<T> snapshot() {
        List<T> out = new ArrayList<>(size);
        int start = (head - size + buf.length) % buf.length;
        for (int i = 0; i < size; i++) {
            @SuppressWarnings("unchecked")
            T item = (T) buf[(start + i) % buf.length];
            out.add(item);
        }
        return out;
    }

    /** Oldest-first copy of the newest {@code limit} entries. */
    public synchronized List<T> recent(int limit) {
        int n = Math.max(0, Math.min(limit, size));
        List<T> out = new ArrayList<>(n);
        int start = (head - n + buf.length) % buf.length;
        for (int i = 0; i < n; i++) {
            @SuppressWarnings("unchecked")
            T item = (T) buf[(start + i) % buf.length];
            out.add(item);
        }
        return out;
    }

    public synchronized int size() {
        return size;
    }

    /** How many entries were EVER added — {@code total - size} have been overwritten. */
    public synchronized long totalAdded() {
        return total;
    }

    public int capacity() {
        return buf.length;
    }

    public synchronized void clear() {
        java.util.Arrays.fill(buf, null);
        head = 0;
        size = 0;
        total = 0;
    }
}
