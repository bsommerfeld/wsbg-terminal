SELECT symbol, COUNT(*) as mention_count
FROM agent_ticker_mentions
WHERE created_at >= ?
GROUP BY symbol
ORDER BY mention_count DESC;
