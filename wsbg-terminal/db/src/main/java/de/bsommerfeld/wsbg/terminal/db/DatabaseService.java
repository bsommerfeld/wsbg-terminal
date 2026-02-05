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

            // Pre-schema migration: Ensure column exists if table exists, so indexes in
            // schema.sql don't fail
            try (Statement stmt = conn.createStatement()) {
                // Try to add the column. If it exists, this throws.
                stmt.execute("ALTER TABLE reddit_threads ADD COLUMN last_activity_utc INTEGER DEFAULT 0");
                LOG.info("Migrated schema: Added last_activity_utc column");

                // Backfill old data: If we just added it (or it existed as 0), set it to
                // created_utc
                stmt.execute("UPDATE reddit_threads SET last_activity_utc = created_utc WHERE last_activity_utc = 0");
                LOG.info("Migrated schema: Backfilled last_activity_utc");

            } catch (SQLException e) {
                // Check if it's the "duplicate column" error. If so, we might still need to
                // backfill if we missed it before.
                if (e.getMessage().contains("duplicate") || e.getMessage().contains("exists")) {
                    LOG.debug("Column last_activity_utc already exists.");
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(
                                "UPDATE reddit_threads SET last_activity_utc = created_utc WHERE last_activity_utc = 0");
                    } catch (SQLException ex) {
                        LOG.warn("Failed to backfill last_activity_utc: " + ex.getMessage());
                    }
                } else {
                    LOG.debug("Migration step skipped/failed: {}", e.getMessage());
                }
            }

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

            // Remove comments and split by semicolon
            // This regex handles standard SQL semicolons not inside quotes (simplified)
            // Ideally we'd use a parser, but for this schema it suffices.
            // Split by ';' followed by newline or end of string, ignoring empty lines.
            String[] statements = schemaSql.split(";\\s*(\\r?\\n|$)");

            conn.setAutoCommit(false);
            for (String sql : statements) {
                if (!sql.trim().isEmpty()) {
                    LOG.debug("Executing: {}", sql.trim());
                    stmt.execute(sql.trim());
                }
            }
            conn.commit();
            LOG.info("Database schema applied successfully.");

        } catch (Exception e) {
            LOG.error("Error applying schema", e);
            conn.rollback();
            throw new SQLException("Schema application failed", e);
        }
    }

    public void saveThread(de.bsommerfeld.wsbg.terminal.core.domain.RedditThread thread) {
        long currentLastActivity = thread.getLastActivityUtc();

        // Check existing state to determine if we should bump lastActivity
        String selectSql = "SELECT num_comments, last_activity_utc FROM reddit_threads WHERE id = ?";
        try (Connection conn = getConnection();
                java.sql.PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {

            selectStmt.setString(1, thread.getId());
            try (java.sql.ResultSet rs = selectStmt.executeQuery()) {
                if (rs.next()) {
                    int oldComments = rs.getInt("num_comments");
                    long oldLastActivity = rs.getLong("last_activity_utc");

                    if (thread.getNumComments() > oldComments) {
                        // New comments -> Bump activity to NOW
                        currentLastActivity = System.currentTimeMillis() / 1000;
                    } else {
                        // No new activity -> Keep old activity or use created if 0
                        currentLastActivity = oldLastActivity > 0 ? oldLastActivity : thread.getCreatedUtc();
                    }
                } else {
                    // New thread -> Activity is creation time
                    currentLastActivity = thread.getCreatedUtc();
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to check existing thread state", e);
        }

        String sql = "INSERT OR REPLACE INTO reddit_threads (id, subreddit, title, author, text_content, created_utc, permalink, score, upvote_ratio, num_comments, fetched_at, last_activity_utc) "
                +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
                java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, thread.getId());
            pstmt.setString(2, thread.getSubreddit());
            pstmt.setString(3, thread.getTitle());
            pstmt.setString(4, thread.getAuthor());
            pstmt.setString(5, thread.getTextContent());
            pstmt.setLong(6, thread.getCreatedUtc());
            pstmt.setString(7, thread.getPermalink());
            pstmt.setInt(8, thread.getScore());
            pstmt.setDouble(9, thread.getUpvoteRatio());
            pstmt.setInt(10, thread.getNumComments());
            pstmt.setLong(11, System.currentTimeMillis() / 1000); // fetched_at
            pstmt.setLong(12, currentLastActivity);

            pstmt.executeUpdate();
            LOG.info("[INTERNAL][DB] Saved/Updated thread: {}", thread.getId());
        } catch (SQLException e) {
            LOG.error("Failed to save thread {}", thread.getId(), e);
        }
    }

    public int getThreadCommentCount(String id) {
        String sql = "SELECT num_comments FROM reddit_threads WHERE id = ?";
        try (Connection conn = getConnection();
                java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("num_comments");
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to get comment count for {}", id, e);
        }
        return -1; // Not found
    }

    /**
     * Retrieves training data records.
     */
    public java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.TrainingData> getTrainingData(int limit) {
        String sql = "SELECT features_json, target_label FROM ml_training_data ORDER BY timestamp ASC LIMIT ?";
        java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.TrainingData> result = new java.util.ArrayList<>();

        try (Connection conn = getConnection();
                java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, limit);

            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String features = rs.getString("features_json");
                    String labelStr = rs.getString("target_label");
                    int label = mapLabelToInt(labelStr);
                    result.add(new de.bsommerfeld.wsbg.terminal.core.domain.TrainingData(features, label));
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to fetch training data", e);
        }
        return result;
    }

    private int mapLabelToInt(String label) {
        if (label == null)
            return 2; // Default HOLD
        switch (label.toUpperCase()) {
            case "BUY":
                return 0;
            case "SELL":
                return 1;
            case "HOLD":
                return 2;
            default:
                return 2;
        }
    }

    public int cleanupOldThreads(long maxAgeSeconds) {
        long cutoff = (System.currentTimeMillis() / 1000) - maxAgeSeconds;
        String sql = "DELETE FROM reddit_threads WHERE last_activity_utc < ?";

        try (Connection conn = getConnection();
                java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, cutoff);
            int deleted = pstmt.executeUpdate();
            if (deleted > 0) {
                LOG.info("Cleanup: Deleted {} threads older than {} seconds (inactive since {})",
                        deleted, maxAgeSeconds, java.time.Instant.ofEpochSecond(cutoff));
            }
            return deleted;
        } catch (SQLException e) {
            LOG.error("Failed to cleanup old threads", e);
            return 0;
        }
    }

    public de.bsommerfeld.wsbg.terminal.core.domain.RedditThread getThread(String id) {
        String sql = "SELECT id, subreddit, title, author, text_content, created_utc, permalink, score, upvote_ratio, num_comments, last_activity_utc FROM reddit_threads WHERE id = ?";
        try (Connection conn = getConnection();
                java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, id);

            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String rId = rs.getString("id");
                    String subreddit = rs.getString("subreddit");
                    String title = rs.getString("title");
                    String author = rs.getString("author");
                    String textContent = rs.getString("text_content");
                    long createdUtc = rs.getLong("created_utc");
                    String permalink = rs.getString("permalink");
                    int score = rs.getInt("score");
                    double upvoteRatio = rs.getDouble("upvote_ratio");
                    int numComments = rs.getInt("num_comments");
                    long lastActivity = rs.getLong("last_activity_utc");

                    return new de.bsommerfeld.wsbg.terminal.core.domain.RedditThread(
                            rId, subreddit, title, author, textContent, createdUtc, permalink, score, upvoteRatio,
                            numComments, lastActivity);
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to fetch thread {}", id, e);
        }
        return null;
    }

    public java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditThread> getRecentThreads(int limit) {
        // Updated to use last_activity_utc for sorting
        String sql = "SELECT id, subreddit, title, author, text_content, created_utc, permalink, score, upvote_ratio, num_comments, last_activity_utc FROM reddit_threads ORDER BY last_activity_utc DESC LIMIT ?";
        java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditThread> result = new java.util.ArrayList<>();

        try (Connection conn = getConnection();
                java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, limit);

            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String subreddit = rs.getString("subreddit");
                    String title = rs.getString("title");
                    String author = rs.getString("author");
                    String textContent = rs.getString("text_content");
                    long createdUtc = rs.getLong("created_utc");
                    String permalink = rs.getString("permalink");
                    int score = rs.getInt("score");
                    double upvoteRatio = rs.getDouble("upvote_ratio");
                    int numComments = rs.getInt("num_comments");
                    long lastActivity = rs.getLong("last_activity_utc");

                    result.add(new de.bsommerfeld.wsbg.terminal.core.domain.RedditThread(
                            id, subreddit, title, author, textContent, createdUtc, permalink, score, upvoteRatio,
                            numComments, lastActivity));
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to fetch recent reddit threads", e);
        }
        return result;
    }
}
