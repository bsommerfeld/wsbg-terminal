/**
 * UI widgets for the modular docking workstation.
 *
 * <h2>Architectural Constraints for Widget Lifecycles</h2>
 * {@code DockWidget} instances are ephemeral UI components dynamically instantiated
 * by the {@link de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets.WidgetRegistry} 
 * when grid layouts are materialized or restored. 
 *
 * <p><strong>Do not manually instantiate background services or continuous polling threads within widget constructors.</strong> 
 * Background systems (like network scrapers or dataset observers) must remain decoupled 
 * from the transient widget lifecycle. They are to be injected via Dependency Injection 
 * (to preserve {@code @Singleton} contracts) or communicated with via Event Tunnels.
 *
 * <p>We rejected the naive approach of launching {@code ExecutorService} loops internally 
 * inside specific widgets (like custom RSS or data feeds), because recreating or 
 * restoring a widget silently spins up duplicated parallel jobs. This circumvents 
 * internal rate limits and triggers aggressive third-party WAF (Cloudflare 1015) IP 
 * rate bans on the entire workstation.
 */
package de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets;
