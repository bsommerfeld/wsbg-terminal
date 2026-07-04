package de.bsommerfeld.updater.api;

/**
 * The closed set of phase keys an {@link UpdateClient} may put into
 * {@link UpdateProgress#phase()}. Each constant carries the exact English
 * token literal that both the emitter (the update pipeline) and the launcher's
 * i18n layer key their mapping off of — making what used to be an implicit
 * stringly-typed contract an explicit, single-source-of-truth enum.
 *
 * <p>The token strings must stay byte-for-byte stable: they are the keys the
 * launcher's {@code LauncherI18n} maps to localized labels. Rename a constant
 * freely, but never a {@link #token()}.
 */
public enum UpdatePhase {

    CHECKING("Checking for updates"),
    UP_TO_DATE("Up to date"),
    UPDATE_COMPLETE("Update complete"),
    DOWNLOADING_UPDATE("Downloading update"),
    DOWNLOADING_DEPENDENCIES("Downloading dependencies"),
    EXTRACTING_FILES("Extracting files"),
    EXTRACTING_DEPENDENCIES("Extracting dependencies"),
    VERIFYING_INTEGRITY("Verifying integrity");

    private final String token;

    UpdatePhase(String token) {
        this.token = token;
    }

    /** The exact phase-key literal placed into {@link UpdateProgress#phase()}. */
    public String token() {
        return token;
    }
}
