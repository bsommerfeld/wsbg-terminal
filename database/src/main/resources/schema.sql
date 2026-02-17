-- =============================================================================
--  WSBG Terminal — SQLite Schema (Third Normal Form)
-- =============================================================================
--
--  Four tables, one shared entity model:
--
--    reddit_threads   ← thread metadata (score, subreddit, timestamps)
--    reddit_comments  ← comment metadata + parent_id hierarchy (NO thread_id)
--    reddit_contents  ← body text for BOTH threads and comments (entity_id FK)
--    reddit_images    ← image URLs for BOTH threads and comments (entity_id FK)
--
--  Content and images are polymorphic: entity_id can reference either a
--  thread (t3_*) or a comment (t1_*). This avoids duplicating tables.
--
--  Comment trees are reconstructed via recursive CTE on parent_id — see
--  sql/select-comments-for-thread.sql and sql/collect-entity-hierarchy.sql.
--
--  All DDL uses IF NOT EXISTS — safe to re-run on every startup.
-- =============================================================================

-- Thread Metadata (no body text — that lives in reddit_contents)
CREATE TABLE IF NOT EXISTS reddit_threads (
    id TEXT PRIMARY KEY,              -- Reddit ID (t3_...)
    subreddit TEXT NOT NULL,
    title TEXT NOT NULL,              -- Metadata, not content (kept here for indexing)
    author TEXT,
    permalink TEXT,
    score INTEGER,
    upvote_ratio REAL,
    created_utc INTEGER NOT NULL,     -- Immutable; set once on first scrape
    fetched_at INTEGER NOT NULL,      -- Updated on every scrape cycle
    last_activity_utc INTEGER DEFAULT 0  -- MAX(created, newest comment); drives cleanup TTL
);
CREATE INDEX IF NOT EXISTS idx_reddit_threads_subreddit ON reddit_threads(subreddit);
CREATE INDEX IF NOT EXISTS idx_reddit_threads_created ON reddit_threads(created_utc);
CREATE INDEX IF NOT EXISTS idx_reddit_threads_last_activity ON reddit_threads(last_activity_utc);

-- Comment Metadata + Hierarchy
-- thread_id is intentionally absent — it is implied by walking parent_id
-- up to the root (a t3_ ID). This eliminates redundancy and simplifies
-- cascade deletes via recursive CTE.
CREATE TABLE IF NOT EXISTS reddit_comments (
    id TEXT PRIMARY KEY,              -- Reddit ID (t1_...)
    parent_id TEXT NOT NULL,          -- Points to thread (t3_) OR parent comment (t1_)
    author TEXT,
    score INTEGER,
    created_utc INTEGER NOT NULL,
    fetched_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reddit_comments_parent ON reddit_comments(parent_id);

-- Shared Content Table (body text for threads AND comments)
-- Separated from metadata to keep index scans on threads/comments fast —
-- body text can be multi-KB and would bloat the metadata B-trees.
CREATE TABLE IF NOT EXISTS reddit_contents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_id TEXT NOT NULL UNIQUE,   -- FK → reddit_threads.id OR reddit_comments.id
    body TEXT
);
CREATE INDEX IF NOT EXISTS idx_reddit_contents_entity ON reddit_contents(entity_id);

-- Shared Images Table (one-to-many for threads AND comments)
-- Delete-then-insert pattern used on upsert to handle image changes cleanly.
CREATE TABLE IF NOT EXISTS reddit_images (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_id TEXT NOT NULL,          -- FK → reddit_threads.id OR reddit_comments.id
    image_url TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reddit_images_entity ON reddit_images(entity_id);
