package de.bsommerfeld.updater;

import de.bsommerfeld.tinyupdate.api.UpdateProgress;

/** Where an {@link UpdateRun} stands - what the window shows. */
public sealed interface UpdateStatus {

    /** Waiting for the application to exit. */
    record Waiting() implements UpdateStatus {
    }

    /** The update itself, as TinyUpdate reports it. */
    record Updating(UpdateProgress progress) implements UpdateStatus {
    }

    /** The post-update script is running. */
    record Finishing() implements UpdateStatus {
    }

    /** Done; the application is being started again. */
    record Relaunching() implements UpdateStatus {
    }

    /** The run stopped; {@code detail} says why, in the failing part's own words. */
    record Failed(Failure failure, String detail) implements UpdateStatus {
    }

    /** The step a run failed in. */
    enum Failure {
        /** The application did not exit in time. */
        STILL_RUNNING,
        /** Fetching or applying the update. */
        UPDATE,
        /** The post-update script exited with a failure. */
        POST_UPDATE,
        /** The application could not be started again. */
        RELAUNCH
    }
}
