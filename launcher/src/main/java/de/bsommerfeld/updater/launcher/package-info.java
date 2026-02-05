/**
 * Native launcher for the WSBG Terminal application.
 *
 * <h2>Purpose</h2>
 * This module is the <strong>only</strong> component packaged by JPackage
 * into a native executable. It has no JavaFX dependency and uses Swing
 * exclusively for the update progress window. Its responsibilities:
 * <ol>
 * <li><strong>Update</strong> — downloads the latest release artifacts
 * from GitHub via the {@code updater-core} library</li>
 * <li><strong>Environment setup</strong> — runs platform-specific scripts
 * that install prerequisites like Ollama and pull AI models</li>
 * <li><strong>Application launch</strong> — spawns the JavaFX application
 * in a separate JVM with the correct module-path and classpath</li>
 * </ol>
 *
 * <h2>Robustness contract</h2>
 * The launcher must <strong>never crash silently</strong>. Every failure
 * either recovers gracefully (launching a cached version after an update
 * failure) or presents a visible Swing error dialog before exiting.
 *
 * <h2>Class responsibilities</h2>
 * 
 * <pre>
 * LauncherMain       — entry point, orchestrates the 3-phase pipeline
 * LauncherWindow     — undecorated Swing progress UI (dark theme, ~30fps coalescing)
 * AppLauncher        — JVM process builder with dynamic main-class discovery
 * EnvironmentSetup   — runs setup scripts, parses Ollama output into structured events
 * StorageResolver    — resolves the OS-specific writable data directory
 * PathEnricher       — extends PATH for JPackage-stripped environments
 * </pre>
 *
 * <h2>Dynamic main-class resolution</h2>
 * The launcher does <strong>not</strong> hardcode the application's entry
 * point. Instead, {@code AppLauncher} scans JAR manifests in {@code lib/}
 * for a {@code Main-Class} attribute. This means renaming the application's
 * main class only requires rebuilding the terminal module — the launcher
 * binary does not need to change.
 */
package de.bsommerfeld.updater.launcher;
