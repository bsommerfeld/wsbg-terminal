SELECT c.id, c.parent_id, c.author, c.score, c.created_utc, c.fetched_at,
    rc.body, GROUP_CONCAT(ri.image_url) as images
FROM reddit_comments c
LEFT JOIN reddit_contents rc ON c.id = rc.entity_id
LEFT JOIN reddit_images ri ON c.id = ri.entity_id
GROUP BY c.id, rc.body ORDER BY c.created_utc DESC
