package de.bsommerfeld.tinyfetch.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * A stand-in for TinyUnlock speaking its protocol: arguments in, cookie lines
 * on stdout, exit codes 0 / 1 / 3 / 4. Every call's arguments are appended to
 * a log, one line per call.
 */
final class FakeEngine {

    enum Mode {
        /** Hands out the cookie. */
        OK,
        /** Headless: CAPTCHA. Visible: the person solves it. */
        CAPTCHA,
        /** Headless: CAPTCHA. Visible: the person closes the window. */
        CLOSED,
        /** Fails with a message on stderr. */
        FAIL,
        /** Never answers. */
        HANG
    }

    static final String COOKIE = "127.0.0.1\tFALSE\t/\tFALSE\t0\tsession\tunlocked";

    private final Path script;
    private final Path log;

    private FakeEngine(Path script, Path log) {
        this.script = script;
        this.log = log;
    }

    static FakeEngine create(Path directory, Mode mode) throws IOException {
        Path log = directory.resolve("calls.log");
        Path script = directory.resolve("engine.sh");
        Files.writeString(script, """
                #!/bin/sh
                echo "$@" >> '%s'
                visible=false
                for argument in "$@"; do
                  if [ "$previous" = "--visible" ]; then visible="$argument"; fi
                  previous="$argument"
                done
                case '%s' in
                  OK) printf '%s\\n'; exit 0 ;;
                  CAPTCHA) if [ "$visible" = true ]; then printf '%s\\n'; exit 0; else exit 3; fi ;;
                  CLOSED) if [ "$visible" = true ]; then exit 4; else exit 3; fi ;;
                  FAIL) echo "tinyunlock: page never finished loading" >&2; exit 1 ;;
                  HANG) sleep 30 ;;
                esac
                """.formatted(log, mode, COOKIE.replace("\t", "\\t"), COOKIE.replace("\t", "\\t")));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return new FakeEngine(script, log);
    }

    List<String> command() {
        return List.of("/bin/sh", script.toString());
    }

    List<String> calls() throws IOException {
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }
}
