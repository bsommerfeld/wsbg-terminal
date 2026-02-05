/**
 * Persistence layer for Reddit data — SQLite-backed in production, in-memory
 * in TEST mode.
 *
 * <h2>Architecture</h2>
 * 
 * <pre>
 *   [Agent / UI]
 *        │
 *        ▼
 *   RedditRepository   ← write-through cache, single public entry point
 *        │
 *        ▼
 *   DatabaseService    ← interface (PROD ↔ TEST swap via Guice)
 *    ┌───┴───┐
 *    │       │
 *  SqlDB   TestDB
 * </pre>
 *
 * <h2>Database Schema (Third Normal Form)</h2>
 * The schema is intentionally normalized to 3NF. Text content and images are
 * separated from metadata to avoid duplication and to keep the metadata tables
 * lean for index-heavy query patterns.
 *
 * <h3>Tables</h3>
 * 
 * <pre>
 * ┌───────────────────────────────────────────────────────────────────┐
 * │ reddit_threads (Metadata)                                        │
 * ├──────────────────┬────────────────────────────────────────────────┤
 * │ id  (PK)         │ Reddit ID (t3_...)                            │
 * │ subreddit        │ e.g. "wallstreetbets"                         │
 * │ title            │ Thread title (metadata, not content)          │
 * │ author           │ Username                                      │
 * │ permalink        │ Reddit permalink                              │
 * │ score            │ Current upvote score                          │
 * │ upvote_ratio     │ 0.0–1.0                                      │
 * │ created_utc      │ Epoch seconds, immutable                     │
 * │ fetched_at       │ Last scrape timestamp                         │
 * │ last_activity_utc│ MAX(created, latest comment) — drives cleanup │
 * └──────────────────┴────────────────────────────────────────────────┘
 *
 * ┌───────────────────────────────────────────────────────────────────┐
 * │ reddit_comments (Metadata + Hierarchy)                           │
 * ├──────────────────┬────────────────────────────────────────────────┤
 * │ id  (PK)         │ Reddit ID (t1_...)                            │
 * │ parent_id        │ FK → thread ID (t3_) OR comment ID (t1_)     │
 * │ author           │ Username                                      │
 * │ score            │ Current upvote score                          │
 * │ created_utc      │ Epoch seconds                                 │
 * │ fetched_at       │ Last scrape timestamp                         │
 * └──────────────────┴────────────────────────────────────────────────┘
 *   Note: thread_id is NOT stored — it is implied by walking the parent_id
 *   chain up to the root (a t3_ ID). This avoids redundancy and simplifies
 *   cascade deletes.
 *
 * ┌───────────────────────────────────────────────────────────────────┐
 * │ reddit_contents (Body Text — shared by threads and comments)     │
 * ├──────────────────┬────────────────────────────────────────────────┤
 * │ id  (PK, auto)   │ Surrogate key                                │
 * │ entity_id (UQ)   │ FK → reddit_threads.id OR reddit_comments.id │
 * │ body             │ Full text content                             │
 * └──────────────────┴────────────────────────────────────────────────┘
 *
 * ┌───────────────────────────────────────────────────────────────────┐
 * │ reddit_images (One-to-Many — shared by threads and comments)     │
 * ├──────────────────┬────────────────────────────────────────────────┤
 * │ id  (PK, auto)   │ Surrogate key                                │
 * │ entity_id        │ FK → reddit_threads.id OR reddit_comments.id │
 * │ image_url        │ Absolute URL                                  │
 * └──────────────────┴────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Why 3NF?</h3>
 * <ul>
 * <li><strong>Content separation</strong>: Body text can be large (multi-KB).
 * Keeping it out of the metadata tables means index scans on
 * {@code subreddit}, {@code created_utc}, and {@code last_activity_utc}
 * stay fast — they never touch the text columns.</li>
 * <li><strong>Shared entity model</strong>: Both {@code reddit_contents} and
 * {@code reddit_images} use a polymorphic {@code entity_id} column that
 * can reference either a thread or a comment. This avoids duplicating
 * content/image tables per entity type.</li>
 * <li><strong>Clean cascade deletes</strong>: When a thread is cleaned up, we
 * collect all entity IDs (thread + all descendant comments) and delete
 * their content/images in one pass.</li>
 * </ul>
 *
 * <h2>Recursive Comment Hierarchy</h2>
 * Comments form a tree via the {@code parent_id} column. Since there is no
 * explicit {@code thread_id} on comments, resolving all comments for a thread
 * requires a recursive Common Table Expression (CTE):
 *
 * <pre>
 * WITH RECURSIVE thread_tree AS (
 *     -- Anchor: direct children of the thread
 *     SELECT id FROM reddit_comments WHERE parent_id = ?
 *     UNION ALL
 *     -- Recurse: children of children
 *     SELECT c.id FROM reddit_comments c
 *     INNER JOIN thread_tree tt ON c.parent_id = tt.id
 * )
 * SELECT id FROM thread_tree
 * </pre>
 *
 * This CTE is used in two contexts:
 * <ul>
 * <li>{@code select-comments-for-thread.sql} — fetches comments with content
 * and images JOINed, for display in the sidebar</li>
 * <li>{@code collect-entity-hierarchy.sql} — collects bare IDs for
 * cascade deletion during cleanup</li>
 * </ul>
 *
 * <h2>SQL File Inventory</h2>
 * All SQL statements are externalized to {@code sql/*.sql}, loaded via
 * {@link SqlLoader}:
 * <ul>
 * <li>{@code upsert-thread.sql} — INSERT OR UPDATE thread metadata</li>
 * <li>{@code upsert-content.sql} — INSERT OR UPDATE body text</li>
 * <li>{@code insert-comment.sql} — INSERT OR IGNORE comment metadata</li>
 * <li>{@code insert-image.sql} — INSERT image URL</li>
 * <li>{@code delete-images.sql} — DELETE images by entity_id (before
 * re-insert)</li>
 * <li>{@code delete-content.sql} — DELETE content by entity_id</li>
 * <li>{@code delete-comment.sql} — DELETE comment by id</li>
 * <li>{@code delete-thread.sql} — DELETE thread by id</li>
 * <li>{@code update-thread-activity.sql} — advance last_activity_utc</li>
 * <li>{@code select-thread.sql} — single thread with JOINed content/image</li>
 * <li>{@code select-all-threads.sql} — all threads (metadata only)</li>
 * <li>{@code select-recent-threads.sql} — recent threads by activity</li>
 * <li>{@code select-comments-for-thread.sql} — recursive CTE join</li>
 * <li>{@code select-all-comments.sql} — all comments (flat, for graph
 * view)</li>
 * <li>{@code select-old-thread-ids.sql} — threads past retention cutoff</li>
 * <li>{@code collect-entity-hierarchy.sql} — recursive CTE for cascade
 * delete</li>
 * <li>{@code count-thread-comments.sql} — comment count for a single
 * thread</li>
 * </ul>
 */
package de.bsommerfeld.wsbg.terminal.db;
