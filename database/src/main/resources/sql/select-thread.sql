SELECT t.id, t.subreddit, t.title, t.author, t.created_utc, t.permalink,
    t.score, t.upvote_ratio, t.last_activity_utc, c.body, i.image_url
FROM reddit_threads t
LEFT JOIN reddit_contents c ON t.id = c.entity_id
LEFT JOIN reddit_images i ON t.id = i.entity_id
WHERE t.id = ?
