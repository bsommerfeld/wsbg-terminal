-- WSBG Terminal SQLite Schema (3NF Refactored)

-- Reddit Threads (Metadata Only)
CREATE TABLE IF NOT EXISTS reddit_threads (
    id TEXT PRIMARY KEY, -- Reddit ID (t3_...)
    subreddit TEXT NOT NULL,
    title TEXT NOT NULL, -- Metadata essential for identification
    author TEXT,
    permalink TEXT,
    score INTEGER,
    upvote_ratio REAL,
    created_utc INTEGER NOT NULL,
    fetched_at INTEGER NOT NULL,
    last_activity_utc INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_reddit_threads_subreddit ON reddit_threads(subreddit);
CREATE INDEX IF NOT EXISTS idx_reddit_threads_created ON reddit_threads(created_utc);
CREATE INDEX IF NOT EXISTS idx_reddit_threads_last_activity ON reddit_threads(last_activity_utc);

-- Reddit Comments (Metadata + Hierarchy Only)
CREATE TABLE IF NOT EXISTS reddit_comments (
    id TEXT PRIMARY KEY, -- Reddit ID (t1_...)
    parent_id TEXT NOT NULL, -- Points to thread_id OR comment_id
    author TEXT,
    score INTEGER,
    created_utc INTEGER NOT NULL,
    fetched_at INTEGER NOT NULL
    -- thread_id removed (implied via recursive Parent)
);
CREATE INDEX IF NOT EXISTS idx_reddit_comments_parent ON reddit_comments(parent_id);

-- Content Table (Text Body for both Threads and Comments)
CREATE TABLE IF NOT EXISTS reddit_contents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_id TEXT NOT NULL UNIQUE, -- FK to reddit_threads.id OR reddit_comments.id
    body TEXT
);
CREATE INDEX IF NOT EXISTS idx_reddit_contents_entity ON reddit_contents(entity_id);

-- Images Table (One-to-Many for both Threads and Comments)
CREATE TABLE IF NOT EXISTS reddit_images (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_id TEXT NOT NULL, -- FK to reddit_threads.id OR reddit_comments.id
    image_url TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reddit_images_entity ON reddit_images(entity_id);
