-- Recursive CTE: fetches all comments belonging to a thread, at any nesting
-- depth, with their content and images JOINed in a single pass.
--
-- The anchor selects direct replies to the thread (parent_id = ?), then
-- the recursive step descends to replies-of-replies. The result is a flat
-- list — the UI reconstructs the tree by grouping on parent_id.
--
-- Images arrive as a comma-separated string via GROUP_CONCAT; the Java
-- mapper splits them back into a List<String>.
WITH RECURSIVE thread_tree AS (
    SELECT id, parent_id, author, score, created_utc, fetched_at, 0 AS level
    FROM reddit_comments WHERE parent_id = ?
    UNION ALL
    SELECT c.id, c.parent_id, c.author, c.score, c.created_utc, c.fetched_at, tt.level + 1
    FROM reddit_comments c INNER JOIN thread_tree tt ON c.parent_id = tt.id
)
SELECT tt.*, rc.body, GROUP_CONCAT(ri.image_url) as images
FROM thread_tree tt
LEFT JOIN reddit_contents rc ON tt.id = rc.entity_id
LEFT JOIN reddit_images ri ON tt.id = ri.entity_id
GROUP BY tt.id, rc.body ORDER BY tt.created_utc DESC LIMIT ?
