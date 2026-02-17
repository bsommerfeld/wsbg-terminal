package de.bsommerfeld.wsbg.terminal.db;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SQLite-backed {@link DatabaseService} for production use.
 *
 * <p>
 * All SQL lives in external {@code .sql} files loaded via {@link SqlLoader}.
 * The schema is applied from {@code schema.sql} on every startup — every DDL
 * statement uses {@code CREATE TABLE IF NOT EXISTS} so it is safe to re-run.
 *
 * <h3>Connection strategy</h3>
 * A new {@link Connection} is opened per operation and closed immediately
 * after.
 * SQLite serializes writes at the file level anyway, so pooling provides no
 * benefit. The single-threaded executor in {@link RedditRepository} ensures
 * write serialization on the Java side.
 *
 * <h3>Transaction boundaries</h3>
 * Multi-statement operations (batch inserts, comment saves, cleanup) use
 * explicit transactions with rollback-on-failure. Single-statement queries
 * use auto-commit.
 *
 * @see SqlLoader
 * @see RedditRepository
 */
@Singleton
public class SqlDatabaseService implements DatabaseService {

    private static final Logger LOG = LoggerFactory.getLogger(SqlDatabaseService.class);
    private final String dbUrl;

    public SqlDatabaseService() {
        Path appData = StorageUtils.getAppDataDir("wsbg-terminal");
        try {
            if (!Files.exists(appData))
                Files.createDirectories(appData);
        } catch (Exception e) {
            LOG.error("Failed to create app data directory", e);
        }
        this.dbUrl = "jdbc:sqlite:" + appData.resolve("wsbg-terminal.db").toAbsolutePath();
        initialize();
    }

    Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    private void initialize() {
        LOG.info("Initializing Database at {}", dbUrl);
        try (Connection conn = getConnection()) {
            applySchema(conn);
        } catch (SQLException e) {
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    /**
     * Applies the full DDL from {@code schema.sql}. Splits on semicolons and
     * executes each statement individually — existing-table warnings are
     * suppressed since {@code IF NOT EXISTS} handles idempotency.
     */
    private void applySchema(Connection conn) throws SQLException {
        try (InputStream schemaStream = getClass().getClassLoader().getResourceAsStream("schema.sql");
                Statement stmt = conn.createStatement()) {

            if (schemaStream == null) {
                LOG.error("Schema.sql not found in classpath!");
                return;
            }

            String schemaSql = new String(schemaStream.readAllBytes(), StandardCharsets.UTF_8);
            conn.setAutoCommit(false);
            for (String sql : schemaSql.split(";\\s*(\\r?\\n|$)")) {
                if (sql.trim().isEmpty())
                    continue;
                try {
                    stmt.execute(sql.trim());
                } catch (SQLException ex) {
                    if (!ex.getMessage().contains("exists")) {
                        LOG.warn("Schema execution warning: {}", ex.getMessage());
                    }
                }
            }
            conn.commit();
            LOG.info("Database schema applied.");
        } catch (Exception e) {
            conn.rollback();
            throw new SQLException("Schema application failed", e);
        }
    }

    // =====================================================================
    // Thread Operations
    // =====================================================================

    @Override
    public void saveThread(RedditThread thread) {
        saveThreadsBatch(Collections.singletonList(thread));
    }

    @Override
    public void saveThreadsBatch(List<RedditThread> threads) {
        if (threads == null || threads.isEmpty())
            return;

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                batchUpsertThreads(conn, threads);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOG.error("Failed to batch save threads", e);
        }
    }

    /**
     * Upserts threads across all four 3NF tables in a single batch:
     * {@code reddit_threads} (metadata), {@code reddit_contents} (body text),
     * {@code reddit_images} (delete-then-insert to handle image changes).
     */
    private void batchUpsertThreads(Connection conn, List<RedditThread> threads) throws SQLException {
        String sqlThread = SqlLoader.load("upsert-thread");
        String sqlContent = SqlLoader.load("upsert-content");
        String sqlDelImg = SqlLoader.load("delete-images");
        String sqlImg = SqlLoader.load("insert-image");

        try (PreparedStatement psThread = conn.prepareStatement(sqlThread);
                PreparedStatement psContent = conn.prepareStatement(sqlContent);
                PreparedStatement psDelImg = conn.prepareStatement(sqlDelImg);
                PreparedStatement psImg = conn.prepareStatement(sqlImg)) {

            for (RedditThread t : threads) {
                bindThread(psThread, t);
                psThread.addBatch();

                if (t.textContent() != null) {
                    psContent.setString(1, t.id());
                    psContent.setString(2, t.textContent());
                    psContent.addBatch();
                }

                if (t.imageUrl() != null && !t.imageUrl().isEmpty()) {
                    psDelImg.setString(1, t.id());
                    psDelImg.addBatch();
                    psImg.setString(1, t.id());
                    psImg.setString(2, t.imageUrl());
                    psImg.addBatch();
                }
            }

            psThread.executeBatch();
            psContent.executeBatch();
            psDelImg.executeBatch();
            psImg.executeBatch();
        }

        if (threads.size() > 1) {
            LOG.info("[DB] Batch saved {} threads.", threads.size());
        } else {
            LOG.debug("[DB] Saved/Updated thread: {}", threads.get(0).id());
        }
    }

    /** Binds all 10 thread parameters to the upsert prepared statement. */
    private void bindThread(PreparedStatement ps, RedditThread t) throws SQLException {
        ps.setString(1, t.id());
        ps.setString(2, t.subreddit());
        ps.setString(3, t.title());
        ps.setString(4, t.author());
        ps.setString(5, t.permalink());
        ps.setInt(6, t.score());
        ps.setDouble(7, t.upvoteRatio());
        ps.setLong(8, t.createdUtc());
        ps.setLong(9, System.currentTimeMillis() / 1000);
        ps.setLong(10, t.lastActivityUtc());
    }

    // =====================================================================
    // Comment Operations
    // =====================================================================

    /**
     * Saves a comment across all 3NF tables in a single transaction:
     * metadata → content → images → thread activity update.
     */
    @Override
    public void saveComment(RedditComment comment) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                insertComment(conn, comment);
                insertCommentContent(conn, comment);
                insertCommentImages(conn, comment);
                updateThreadActivity(conn, comment.threadId(), comment.lastUpdatedUtc());
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOG.error("Failed to save comment {}", comment.id(), e);
        }
    }

    private void insertComment(Connection conn, RedditComment c) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlLoader.load("insert-comment"))) {
            ps.setString(1, c.id());
            ps.setString(2, c.parentId());
            ps.setString(3, c.author());
            ps.setInt(4, c.score());
            ps.setLong(5, c.createdUtc());
            ps.setLong(6, c.fetchedAt());
            ps.executeUpdate();
        }
    }

    private void insertCommentContent(Connection conn, RedditComment c) throws SQLException {
        if (c.body() == null)
            return;
        try (PreparedStatement ps = conn.prepareStatement(SqlLoader.load("upsert-content"))) {
            ps.setString(1, c.id());
            ps.setString(2, c.body());
            ps.executeUpdate();
        }
    }

    private void insertCommentImages(Connection conn, RedditComment c) throws SQLException {
        if (c.imageUrls() == null || c.imageUrls().isEmpty())
            return;

        try (PreparedStatement del = conn.prepareStatement(SqlLoader.load("delete-images"))) {
            del.setString(1, c.id());
            del.executeUpdate();
        }
        String sql = SqlLoader.load("insert-image");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String url : c.imageUrls()) {
                ps.setString(1, c.id());
                ps.setString(2, url);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Advances a thread's {@code last_activity_utc} — only if the new
     * timestamp is actually newer (guarded by {@code MAX()} in the SQL).
     */
    private void updateThreadActivity(Connection conn, String threadId, long activityUtc) throws SQLException {
        String sql = SqlLoader.load("update-thread-activity");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, activityUtc);
            ps.setString(2, threadId);
            ps.setLong(3, activityUtc);
            ps.executeUpdate();
        }
    }

    // =====================================================================
    // Query Operations
    // =====================================================================

    @Override
    public RedditThread getThread(String id) {
        String sql = SqlLoader.load("select-thread");

        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapThread(rs, getThreadCommentCount(conn, id));
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to fetch thread {}", id, e);
        }
        return null;
    }

    @Override
    public List<RedditThread> getAllThreads() {
        return queryThreads(SqlLoader.load("select-all-threads"), -1);
    }

    @Override
    public List<RedditThread> getRecentThreads(int limit) {
        return queryThreads(SqlLoader.load("select-recent-threads"), limit);
    }

    private List<RedditThread> queryThreads(String sql, int limit) {
        List<RedditThread> results = new ArrayList<>();
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            if (limit > 0)
                ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    results.add(mapThread(rs, getThreadCommentCount(conn, id)));
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to query threads", e);
        }
        return results;
    }

    /**
     * Fetches comments for a thread using a recursive CTE that walks the
     * {@code parent_id} hierarchy starting from the thread ID. This resolves
     * the entire comment tree regardless of nesting depth — the 3NF schema
     * does not store {@code thread_id} on comments, so traversal is the
     * only way to discover all descendants.
     */
    @Override
    public List<RedditComment> getCommentsForThread(String threadId, int limit) {
        String sql = SqlLoader.load("select-comments-for-thread");

        List<RedditComment> result = new ArrayList<>();
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, threadId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapComment(rs, threadId));
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to fetch comments for thread {}", threadId, e);
        }
        return result;
    }

    @Override
    public List<RedditComment> getAllComments() {
        String sql = SqlLoader.load("select-all-comments");

        List<RedditComment> result = new ArrayList<>();
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapComment(rs, null));
            }
        } catch (SQLException e) {
            LOG.error("Failed to load all comments", e);
        }
        return result;
    }

    // =====================================================================
    // Cleanup
    // =====================================================================

    /**
     * Deletes stale threads and their full entity trees. The cascade works in
     * three phases per thread:
     * <ol>
     * <li>{@link #collectEntityHierarchy} — recursive CTE collects all
     * comment IDs descending from the thread</li>
     * <li>{@link #deleteEntities} — removes content and images for every
     * entity, then the comments themselves, then the thread</li>
     * </ol>
     *
     * <p>
     * The entire cleanup runs in one transaction — either all deletes
     * succeed or the database rolls back to its pre-cleanup state.
     */
    @Override
    public int cleanupOldThreads(long maxAgeSeconds) {
        long cutoff = (System.currentTimeMillis() / 1000) - maxAgeSeconds;
        LOG.info("Cleaning up threads inactive since {}", Instant.ofEpochSecond(cutoff));

        try (Connection conn = getConnection()) {
            List<String> threadsToDelete = findOldThreadIds(conn, cutoff);
            if (threadsToDelete.isEmpty()) {
                LOG.info("No old threads to clean.");
                return 0;
            }

            conn.setAutoCommit(false);
            try {
                int deletedComments = 0;
                for (String tid : threadsToDelete) {
                    Set<String> entityIds = collectEntityHierarchy(conn, tid);
                    deleteEntities(conn, entityIds);
                    deletedComments += entityIds.size() - 1;
                }
                conn.commit();
                LOG.info("Cleanup complete. Deleted {} threads and {} comments.",
                        threadsToDelete.size(), deletedComments);
                return threadsToDelete.size();
            } catch (SQLException e) {
                conn.rollback();
                LOG.error("Cleanup transaction failed", e);
            }
        } catch (SQLException e) {
            LOG.error("Failed to execute cleanup", e);
        }
        return 0;
    }

    private List<String> findOldThreadIds(Connection conn, long cutoff) throws SQLException {
        List<String> ids = new ArrayList<>();
        String sql = SqlLoader.load("select-old-thread-ids");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    ids.add(rs.getString("id"));
            }
        }
        return ids;
    }

    /**
     * Walks the comment tree rooted at {@code threadId} using the recursive
     * CTE in {@code collect-entity-hierarchy.sql}. Returns the thread ID
     * plus all descendant comment IDs — these are the entities to cascade-delete.
     */
    private Set<String> collectEntityHierarchy(Connection conn, String threadId) throws SQLException {
        Set<String> ids = new HashSet<>();
        ids.add(threadId);
        try (PreparedStatement ps = conn.prepareStatement(SqlLoader.load("collect-entity-hierarchy"))) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    ids.add(rs.getString("id"));
            }
        }
        return ids;
    }

    /**
     * Deletes all data for a set of entity IDs in the correct order:
     * content → images → comments → thread. The thread ID is assumed to be
     * the first element (iteration order of the set from
     * {@link #collectEntityHierarchy}) and is deleted last.
     */
    private void deleteEntities(Connection conn, Set<String> entityIds) throws SQLException {
        for (String entityId : entityIds) {
            try (PreparedStatement delContent = conn.prepareStatement(SqlLoader.load("delete-content"));
                    PreparedStatement delImages = conn.prepareStatement(SqlLoader.load("delete-images"))) {
                delContent.setString(1, entityId);
                delContent.executeUpdate();
                delImages.setString(1, entityId);
                delImages.executeUpdate();
            }
        }

        String threadId = entityIds.iterator().next();
        for (String entityId : entityIds) {
            if (entityId.equals(threadId))
                continue;
            try (PreparedStatement ps = conn.prepareStatement(SqlLoader.load("delete-comment"))) {
                ps.setString(1, entityId);
                ps.executeUpdate();
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(SqlLoader.load("delete-thread"))) {
            ps.setString(1, threadId);
            ps.executeUpdate();
        }
    }

    // =====================================================================
    // ResultSet → Domain Mapping
    // =====================================================================

    /**
     * Maps a thread ResultSet row to a {@link RedditThread} record. The query
     * JOINs {@code reddit_contents} and {@code reddit_images}, so body and
     * image URL are available as columns in the same row.
     */
    private RedditThread mapThread(ResultSet rs, int numComments) throws SQLException {
        return new RedditThread(
                rs.getString("id"), rs.getString("subreddit"),
                rs.getString("title"), rs.getString("author"),
                rs.getString("body"), rs.getLong("created_utc"),
                rs.getString("permalink"), rs.getInt("score"),
                rs.getDouble("upvote_ratio"), numComments,
                rs.getLong("last_activity_utc"), rs.getString("image_url"));
    }

    /**
     * Maps a comment ResultSet row to a {@link RedditComment} record. Image
     * URLs arrive as a comma-separated string from {@code GROUP_CONCAT} and
     * are split back into a list.
     */
    private RedditComment mapComment(ResultSet rs, String threadId) throws SQLException {
        List<String> images = parseImageCsv(rs.getString("images"));
        return new RedditComment(
                rs.getString("id"), threadId, rs.getString("parent_id"),
                rs.getString("author"), rs.getString("body"),
                rs.getInt("score"), rs.getLong("created_utc"),
                rs.getLong("fetched_at"), 0, images);
    }

    /** Splits a {@code GROUP_CONCAT} CSV back into individual URLs. */
    private List<String> parseImageCsv(String csv) {
        if (csv == null || csv.isEmpty())
            return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (String s : csv.split(","))
            result.add(s);
        return result;
    }

    private int getThreadCommentCount(Connection conn, String threadId) {
        String sql = SqlLoader.load("count-thread-comments");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getInt(1);
            }
        } catch (SQLException e) {
            // Count failure is non-critical — thread renders fine without it
        }
        return 0;
    }
}
