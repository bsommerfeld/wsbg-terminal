/**
 * In-memory store for Reddit threads, comments, and AI-generated headlines.
 *
 * <p>
 * Both repositories live only for the session — when the app exits, their
 * data is gone. This is by design: persisting Reddit snapshots between
 * sessions produces ghost clusters when the underlying posts have long
 * disappeared from the live feed, and headlines tied to those ghosts read
 * as authoritative but are not.
 *
 * <ul>
 * <li>{@link de.bsommerfeld.wsbg.terminal.db.RedditRepository} — threads and
 * comment trees, written by the scraper and read by the agent + UI.
 * <li>{@link de.bsommerfeld.wsbg.terminal.db.AgentRepository} — accepted AI
 * headlines and their originating cluster context.
 * </ul>
 */
package de.bsommerfeld.wsbg.terminal.db;
