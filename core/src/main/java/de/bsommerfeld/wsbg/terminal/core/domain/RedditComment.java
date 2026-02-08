package de.bsommerfeld.wsbg.terminal.core.domain;

import java.util.Collections;
import java.util.List;

public class RedditComment {
    private final String id;
    private final String threadId;
    private final String parentId;
    private final String author;
    private final String body;
    private final int score;
    private final long createdUtc;
    private final long fetchedAt;
    private final long lastUpdatedUtc;
    private final List<String> imageUrls;

    public RedditComment(String id, String threadId, String parentId, String author, String body,
            int score, long createdUtc, long fetchedAt, long lastUpdatedUtc) {
        this(id, threadId, parentId, author, body, score, createdUtc, fetchedAt, lastUpdatedUtc,
                Collections.emptyList());
    }

    public RedditComment(String id, String threadId, String parentId, String author, String body,
            int score, long createdUtc, long fetchedAt, long lastUpdatedUtc, List<String> imageUrls) {
        this.id = id;
        this.threadId = threadId;
        this.parentId = parentId;
        this.author = author;
        this.body = body;
        this.score = score;
        this.createdUtc = createdUtc;
        this.fetchedAt = fetchedAt;
        this.lastUpdatedUtc = lastUpdatedUtc;
        this.imageUrls = imageUrls != null ? imageUrls : Collections.emptyList();
    }

    public String getId() {
        return id;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getParentId() {
        return parentId;
    }

    public String getAuthor() {
        return author;
    }

    public String getBody() {
        return body;
    }

    public int getScore() {
        return score;
    }

    public long getCreatedUtc() {
        return createdUtc;
    }

    public long getFetchedAt() {
        return fetchedAt;
    }

    public long getLastUpdatedUtc() {
        return lastUpdatedUtc;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    @Override
    public String toString() {
        return String.format("RedditComment{id='%s', parent='%s', author='%s', images=%d}", id, parentId, author,
                imageUrls.size());
    }
}
