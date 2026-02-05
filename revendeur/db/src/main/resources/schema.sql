-- Revendeur SQLite Schema (3NF)

-- Market Ticks
CREATE TABLE IF NOT EXISTS market_ticks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    open DECIMAL(20, 10) NOT NULL,
    high DECIMAL(20, 10) NOT NULL,
    low DECIMAL(20, 10) NOT NULL,
    close DECIMAL(20, 10) NOT NULL,
    volume INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_market_ticks_symbol_ts ON market_ticks(symbol, timestamp);

-- Reddit Threads
CREATE TABLE IF NOT EXISTS reddit_threads (
    id TEXT PRIMARY KEY, -- Reddit ID (t3_...)
    subreddit TEXT NOT NULL,
    title TEXT NOT NULL,
    author TEXT,
    text_content TEXT,
    created_utc INTEGER NOT NULL,
    permalink TEXT,
    score INTEGER,
    upvote_ratio REAL,
    num_comments INTEGER,
    fetched_at INTEGER NOT NULL,
    last_activity_utc INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_reddit_threads_subreddit ON reddit_threads(subreddit);
CREATE INDEX IF NOT EXISTS idx_reddit_threads_created ON reddit_threads(created_utc);
CREATE INDEX IF NOT EXISTS idx_reddit_threads_last_activity ON reddit_threads(last_activity_utc);

-- Reddit Comments (Linked to Threads)
CREATE TABLE IF NOT EXISTS reddit_comments (
    id TEXT PRIMARY KEY, -- Reddit ID (t1_...)
    thread_id TEXT NOT NULL,
    parent_id TEXT, -- Can be t3_ (thread) or t1_ (comment)
    author TEXT,
    body TEXT,
    score INTEGER,
    created_utc INTEGER NOT NULL,
    FOREIGN KEY(thread_id) REFERENCES reddit_threads(id)
);
CREATE INDEX IF NOT EXISTS idx_reddit_comments_thread ON reddit_comments(thread_id);




