SELECT id, cluster_id, headline, context, created_at
FROM agent_headlines
WHERE created_at >= ?
ORDER BY created_at DESC;
