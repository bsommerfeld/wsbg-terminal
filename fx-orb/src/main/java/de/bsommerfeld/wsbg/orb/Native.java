package de.bsommerfeld.wsbg.orb;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/** The FFM plumbing the GL bindings share: binding a native function and calling it. */
final class Native {

    private static final Linker LINKER = Linker.nativeLinker();

    private Native() {
    }

    /** Binds the named function of a library; a {@code null} result means it returns nothing. */
    static MethodHandle downcall(SymbolLookup library, String name, MemoryLayout result, MemoryLayout... args) {
        MemorySegment symbol = library.find(name).orElseThrow(() -> new IllegalStateException("Native function missing: " + name));
        return downcall(symbol, result, args);
    }

    /** Binds the function at an address; a {@code null} result means it returns nothing. */
    static MethodHandle downcall(MemorySegment symbol, MemoryLayout result, MemoryLayout... args) {
        FunctionDescriptor descriptor = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
        return LINKER.downcallHandle(symbol, descriptor);
    }

    /** Runs a native call; the handles only throw what the native side cannot, so everything is rethrown unchecked. */
    static Object call(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
