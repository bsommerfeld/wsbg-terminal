package de.bsommerfeld.wsbg.terminal.agent.tagging;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The rolling in-RAM Lucene index over the basin's articles — the retrieval
 * and statistics substrate of the tagging engine. Lives in memory
 * ({@link ByteBuffersDirectory}) by design: the basin itself is deliberately
 * volatile (RAM-only, 25k ceiling, 15-min live TTL), so persisting its index
 * would outlive the data it describes.
 *
 * <p>What Lucene is used FOR here: phrase-with-slop candidate retrieval
 * (multi-word names in order, morphology-tolerant), term candidate retrieval,
 * and {@code docFreq} — the basin-side frequency axis of the discriminance
 * statistics. Analysis is the shared {@link TagText#analyzer()}, list-free.
 */
final class ArticleIndex implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(ArticleIndex.class);

    static final String FIELD_ID = "id";
    static final String FIELD_TEXT = "text";

    /** Hard cap on any candidate retrieval — the basin's own ceiling. */
    private static final int MAX_HITS = 30_000;

    private final ByteBuffersDirectory dir = new ByteBuffersDirectory();
    private final IndexWriter writer;
    private final SearcherManager searchers;

    ArticleIndex() {
        try {
            writer = new IndexWriter(dir, new IndexWriterConfig(TagText.analyzer()));
            writer.commit(); // an empty, openable index from second zero
            searchers = new SearcherManager(writer, null);
        } catch (IOException e) {
            // ByteBuffersDirectory does not do real I/O; this is unreachable in
            // practice and fatal if it happens — surface it.
            throw new IllegalStateException("in-memory article index failed to open", e);
        }
    }

    /** Adds (or replaces) one article's text under its basin identity. */
    void add(String id, String text) throws IOException {
        Document d = new Document();
        d.add(new StringField(FIELD_ID, id, Field.Store.YES));
        d.add(new TextField(FIELD_TEXT, text == null ? "" : text, Field.Store.NO));
        writer.updateDocument(new Term(FIELD_ID, id), d);
    }

    void delete(Collection<String> ids) throws IOException {
        for (String id : ids) {
            writer.deleteDocuments(new Term(FIELD_ID, id));
        }
    }

    /** Makes everything written so far visible to searches. */
    void refresh() {
        try {
            searchers.maybeRefreshBlocking();
        } catch (IOException e) {
            LOG.warn("article index refresh failed: {}", e.toString());
        }
    }

    int totalDocs() {
        IndexSearcher s = acquire();
        if (s == null) return 0;
        try {
            return s.getIndexReader().numDocs();
        } finally {
            release(s);
        }
    }

    /** Basin document frequency of one analyzed token. */
    int docFreq(String token) {
        IndexSearcher s = acquire();
        if (s == null) return 0;
        try {
            return s.getIndexReader().docFreq(new Term(FIELD_TEXT, token));
        } catch (IOException e) {
            return 0;
        } finally {
            release(s);
        }
    }

    /**
     * Identities of documents carrying the token sequence in order within
     * {@code slop} positions — each position tolerant of a plural/genitive
     * {@code s} suffix ("Canopys", "Nvidias"), which is suffix morphology, not
     * a word list.
     */
    Set<String> phraseDocs(List<String> tokens, int slop) {
        if (tokens == null || tokens.size() < 2) return Set.of();
        MultiPhraseQuery.Builder b = new MultiPhraseQuery.Builder();
        b.setSlop(slop);
        for (String t : tokens) {
            b.add(new Term[] {new Term(FIELD_TEXT, t), new Term(FIELD_TEXT, t + "s")});
        }
        return docsMatching(b.build());
    }

    /** Identities of documents carrying the single token (plural-tolerant). */
    Set<String> tokenDocs(String token) {
        if (token == null || token.isBlank()) return Set.of();
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(new TermQuery(new Term(FIELD_TEXT, token)), BooleanClause.Occur.SHOULD);
        b.add(new TermQuery(new Term(FIELD_TEXT, token + "s")), BooleanClause.Occur.SHOULD);
        return docsMatching(b.build());
    }

    private Set<String> docsMatching(Query q) {
        IndexSearcher searcher = acquire();
        if (searcher == null) return Set.of();
        try {
            Set<String> ids = new HashSet<>();
            searcher.search(q, new SimpleCollector() {
                private org.apache.lucene.index.LeafReaderContext ctx;

                @Override
                protected void doSetNextReader(org.apache.lucene.index.LeafReaderContext context) {
                    this.ctx = context;
                }

                @Override
                public void collect(int doc) throws IOException {
                    if (ids.size() >= MAX_HITS) return;
                    Document d = ctx.reader().storedFields().document(doc);
                    String id = d.get(FIELD_ID);
                    if (id != null) ids.add(id);
                }

                @Override
                public ScoreMode scoreMode() {
                    return ScoreMode.COMPLETE_NO_SCORES;
                }
            });
            return ids;
        } catch (IOException e) {
            LOG.warn("article index query failed: {}", e.toString());
            return Set.of();
        } finally {
            release(searcher);
        }
    }

    private IndexSearcher acquire() {
        try {
            return searchers.acquire();
        } catch (IOException e) {
            LOG.warn("article index searcher unavailable: {}", e.toString());
            return null;
        }
    }

    private void release(IndexSearcher s) {
        try {
            searchers.release(s);
        } catch (IOException ignored) {
            // in-memory release cannot fail meaningfully
        }
    }

    @Override
    public void close() throws IOException {
        searchers.close();
        writer.close();
        dir.close();
    }
}
