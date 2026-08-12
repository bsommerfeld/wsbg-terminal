/**
 * The Sammelbecken: the in-memory basin every pipeline pours into and every
 * consumer reads from. Two streams with two clocks, both deliberate
 * architecture (user decision 2026-08-12):
 *
 * <ul>
 *   <li><b>Collector entries</b> (world news on the scheduler's clock) STAY —
 *       the collectors fetch consequently instead of on demand, so the basin
 *       always carries the current world; only the size ceiling evicts.</li>
 *   <li><b>Live entries</b> (instrument fan, search engines) expire after
 *       15 minutes — a repeat inquiry inside the window is answered from RAM,
 *       after it the world is asked FRESH. Coupled to the gateway's
 *       {@code FAN_FRESH} ledger window; move one and you move both.</li>
 * </ul>
 *
 * <p><b>RAM-only is the design, not a debt</b> (user decision 2026-08-12).
 * The basin is a WINDOW on the last hours, never an archive: nothing is
 * written to disk, and every restart begins empty. That is chosen, and it has
 * one consequence every consumer must carry — a fresh boot means "the world is
 * not in yet" for the first few minutes, until the collectors have poured once.
 * Read the basin as the current state of the world, never as a record of it;
 * what needs to outlive a restart (published headlines and their sources) is
 * persisted by the agent side, not here.
 */
package de.bsommerfeld.wsbg.terminal.web.impl.pool;
