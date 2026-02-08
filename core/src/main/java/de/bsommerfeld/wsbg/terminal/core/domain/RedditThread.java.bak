package de.bsommerfeld.wsbg.terminal.core.domain;

public class RedditThread {
    private final String id;
    private final String subreddit;
    private final String title;
    private final String author;
    private final String textContent;
    private final long createdUtc;
    private final String permalink;
    private final int score;
    private final double upvoteRatio;
    private final int numComments;
    private final long lastActivityUtc;

    public RedditThread(String id, String subreddit, String title, String author, String textContent,
            long createdUtc, String permalink, int score, double upvoteRatio, int numComments, long lastActivityUtc) {
        this.id = id;
        this.subreddit = subreddit;
        this.title = title;
        this.author = author;
        this.textContent = textContent;
        this.createdUtc = createdUtc;
        this.permalink = permalink;
        this.score = score;
        this.upvoteRatio = upvoteRatio;
        this.numComments = numComments;
        this.lastActivityUtc = lastActivityUtc;
    }

    // Constructor for backward compatibility (defaults lastActivityUtc to
    // createdUtc)
    public RedditThread(String id, String subreddit, String title, String author, String textContent,
            long createdUtc, String permalink, int score, double upvoteRatio, int numComments) {
        this(id, subreddit, title, author, textContent, createdUtc, permalink, score, upvoteRatio, numComments,
                createdUtc);
    }

    public String getId() {
        return id;
    }

    public String getSubreddit() {
        return subreddit;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public String getTextContent() {
        return textContent;
    }

    public long getCreatedUtc() {
        return createdUtc;
    }

    public String getPermalink() {
        return permalink;
    }

    public int getScore() {
        return score;
    }

    public double getUpvoteRatio() {
        return upvoteRatio;
    }

    public int getNumComments() {
        return numComments;
    }

    public long getLastActivityUtc() {
        return lastActivityUtc;
    }

    @Override
    public String toString() {
        return String.format("RedditThread{id='%s', title='%s', comments=%d}", id, title, numComments);
    }
}
