INSERT INTO reddit_threads (id, subreddit, title, author, permalink,
    score, upvote_ratio, created_utc, fetched_at, last_activity_utc)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(id) DO UPDATE SET
    subreddit=excluded.subreddit, title=excluded.title, author=excluded.author,
    permalink=excluded.permalink, score=excluded.score, upvote_ratio=excluded.upvote_ratio,
    created_utc=excluded.created_utc, fetched_at=excluded.fetched_at,
    last_activity_utc=MAX(reddit_threads.last_activity_utc, excluded.last_activity_utc)
