-- Recursive CTE: collects ALL comment IDs descending from a thread.
-- Used during cleanup to find every entity that must be cascade-deleted
-- (content, images, comments) before the thread itself can be removed.
--
-- The anchor selects direct children of the thread (parent_id = ?),
-- then the recursive step walks deeper, collecting children of children.
-- Result: flat set of comment IDs (does NOT include the thread ID itself).
WITH RECURSIVE thread_tree AS (
    SELECT id FROM reddit_comments WHERE parent_id = ?
    UNION ALL
    SELECT c.id FROM reddit_comments c INNER JOIN thread_tree tt ON c.parent_id = tt.id
)
SELECT id FROM thread_tree
