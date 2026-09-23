package de.bsommerfeld.wsbg.terminal;

import de.bsommerfeld.wsbg.terminal.fx.Fx;
import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolkit and the injector for tests that build nodes: both start once per
 * JVM, whichever test comes first. No window is opened; the toolkit stays up
 * until the JVM ends.
 */
public final class FxToolkit {

    private FxToolkit() {
    }

    public static void boot() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
            assertTrue(started.await(10, TimeUnit.SECONDS), "toolkit did not start");
            Platform.setImplicitExit(false);
        } catch (IllegalStateException alreadyRunning) {
            // an earlier test in this JVM started it
        }
        if (!Fx.initialized()) {
            Fx.init();
        }
    }

    /** Runs the action on the FX thread and returns its result, or rethrows what it threw. */
    public static <T> T onFxThread(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }
}
