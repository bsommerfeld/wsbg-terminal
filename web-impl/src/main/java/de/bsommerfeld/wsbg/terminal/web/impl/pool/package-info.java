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
 *       5 minutes — a repeat inquiry inside the window is answered from RAM,
 *       after it the world is asked FRESH.</li>
 * </ul>
 *
 * <p><b>URGENT — persistence debt:</b> the basin is RAM-ONLY. Both streams —
 * the collected world wire AND the live yields — are lost on every shutdown,
 * and every restart begins with an empty basin (cold start: minutes of blind
 * flight until the collectors have poured once). The basin MUST gain disk
 * persistence (write-through or snapshot-on-interval under the app-data dir,
 * reload on boot honoring each stream's clock). Until that exists, every
 * consumer has to treat a fresh boot as "the world is not in yet".
 */
package de.bsommerfeld.wsbg.terminal.web.impl.pool;
