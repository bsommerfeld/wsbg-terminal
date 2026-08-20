package de.bsommerfeld.updater.launcher;

import java.nio.file.Path;

/**
 * Persists the advanced sheet's answer as the {@code agent.endpoint-*} keys in
 * {@code config.toml} - the same keys the setup scripts and the terminal
 * runtime read. The line surgery itself lives in {@link ConfigWriter}, shared
 * with the language and model choices.
 *
 * <p>
 * Writing the MODE last is deliberate: it is the switch everything else hangs
 * off, and a config interrupted mid-write (a crash, a full disk) then still
 * describes the managed runtime with a few unused keys beside it - never a
 * remote runtime with no address to reach.
 */
final class EndpointConfigWriter {

    private EndpointConfigWriter() {
    }

    /**
     * Writes the endpoint. Returns false (after logging) when the file cannot
     * be written - the choice still drives THIS run via the env var; only
     * persistence for the next start is lost.
     */
    static boolean write(Path appDir, AdvancedEndpointSheet.Endpoint endpoint, SessionLog log) {
        boolean ok = ConfigWriter.write(appDir, "[agent]", "agent.endpoint-url",
                endpoint.url(), log);
        ok &= ConfigWriter.write(appDir, "[agent]", "agent.endpoint-model",
                endpoint.model(), log);
        ok &= ConfigWriter.write(appDir, "[agent]", "agent.endpoint-auth",
                endpoint.auth(), log);
        ok &= ConfigWriter.write(appDir, "[agent]", "agent.endpoint-api",
                endpoint.api(), log);
        ok &= ConfigWriter.write(appDir, "[agent]", "agent.endpoint-mode", "remote", log);
        return ok;
    }
}
