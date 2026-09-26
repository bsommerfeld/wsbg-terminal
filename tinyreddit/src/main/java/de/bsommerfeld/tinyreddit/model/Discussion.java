package de.bsommerfeld.tinyreddit.model;

import java.util.List;

/**
 * A post with its comments, depth first: every reply follows the comment it
 * answers, so the list reads like the page and the tree is rebuilt from
 * {@link Comment#parentId()}.
 */
public record Discussion(Post post, List<Comment> comments) {

    public Discussion {
        comments = List.copyOf(comments);
    }
}
