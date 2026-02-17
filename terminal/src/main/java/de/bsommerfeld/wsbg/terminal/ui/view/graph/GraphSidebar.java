package de.bsommerfeld.wsbg.terminal.ui.view.graph;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.ui.view.LiquidGlass;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Thread detail sidebar for the graph view. Displays a full Reddit thread
 * with hierarchical comments using IntelliJ-style indent guide lines.
 * Each nesting level wraps children in a bordered VBox, creating continuous
 * vertical lines that span the entire depth group.
 */
public class GraphSidebar extends VBox {

    private final ScrollPane scrollPane;
    private final VBox contentBox;
    private boolean isOpen = false;
    private Consumer<Void> summarizeHandler;
    private Consumer<String> openUrlHandler;

    private final Map<String, Node> commentNodeMap = new HashMap<>();
    private final Map<String, Integer> replyCountMap = new HashMap<>();

    private final Button openBrowserBtn;
    private final Button summarizeBtn;
    private final Button closeBtn;

    public GraphSidebar() {
        this.getStyleClass().add("graph-sidebar");
        this.setMinWidth(0);
        this.setPrefWidth(0);
        this.setMaxWidth(0);

        openBrowserBtn = createActionButton("icon-globe");
        openBrowserBtn.setOnAction(e -> {
            if (openUrlHandler != null && currentPermalink != null) {
                openUrlHandler.accept(currentPermalink);
            }
        });

        summarizeBtn = createActionButton("icon-summarize");
        summarizeBtn.setOnAction(e -> {
            if (summarizeHandler != null) {
                summarizeHandler.accept(null);
            }
        });

        closeBtn = createActionButton("icon-sidebar-close");
        closeBtn.setOnAction(e -> close());

        contentBox = new VBox(0);
        contentBox.getStyleClass().add("graph-sidebar-content");

        scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("graph-sidebar-scroll");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // No separate header — buttons are integrated into thread content
        this.getChildren().add(scrollPane);
    }

    /** Sets the callback invoked when the “Summarize” button is pressed. */
    public void setSummarizeHandler(Consumer<Void> handler) {
        this.summarizeHandler = handler;
    }

    /** Sets the callback invoked when the “Open in Browser” button is pressed. */
    public void setOpenUrlHandler(Consumer<String> handler) {
        this.openUrlHandler = handler;
    }

    private Button createActionButton(String iconClass) {
        Button btn = new Button();
        btn.getStyleClass().add("ascii-button");
        Region icon = new Region();
        icon.getStyleClass().add(iconClass);
        btn.setGraphic(icon);
        return btn;
    }

    private String currentPermalink;

    /**
     * Populates the sidebar with a thread header and its comment tree.
     * Opens the sidebar if it was closed. If {@code scrollToCommentId} is
     * non-null the view scrolls to that comment after layout.
     */
    public void showThread(RedditThread thread, List<RedditComment> comments, String scrollToCommentId) {
        commentNodeMap.clear();
        replyCountMap.clear();
        contentBox.getChildren().clear();

        if (thread == null)
            return;

        // Store permalink for browser-open button
        String permalink = thread.permalink();
        if (permalink != null && !permalink.startsWith("http")) {
            permalink = "https://www.reddit.com" + permalink;
        }
        currentPermalink = permalink;

        Map<String, List<RedditComment>> byParent = null;
        if (comments != null && !comments.isEmpty()) {
            byParent = indexByParent(comments);
            computeReplyCounts(comments, byParent);
        }

        // Thread Header
        VBox threadHeader = new VBox(4);
        threadHeader.getStyleClass().add("graph-sidebar-thread-header");
        threadHeader.setPadding(new Insets(12, 10, 10, 14));

        // Title row: [Title] — spacer — [X]
        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.TOP_LEFT);

        Label title = new Label(thread.title());
        title.getStyleClass().add("graph-sidebar-thread-title");
        title.setWrapText(true);
        HBox.setHgrow(title, Priority.ALWAYS);

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        HBox closeContainer = new HBox(0);
        closeContainer.getStyleClass().add("sidebar-compact-controls");
        closeContainer.setAlignment(Pos.CENTER);
        closeContainer.getChildren().add(closeBtn);
        LiquidGlass.apply(closeContainer);

        titleRow.getChildren().addAll(title, titleSpacer, closeContainer);

        HBox meta = new HBox(12);
        meta.setAlignment(Pos.CENTER_LEFT);
        Label author = new Label("u/" + (thread.author() != null ? thread.author() : "[deleted]"));
        author.getStyleClass().add("graph-sidebar-meta");
        Label score = new Label("^" + thread.score());
        score.getStyleClass().add("graph-sidebar-meta");
        Label commentCount = new Label("*" + thread.numComments());
        commentCount.getStyleClass().add("graph-sidebar-meta");
        meta.getChildren().addAll(author, score, commentCount);

        // Thread's own image only — comment images belong to their respective nodes
        int imageCount = 0;
        if (thread.imageUrl() != null && !thread.imageUrl().isEmpty()) {
            imageCount++;
        }

        if (imageCount > 0) {
            Region imageIcon = new Region();
            imageIcon.getStyleClass().add("icon-image-small");

            Label imageLabel = new Label(String.valueOf(imageCount));
            imageLabel.getStyleClass().add("graph-sidebar-meta");

            HBox imageBox = new HBox(3);
            imageBox.setAlignment(Pos.CENTER_LEFT);
            imageBox.getChildren().addAll(imageIcon, imageLabel);
            meta.getChildren().add(imageBox);
        }

        threadHeader.getChildren().addAll(titleRow, meta);

        if (thread.textContent() != null && !thread.textContent().isEmpty()) {
            Label body = new Label(thread.textContent());
            body.getStyleClass().add("graph-sidebar-body");
            body.setWrapText(true);
            body.setPadding(new Insets(8, 0, 4, 0));
            threadHeader.getChildren().add(body);
        }

        // Compact action footer: [Globe | Summarize]
        HBox actionFooter = new HBox(0);
        actionFooter.getStyleClass().add("sidebar-action-footer");
        actionFooter.setAlignment(Pos.CENTER_LEFT);

        HBox actionContainer = new HBox(0);
        actionContainer.getStyleClass().add("sidebar-compact-controls");
        actionContainer.setAlignment(Pos.CENTER);
        actionContainer.getChildren().addAll(openBrowserBtn, summarizeBtn);
        LiquidGlass.apply(actionContainer);
        actionFooter.getChildren().add(actionContainer);

        threadHeader.getChildren().add(actionFooter);

        contentBox.getChildren().add(threadHeader);

        Region separator = new Region();
        separator.getStyleClass().add("graph-sidebar-separator");
        separator.setMinHeight(1);
        separator.setMaxHeight(1);
        contentBox.getChildren().add(separator);

        // Build comment hierarchy with nested VBox indent guides
        if (byParent != null) {
            List<RedditComment> topLevel = findTopLevel(comments, thread.id());
            topLevel.sort(Comparator.comparingInt(RedditComment::score).reversed());

            for (RedditComment c : topLevel) {
                contentBox.getChildren().add(buildCommentTree(c, byParent));
            }
        }

        if (!isOpen) {
            open();
        }

        if (scrollToCommentId != null) {
            Platform.runLater(() -> {
                Node target = commentNodeMap.get(scrollToCommentId);
                if (target != null) {
                    Platform.runLater(() -> {
                        scrollPane.layout();
                        double contentHeight = contentBox.getBoundsInLocal().getHeight();
                        double targetY = target.getBoundsInParent().getMinY();
                        if (contentHeight > 0) {
                            scrollPane.setVvalue(targetY / contentHeight);
                        }
                    });
                }
            });
        }
    }

    /**
     * Builds a comment node with its children nested inside a bordered VBox.
     * The children VBox uses a LEFT MARGIN (not padding) so the border itself
     * is pushed inward, aligning the guide line with the parent content.
     * Margin → border → padding → content in JavaFX layout order.
     */
    private VBox buildCommentTree(RedditComment comment, Map<String, List<RedditComment>> byParent) {
        VBox commentBlock = new VBox(0);

        // Comment content
        VBox commentContent = new VBox(2);
        commentContent.getStyleClass().add("graph-sidebar-comment");
        commentContent.setPadding(new Insets(8, 10, 8, 10));

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label authorLabel = new Label("u/" + (comment.author() != null ? comment.author() : "[deleted]"));
        authorLabel.getStyleClass().add("graph-sidebar-comment-author");

        Label scoreLabel = new Label("^" + comment.score());
        scoreLabel.getStyleClass().add("graph-sidebar-comment-score");

        header.getChildren().addAll(authorLabel, scoreLabel);

        Integer replies = replyCountMap.get(comment.id());
        if (replies != null && replies > 0) {
            Label replyLabel = new Label("*" + replies);
            replyLabel.getStyleClass().add("graph-sidebar-comment-replies");
            header.getChildren().add(replyLabel);
        }

        if (comment.imageUrls() != null && !comment.imageUrls().isEmpty()) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label imgLabel = new Label(String.valueOf(comment.imageUrls().size()));
            imgLabel.getStyleClass().add("graph-sidebar-comment-reply-count");
            Region imgIcon = new Region();
            imgIcon.getStyleClass().add("icon-image-small");
            HBox imgBox = new HBox(3);
            imgBox.setAlignment(Pos.CENTER_LEFT);
            imgBox.getChildren().addAll(imgIcon, imgLabel);
            header.getChildren().addAll(spacer, imgBox);
        }

        Label bodyLabel = new Label(comment.body() != null ? comment.body() : "[deleted]");
        bodyLabel.getStyleClass().add("graph-sidebar-comment-body");
        bodyLabel.setWrapText(true);

        commentContent.getChildren().addAll(header, bodyLabel);
        commentBlock.getChildren().add(commentContent);

        commentNodeMap.put(comment.id(), commentBlock);
        commentNodeMap.put("CMT_" + comment.id(), commentBlock);

        // Children wrapped in a guide container. Margin pushes the border
        // inward to align with the parent comment's text column.
        List<RedditComment> children = findChildren(comment.id(), byParent);
        if (children != null && !children.isEmpty()) {
            children.sort(Comparator.comparingInt(RedditComment::score).reversed());

            VBox childContainer = new VBox(0);
            childContainer.getStyleClass().add("graph-sidebar-indent-guide");
            // Margin (not padding) moves the border itself to the right
            VBox.setMargin(childContainer, new Insets(0, 0, 0, 12));

            for (RedditComment child : children) {
                childContainer.getChildren().add(buildCommentTree(child, byParent));
            }

            commentBlock.getChildren().add(childContainer);

            // Explicit separator after nested replies, so the next sibling at
            // this level has a visible boundary. The indent guide's own border
            // doesn't provide this separation.
            Region sep = new Region();
            sep.getStyleClass().add("graph-sidebar-separator");
            sep.setMinHeight(1);
            sep.setMaxHeight(1);
            commentBlock.getChildren().add(sep);
        }

        return commentBlock;
    }

    /** Returns top-level comments whose parentId resolves to the given threadId. */
    private List<RedditComment> findTopLevel(List<RedditComment> comments, String threadId) {
        List<RedditComment> topLevel = new ArrayList<>();
        for (RedditComment c : comments) {
            String parentId = c.parentId();
            if (parentId == null)
                continue;
            String clean = parentId.startsWith("t3_") ? parentId.substring(3) : parentId;
            if (clean.equals(threadId) || parentId.equals(threadId) ||
                    parentId.equals("t3_" + threadId)) {
                topLevel.add(c);
            }
        }
        return topLevel;
    }

    /**
     * Resolves direct children for a comment, trying bare, t1_, and CMT_ prefix
     * variants.
     */
    private List<RedditComment> findChildren(String commentId, Map<String, List<RedditComment>> byParent) {
        List<RedditComment> children = byParent.get(commentId);
        if (children == null)
            children = byParent.get("t1_" + commentId);
        if (children == null)
            children = byParent.get("CMT_" + commentId);
        return children;
    }

    /** Pre-computes total recursive reply counts for all comments. */
    private void computeReplyCounts(List<RedditComment> comments, Map<String, List<RedditComment>> byParent) {
        for (RedditComment c : comments) {
            replyCountMap.put(c.id(), countReplies(c.id(), byParent));
        }
    }

    private int countReplies(String commentId, Map<String, List<RedditComment>> byParent) {
        List<RedditComment> direct = findChildren(commentId, byParent);
        if (direct == null)
            return 0;

        int total = direct.size();
        for (RedditComment child : direct) {
            total += countReplies(child.id(), byParent);
        }
        return total;
    }

    /**
     * Indexes comments by parentId with multiple prefix variants so
     * lookups work regardless of the caller's ID format.
     */
    private Map<String, List<RedditComment>> indexByParent(List<RedditComment> comments) {
        Map<String, List<RedditComment>> byParent = new HashMap<>();
        for (RedditComment c : comments) {
            String parentId = c.parentId();
            if (parentId == null || parentId.isEmpty())
                continue;
            byParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(c);
            if (parentId.startsWith("t3_") || parentId.startsWith("t1_")) {
                byParent.computeIfAbsent(parentId.substring(3), k -> new ArrayList<>()).add(c);
            } else {
                byParent.computeIfAbsent("t3_" + parentId, k -> new ArrayList<>()).add(c);
                byParent.computeIfAbsent("t1_" + parentId, k -> new ArrayList<>()).add(c);
                byParent.computeIfAbsent("CMT_" + parentId, k -> new ArrayList<>()).add(c);
            }
        }
        return byParent;
    }

    /** Slides the sidebar open with a 250ms width animation. */
    public void open() {
        if (isOpen)
            return;
        isOpen = true;
        this.setVisible(true);
        this.setManaged(true);

        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(this.prefWidthProperty(), 0),
                        new KeyValue(this.maxWidthProperty(), 0),
                        new KeyValue(this.minWidthProperty(), 0)),
                new KeyFrame(Duration.millis(250),
                        new KeyValue(this.prefWidthProperty(), getTargetWidth(),
                                Interpolator.EASE_BOTH),
                        new KeyValue(this.maxWidthProperty(), getTargetWidth(),
                                Interpolator.EASE_BOTH),
                        new KeyValue(this.minWidthProperty(), getTargetWidth(),
                                Interpolator.EASE_BOTH)));
        timeline.play();
    }

    private Runnable onCloseHandler;

    /** Registers a callback fired when the sidebar closes (via the X button). */
    public void setOnCloseHandler(Runnable handler) {
        this.onCloseHandler = handler;
    }

    /** Slides the sidebar closed with a 200ms width animation. */
    public void close() {
        if (!isOpen)
            return;
        isOpen = false;
        if (onCloseHandler != null) {
            onCloseHandler.run();
        }

        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(this.prefWidthProperty(), getTargetWidth()),
                        new KeyValue(this.maxWidthProperty(), getTargetWidth()),
                        new KeyValue(this.minWidthProperty(), getTargetWidth())),
                new KeyFrame(Duration.millis(200),
                        new KeyValue(this.prefWidthProperty(), 0,
                                Interpolator.EASE_BOTH),
                        new KeyValue(this.maxWidthProperty(), 0,
                                Interpolator.EASE_BOTH),
                        new KeyValue(this.minWidthProperty(), 0,
                                Interpolator.EASE_BOTH)));
        timeline.setOnFinished(e -> {
            this.setVisible(false);
            this.setManaged(false);
        });
        timeline.play();
    }

    public boolean isOpen() {
        return isOpen;
    }

    /** Target width (30% of scene, clamped to 300–600px). */
    private double getTargetWidth() {
        double parentWidth = this.getScene() != null ? this.getScene().getWidth() : 400;
        return Math.max(300, Math.min(parentWidth * 0.30, 600));
    }
}
