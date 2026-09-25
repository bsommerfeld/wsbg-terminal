package de.bsommerfeld.updater;

import de.bsommerfeld.tinyupdate.handoff.Handoff;
import de.bsommerfeld.updater.UpdateStatus.Failed;
import de.bsommerfeld.updater.UpdateStatus.Failure;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * One pass of the updater over a {@link Handoff}, free of any UI:
 *
 * <pre>
 * 1. Wait until the application's process has exited
 * 2. Apply the update (TinyUpdate)
 * 3. Run the post-update script, if the handoff names one
 * 4. Start the application again
 * </pre>
 *
 * Every step reports through the status consumer; the first one that fails
 * ends the pass with {@link Failed}. A pass may simply be run again - TinyUpdate
 * diffs by hash, so a second pass finishes whatever the first left undone.
 */
public final class UpdateRun {

    private final Handoff handoff;
    private final Duration exitTimeout;
    private final Consumer<UpdateStatus> status;

    /**
     * @param exitTimeout how long the application gets to exit before the
     *                    pass gives up with {@link Failure#STILL_RUNNING}
     * @param status      receives every status, on the calling thread
     */
    public UpdateRun(Handoff handoff, Duration exitTimeout, Consumer<UpdateStatus> status) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.exitTimeout = Objects.requireNonNull(exitTimeout, "exitTimeout");
        this.status = Objects.requireNonNull(status, "status");
    }

    /**
     * Runs the pass on the calling thread - never the FX thread, it blocks.
     *
     * @return whether the application was started again
     */
    public boolean run() {
        Optional<Failed> failure = awaitExit()
                .or(this::update)
                .or(this::postUpdate)
                .or(this::relaunch);
        failure.ifPresent(status);
        return failure.isEmpty();
    }

    private Optional<Failed> awaitExit() {
        status.accept(new UpdateStatus.Waiting());
        Optional<ProcessHandle> application = ProcessHandle.of(handoff.waitFor());
        if (application.isEmpty()) {
            return Optional.empty();
        }
        try {
            application.get().onExit().get(exitTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return Optional.empty();
        } catch (TimeoutException e) {
            return failed(Failure.STILL_RUNNING, handoff.applicationName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(Failure.STILL_RUNNING, handoff.applicationName());
        } catch (ExecutionException e) {
            // onExit() does not fail; should it ever, the process is as good as gone.
            return Optional.empty();
        }
    }

    private Optional<Failed> update() {
        try {
            handoff.client().update(progress -> status.accept(new UpdateStatus.Updating(progress)), 0);
            return Optional.empty();
        } catch (Exception e) {
            e.printStackTrace();
            return failed(Failure.UPDATE, message(e));
        }
    }

    /**
     * Runs on every pass, not only after files changed: a pass that is run
     * again after the script failed finds nothing left to update, and the
     * script still has to succeed once. Setup scripts are written to be run
     * again.
     */
    private Optional<Failed> postUpdate() {
        if (handoff.postUpdate() == null) {
            return Optional.empty();
        }
        status.accept(new UpdateStatus.Finishing());
        try {
            int exitCode = PostUpdateScript.run(handoff.installDirectory(), handoff.postUpdate());
            return PostUpdateScript.succeeded(exitCode)
                    ? Optional.empty()
                    : failed(Failure.POST_UPDATE, Integer.toString(exitCode));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(Failure.POST_UPDATE, message(e));
        } catch (Exception e) {
            return failed(Failure.POST_UPDATE, message(e));
        }
    }

    private Optional<Failed> relaunch() {
        status.accept(new UpdateStatus.Relaunching());
        try {
            new ProcessBuilder(handoff.relaunch())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return Optional.empty();
        } catch (Exception e) {
            return failed(Failure.RELAUNCH, message(e));
        }
    }

    private static Optional<Failed> failed(Failure failure, String detail) {
        return Optional.of(new Failed(failure, detail));
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
