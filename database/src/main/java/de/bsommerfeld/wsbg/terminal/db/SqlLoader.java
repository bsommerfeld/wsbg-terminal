package de.bsommerfeld.wsbg.terminal.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches SQL statements from classpath resource files under
 * {@code sql/}. This class is the database module's counterpart to
 * {@code PromptLoader} in the agent module — same pattern, different domain.
 *
 * <p>
 * Externalizing SQL into individual {@code .sql} files has two benefits:
 * <ul>
 * <li>SQL is syntax-highlighted and lintable in IDEs without string
 * escaping</li>
 * <li>Schema-related queries live next to {@code schema.sql}, making the
 * data model self-documenting</li>
 * </ul>
 *
 * <p>
 * Each file is read exactly once and cached for the lifetime of the JVM.
 * The naming convention is {@code sql/<operation>-<entity>.sql},
 * e.g. {@code upsert-thread.sql}, {@code select-comments-for-thread.sql}.
 *
 * @see SqlDatabaseService
 */
public final class SqlLoader {

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private SqlLoader() {
    }

    /**
     * Returns the SQL statement from {@code sql/<name>.sql} on the classpath.
     * The result is trimmed and cached — subsequent calls with the same name
     * return the cached copy without disk I/O.
     *
     * @param name the file stem without path prefix or extension
     * @return the SQL string, ready for {@link java.sql.PreparedStatement} use
     * @throws IllegalStateException if the resource is missing or unreadable
     */
    public static String load(String name) {
        return CACHE.computeIfAbsent(name, SqlLoader::readResource);
    }

    private static String readResource(String name) {
        String path = "sql/" + name + ".sql";
        try (InputStream in = SqlLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("SQL resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read SQL resource: " + path, e);
        }
    }
}
