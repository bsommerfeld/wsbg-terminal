WITH RECURSIVE thread_tree AS (
    SELECT id FROM reddit_comments WHERE parent_id = ?
    UNION ALL
    SELECT c.id FROM reddit_comments c JOIN thread_tree tt ON c.parent_id = tt.id
)
SELECT COUNT(*) FROM thread_tree
