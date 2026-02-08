package de.bsommerfeld.wsbg.terminal.db;

import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Scanner;

@Singleton
public class DatabaseService {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseService.class);
    private final String dbUrl;

    public DatabaseService() {
        // Resolve DB Path via StorageUtils
        java.nio.file.Path appData = de.bsommerfeld.wsbg.terminal.core.util.StorageUtils.getAppDataDir("wsbg-terminal");
        try {
            if (!java.nio.file.Files.exists(appData)) {
                java.nio.file.Files.createDirectories(appData);
            }
        } catch (Exception e) {
            LOG.error("Failed to create app data directory", e);
        }
        this.dbUrl = "jdbc:sqlite:" + appData.resolve("wsbg-terminal.db").toAbsolutePath().toString();
        initialize();
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    private void initialize() {
        LOG.info("Initializing Database at {}", dbUrl);
        try (Connection conn = getConnection()) {

            // Apply Schema (Creates tables if not exist)
            applySchema(conn);

        } catch (SQLException e) {
            LOG.error("Failed to initialize database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    private void applySchema(Connection conn) throws SQLException {
        try (InputStream schemaStream = getClass().getClassLoader().getResourceAsStream("schema.sql");
                Statement stmt = conn.createStatement()) {

            if (schemaStream == null) {
                LOG.error("Schema.sql not found in classpath!");
                return;
            }

            String schemaSql = new String(schemaStream.readAllBytes(), StandardCharsets.UTF_8);
            String[] statements = schemaSql.split(";\\s*(\\r?\\n|$)");

            conn.setAutoCommit(false);
            for (String sql : statements) {
                if (!sql.trim().isEmpty()) {
                    LOG.debug("Executing: {}", sql.trim());
                    try {
                        stmt.execute(sql.trim());
                    } catch (SQLException ex) {
                        if (!ex.getMessage().contains("exists")) {
                            LOG.warn("Schema execution warning: {}", ex.getMessage());
                        }
                    }
                }
            }
            conn.commit();
            LOG.info("Database schema applied.");

        } catch (Exception e) {
            LOG.error("Error applying schema", e);
            conn.rollback();
            throw new SQLException("Schema application failed", e);
        }
    }

    public void saveThread(de.bsommerfeld.wsbg.terminal.core.domain.RedditThread thread) {
        saveThreadsBatch(java.util.Collections.singletonList(thread));
    }

    public void saveThreadsBatch(java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditThread> threads) {
        if (threads == null || threads.isEmpty())
            return;

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Prepared Statements
                String sqlThread = "INSERT OR REPLACE INTO reddit_threads (id, subreddit, title, author, permalink, score, upvote_ratio, created_utc, fetched_at, last_activity_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                String sqlContent = "INSERT OR REPLACE INTO reddit_contents (entity_id, body) VALUES (?, ?)";
                String sqlDelImg = "DELETE FROM reddit_images WHERE entity_id = ?";
                String sqlImg = "INSERT INTO reddit_images (entity_id, image_url) VALUES (?, ?)";

                try (java.sql.PreparedStatement psThread = conn.prepareStatement(sqlThread);
                        java.sql.PreparedStatement psContent = conn.prepareStatement(sqlContent);
                        java.sql.PreparedStatement psDelImg = conn.prepareStatement(sqlDelImg);
                        java.sql.PreparedStatement psImg = conn.prepareStatement(sqlImg)) {

                    for (de.bsommerfeld.wsbg.terminal.core.domain.RedditThread thread : threads) {
                        // 1. Thread Metadata
                        psThread.setString(1, thread.getId());
                        psThread.setString(2, thread.getSubreddit());
                        psThread.setString(3, thread.getTitle());
                        psThread.setString(4, thread.getAuthor());
                        psThread.setString(5, thread.getPermalink());
                        psThread.setInt(6, thread.getScore());
                        psThread.setDouble(7, thread.getUpvoteRatio());
                        psThread.setLong(8, thread.getCreatedUtc());
                        psThread.setLong(9, System.currentTimeMillis() / 1000); // fetched_at
                        psThread.setLong(10, thread.getLastActivityUtc()); // last_activity
                        psThread.addBatch();

                        // 2. Insert Content (Body)
                        if (thread.getTextContent() != null) {
                            psContent.setString(1, thread.getId());
                            psContent.setString(2, thread.getTextContent());
                            psContent.addBatch();
                        }

                        // 3. Insert Images
                        if (thread.getImageUrl() != null && !thread.getImageUrl().isEmpty()) {
                            // First delete existing images for this thread
                            psDelImg.setString(1, thread.getId());
                            psDelImg.addBatch();

                            psImg.setString(1, thread.getId());
                            psImg.setString(2, thread.getImageUrl());
                            psImg.addBatch();
                        }
                    }

                    psThread.executeBatch();
                    psContent.executeBatch();
                    psDelImg.executeBatch();
                    psImg.executeBatch();

                    conn.commit();

                    if (threads.size() > 1) {
                        LOG.info("[INTERNAL][DB] Batch saved {} threads.", threads.size());
                    } else {
                        LOG.debug("[INTERNAL][DB] Saved/Updated thread: {}", threads.get(0).getId());
                    }

                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }

        } catch (SQLException e) {
            LOG.error("Failed to batch save threads", e);
        }
    }

    public void saveComment(de.bsommerfeld.wsbg.terminal.core.domain.RedditComment comment) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Insert Metadata
                String sql = "INSERT OR REPLACE INTO reddit_comments (id, parent_id, author, score, created_utc, fetched_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)";
                try (java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, comment.getId());
                    pstmt.setString(2, comment.getParentId());
                    pstmt.setString(3, comment.getAuthor());
                    pstmt.setInt(4, comment.getScore());
                    pstmt.setLong(5, comment.getCreatedUtc());
                    pstmt.setLong(6, comment.getFetchedAt());
                    pstmt.executeUpdate();
                }

                // 2. Insert Content
                if (comment.getBody() != null) {
                    String sqlContent = "INSERT OR REPLACE INTO reddit_contents (entity_id, body) VALUES (?, ?)";
                    try (java.sql.PreparedStatement pstmt = conn.prepareStatement(sqlContent)) {
                        pstmt.setString(1, comment.getId());
                        pstmt.setString(2, comment.getBody());
                        pstmt.executeUpdate();
                    }
                }

                // 3. Insert Images (New)
                if (comment.getImageUrls() != null && !comment.getImageUrls().isEmpty()) {
                    // Delete old images for this comment/entity
                    try (java.sql.PreparedStatement del = conn
                            .prepareStatement("DELETE FROM reddit_images WHERE entity_id = ?")) {
                        del.setString(1, comment.getId());
                        del.executeUpdate();
                    }

                    String sqlImg = "INSERT INTO reddit_images (entity_id, image_url) VALUES (?, ?)";
                    try (java.sql.PreparedStatement pstmt = conn.prepareStatement(sqlImg)) {
                        for (String url : comment.getImageUrls()) {
                            pstmt.setString(1, comment.getId());
                            pstmt.setString(2, url);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                    }
                }

                // 4. Update Thread Activity
                updateThreadActivity(conn, comment.getThreadId(), comment.getLastUpdatedUtc());

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOG.error("Failed to save comment {}", comment.getId(), e);
        }
    }

    private void updateThreadActivity(Connection conn, String threadId, long activityUtc) throws SQLException {
        // Ensure threadId is not null or "unknown" and exists in DB before updating?
        // SQLite will just do nothing if ID not found, which is fine.
        // But if the thread hasn't been saved yet (race condition), this update is
        // lost.
        // However, we save threads FIRST in scraper, then comments.
        String sql = "UPDATE reddit_threads SET last_activity_utc = ? WHERE id = ? AND last_activity_utc < ?";
        try (java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, activityUtc);
            pstmt.setString(2, threadId);
            pstmt.setLong(3, activityUtc);
            pstmt.executeUpdate();
        }
    }

    public java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditComment> getCommentsForThread(String threadId,
            int limit) {
        // Recursive Query to reconstruct thread
        // SQLite CTE
        String sql = "WITH RECURSIVE thread_tree AS ( " +
                "  SELECT id, parent_id, author, score, created_utc, fetched_at, 0 AS level " +
                "  FROM reddit_comments " +
                "  WHERE parent_id = ? " + // Start with comments directly on the thread
                "  UNION ALL " +
                "  SELECT c.id, c.parent_id, c.author, c.score, c.created_utc, c.fetched_at, tt.level + 1 " +
                "  FROM reddit_comments c " +
                "  INNER JOIN thread_tree tt ON c.parent_id = tt.id " +
                ") " +
                "SELECT tt.*, rc.body, GROUP_CONCAT(ri.image_url) as images " +
                "FROM thread_tree tt " +
                "LEFT JOIN reddit_contents rc ON tt.id = rc.entity_id " +
                "LEFT JOIN reddit_images ri ON tt.id = ri.entity_id " +
                "GROUP BY tt.id, rc.body " +
                "ORDER BY tt.created_utc DESC LIMIT ?";

        java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditComment> result = new java.util.ArrayList<>();

        try (Connection conn = getConnection();
                java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, threadId);
            pstmt.setInt(2, limit);

            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String imagesStr = rs.getString("images");
                    java.util.List<String> images = new java.util.ArrayList<>();
                    if (imagesStr != null && !imagesStr.isEmpty()) {
                        String[] split = imagesStr.split(",");
                        for (String s : split)
                            images.add(s);
                    }

                    result.add(new de.bsommerfeld.wsbg.terminal.core.domain.RedditComment(
                            rs.getString("id"),
                            threadId, // We know the threadId from context
                            rs.getString("parent_id"),
                            rs.getString("author"),
                            rs.getString("body"),
                            rs.getInt("score"),
                            rs.getLong("created_utc"),
                            rs.getLong("fetched_at"),
                            0, // last_updated_utc
                            images));
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to fetch comments for thread " + threadId, e);
        }
        return result;
    }

    public de.bsommerfeld.wsbg.terminal.core.domain.RedditThread getThread(String id) {
        String sql = "SELECT t.id, t.subreddit, t.title, t.author, t.created_utc, t.permalink, t.score, t.upvote_ratio, "
                +
                "t.last_activity_utc, c.body, i.image_url " +
                "FROM reddit_threads t " +
                "LEFT JOIN reddit_contents c ON t.id = c.entity_id " +
                "LEFT JOIN reddit_images i ON t.id = i.entity_id " +
                "WHERE t.id = ?";

        try (Connection conn = getConnection();
                java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, id);

            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    // Count comments (separate fast query)
                    int numComments = getThreadCommentCount(conn, id);

                    return new de.bsommerfeld.wsbg.terminal.core.domain.RedditThread(
                            rs.getString("id"),
                            rs.getString("subreddit"),
                            rs.getString("title"),
                            rs.getString("author"),
                            rs.getString("body"),
                            rs.getLong("created_utc"),
                            rs.getString("permalink"),
                            rs.getInt("score"),
                            rs.getDouble("upvote_ratio"),
                            numComments,
                            rs.getLong("last_activity_utc"),
                            rs.getString("image_url"));
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to fetch thread {}", id, e);
        }
        return null;
    }

    private int getThreadCommentCount(Connection conn, String threadId) {
        String sql = "WITH RECURSIVE thread_tree AS ( " +
                "  SELECT id FROM reddit_comments WHERE parent_id = ? " +
                "  UNION ALL " +
                "  SELECT c.id FROM reddit_comments c JOIN thread_tree tt ON c.parent_id = tt.id " +
                ") SELECT COUNT(*) FROM thread_tree";
        try (java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, threadId);
            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                if (rs.next())
                    return rs.getInt(1);
            }
        } catch (SQLException e) {
            // ignore
        }
        return 0;
    }

    public java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditThread> getAllThreads() {
        String sql = "SELECT t.id, t.subreddit, t.title, t.author, t.created_utc, t.permalink, t.score, t.upvote_ratio, "
                +
                "t.last_activity_utc, c.body, i.image_url " +
                "FROM reddit_threads t " +
                "LEFT JOIN reddit_contents c ON t.id = c.entity_id " +
                "LEFT JOIN reddit_images i ON t.id = i.entity_id " +
                "ORDER BY t.created_utc DESC";

        java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditThread> results = new java.util.ArrayList<>();

        try (Connection conn = getConnection();
                java.sql.PreparedStatement pstmt = conn.prepareStatement(sql);
                java.sql.ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                results.add(new de.bsommerfeld.wsbg.terminal.core.domain.RedditThread(
                        rs.getString("id"),
                        rs.getString("subreddit"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getString("body"),
                        rs.getLong("created_utc"),
                        rs.getString("permalink"),
                        rs.getInt("score"),
                        rs.getDouble("upvote_ratio"),
                        0, // numComments (expensive to calc for all)
                        rs.getLong("last_activity_utc"),
                        rs.getString("image_url")));
            }
        } catch (SQLException e) {
            LOG.error("Failed to get all threads", e);
        }
        return results;
    }

    public java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditComment> getAllComments() {
        String sql = "SELECT c.id, c.parent_id, c.author, c.score, c.created_utc, c.fetched_at, rc.body, GROUP_CONCAT(ri.image_url) as images "
                +
                "FROM reddit_comments c " +
                "LEFT JOIN reddit_contents rc ON c.id = rc.entity_id " +
                "LEFT JOIN reddit_images ri ON c.id = ri.entity_id " +
                "GROUP BY c.id, rc.body " +
                "ORDER BY c.created_utc DESC";

        java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditComment> result = new java.util.ArrayList<>();
        try (Connection conn = getConnection();
                java.sql.PreparedStatement pstmt = conn.prepareStatement(sql);
                java.sql.ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                String imagesStr = rs.getString("images");
                java.util.List<String> images = new java.util.ArrayList<>();
                if (imagesStr != null && !imagesStr.isEmpty()) {
                    String[] split = imagesStr.split(",");
                    for (String s : split)
                        images.add(s);
                }

                result.add(new de.bsommerfeld.wsbg.terminal.core.domain.RedditComment(
                        rs.getString("id"),
                        null, // Unknown Thread ID
                        rs.getString("parent_id"),
                        rs.getString("author"),
                        rs.getString("body"),
                        rs.getInt("score"),
                        rs.getLong("created_utc"),
                        rs.getLong("fetched_at"),
                        0,
                        images));
            }
        } catch (SQLException e) {
            LOG.error("Failed to load all comments", e);
        }
        return result;
    }

    public java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditThread> getRecentThreads(int limit) {
        String sql = "SELECT t.id, t.subreddit, t.title, t.author, t.created_utc, t.permalink, t.score, t.upvote_ratio, "
                +
                "t.last_activity_utc, c.body, i.image_url " +
                "FROM reddit_threads t " +
                "LEFT JOIN reddit_contents c ON t.id = c.entity_id " +
                "LEFT JOIN reddit_images i ON t.id = i.entity_id " +
                "ORDER BY t.last_activity_utc DESC LIMIT ?";

        java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditThread> result = new java.util.ArrayList<>();
        try (Connection conn = getConnection();
                java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new de.bsommerfeld.wsbg.terminal.core.domain.RedditThread(
                            rs.getString("id"),
                            rs.getString("subreddit"),
                            rs.getString("title"),
                            rs.getString("author"),
                            rs.getString("body"),
                            rs.getLong("created_utc"),
                            rs.getString("permalink"),
                            rs.getInt("score"),
                            rs.getDouble("upvote_ratio"),
                            0, // numComments
                            rs.getLong("last_activity_utc"),
                            rs.getString("image_url")));
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to fetch recent reddit threads", e);
        }
        return result;
    }

    public int cleanupOldThreads(long maxAgeSeconds) {
        long cutoff = (System.currentTimeMillis() / 1000) - maxAgeSeconds;
        LOG.info("Cleaning up threads inactive since {}", java.time.Instant.ofEpochSecond(cutoff));

        String queryOldThreads = "SELECT id FROM reddit_threads WHERE last_activity_utc < ?";
        int totalDeletedThreads = 0;

        try (Connection conn = getConnection();
                java.sql.PreparedStatement pstmt = conn.prepareStatement(queryOldThreads)) {

            pstmt.setLong(1, cutoff);

            java.util.List<String> threadsToDelete = new java.util.ArrayList<>();
            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    threadsToDelete.add(rs.getString("id"));
                }
            }

            if (threadsToDelete.isEmpty()) {
                LOG.info("No old threads to clean.");
                return 0;
            }

            conn.setAutoCommit(false);
            try {
                int deletedComments = 0;

                for (String tid : threadsToDelete) {
                    // 1. Find all related entity IDs (The thread itself + all recursive comments)
                    java.util.Set<String> allEntityIds = new java.util.HashSet<>();
                    allEntityIds.add(tid); // The thread itself

                    // Recursive CTE to get all descendants
                    String fetchHierarchy = "WITH RECURSIVE thread_tree AS ( " +
                            "  SELECT id FROM reddit_comments WHERE parent_id = ? " +
                            "  UNION ALL " +
                            "  SELECT c.id FROM reddit_comments c " +
                            "  INNER JOIN thread_tree tt ON c.parent_id = tt.id " +
                            ") SELECT id FROM thread_tree";

                    try (java.sql.PreparedStatement pTree = conn.prepareStatement(fetchHierarchy)) {
                        pTree.setString(1, tid);
                        try (java.sql.ResultSet rsTree = pTree.executeQuery()) {
                            while (rsTree.next()) {
                                allEntityIds.add(rsTree.getString("id"));
                                deletedComments++;
                            }
                        }
                    }

                    // 2. Delete Content & Images for ALL determined IDs
                    if (!allEntityIds.isEmpty()) {
                        for (String entityId : allEntityIds) {
                            try (java.sql.PreparedStatement delContent = conn
                                    .prepareStatement("DELETE FROM reddit_contents WHERE entity_id = ?");
                                    java.sql.PreparedStatement delImages = conn
                                            .prepareStatement("DELETE FROM reddit_images WHERE entity_id = ?")) {
                                delContent.setString(1, entityId);
                                delContent.executeUpdate();
                                delImages.setString(1, entityId);
                                delImages.executeUpdate();
                            }
                        }
                    }

                    // 3. Delete Comments
                    for (String entityId : allEntityIds) {
                        if (entityId.equals(tid))
                            continue; // Skip thread
                        try (java.sql.PreparedStatement delComm = conn
                                .prepareStatement("DELETE FROM reddit_comments WHERE id = ?")) {
                            delComm.setString(1, entityId);
                            delComm.executeUpdate();
                        }
                    }

                    // 4. Delete Thread
                    try (java.sql.PreparedStatement delThread = conn
                            .prepareStatement("DELETE FROM reddit_threads WHERE id = ?")) {
                        delThread.setString(1, tid);
                        delThread.executeUpdate();
                    }
                    totalDeletedThreads++;
                }

                conn.commit();
                LOG.info("Cleanup complete. Deleted {} threads and {} comments.", totalDeletedThreads, deletedComments);

            } catch (SQLException e) {
                conn.rollback();
                LOG.error("Transaction failed during cleanup", e);
            }

        } catch (SQLException e) {
            LOG.error("Failed to execute cleanup", e);
        }
        return totalDeletedThreads;
    }
}
