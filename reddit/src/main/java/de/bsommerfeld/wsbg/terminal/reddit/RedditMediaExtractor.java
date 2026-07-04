package de.bsommerfeld.wsbg.terminal.reddit;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsommerfeld.wsbg.terminal.reddit.support.RedditText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves post and comment images for the JSON path: galleries, inline
 * rich-text media, crosspost inheritance, single-image posts and comment-body
 * image URLs. Stateless — the only mutation is the (deprecated, unread)
 * {@code imageCounter} on a supplied {@link ThreadAnalysisContext} for the
 * comment path, kept for behaviour parity.
 */
public final class RedditMediaExtractor {

    /**
     * Maximum number of gallery slides stored per thread. Reddit caps
     * galleries at 20; we keep up to 10. Storage is cheap (just URLs) — the cost
     * is vision, and that's decoupled downstream.
     */
    private static final int MAX_GALLERY_IMAGES = 10;

    /**
     * Greedy URL pattern used by {@link #extractCommentImages} to find HTTP(S)
     * links embedded in comment text. The greedy {@code \\S+} deliberately
     * over-matches (capturing trailing punctuation), which is then stripped by
     * {@link RedditText#stripTrailingPunctuation}.
     */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    /**
     * Matches a Reddit inline-media reference inside selftext markdown, e.g.
     * {@code ![img](abc123 "caption")} or {@code ![gif](xy7q9)}. The captured
     * group is the {@code media_id} — a key into the post's
     * {@code media_metadata} map, NOT a URL.
     */
    private static final Pattern INLINE_MEDIA_REF =
            Pattern.compile("!\\[[^\\]]*]\\(([^)\\s\"]+)[^)]*\\)");

    /**
     * Result of scanning comment text for image URLs. Contains the modified text
     * (with image URLs tagged as {@code [Image]}) and the list of extracted
     * image URLs for separate storage.
     */
    public record ImageExtractionResult(String text, List<String> images) {}

    /**
     * Resolves the image URLs for a thread, returning an empty list when the
     * post carries no images. Gallery posts, inline-body images, crosspost
     * parents and single-image posts are handled in that order.
     */
    public List<String> resolveImageUrls(JsonNode data) {
        // Crossposts carry no media of their own — the original post sits in
        // crosspost_parent_list. Recurse into it so the crosspost inherits the
        // full image-resolution logic.
        JsonNode crosspostParent = firstCrosspostParent(data);
        if (crosspostParent != null) {
            List<String> fromParent = resolveImageUrls(crosspostParent);
            if (!fromParent.isEmpty()) return fromParent;
        }

        if (data.path("is_gallery").asBoolean(false)) {
            List<String> gallery = extractGalleryUrls(data);
            if (!gallery.isEmpty()) return gallery;
        }

        // Images embedded mid-text in the body via the rich-text editor.
        List<String> inline = extractInlineBodyImages(data);
        if (!inline.isEmpty()) return inline;

        String url = null;
        if (data.has("url_overridden_by_dest")) {
            url = RedditText.unescapeHtml(data.get("url_overridden_by_dest").asText());
        } else if (data.has("url")) {
            url = RedditText.unescapeHtml(data.get("url").asText());
        }
        if (url != null && RedditText.isImageUrl(url)) {
            return List.of(url);
        }
        return List.of();
    }

    /**
     * Returns the original post node of a crosspost (the first element of
     * {@code crosspost_parent_list}), or {@code null} when this post isn't a
     * crosspost.
     */
    public JsonNode firstCrosspostParent(JsonNode data) {
        JsonNode list = data.path("crosspost_parent_list");
        if (list.isArray() && !list.isEmpty()) {
            JsonNode parent = list.get(0);
            if (parent != null && !parent.isNull() && !parent.isMissingNode()) {
                return parent;
            }
        }
        return null;
    }

    /**
     * Collects images embedded inline in the post body via the rich-text
     * editor, resolved against {@code media_metadata} in text order
     * (de-duplicated, capped at {@value #MAX_GALLERY_IMAGES}).
     */
    private List<String> extractInlineBodyImages(JsonNode data) {
        JsonNode metadata = data.path("media_metadata");
        if (metadata.isMissingNode() || metadata.isNull()) return List.of();
        String selftext = data.path("selftext").asText("");
        if (selftext.isEmpty()) return List.of();

        List<String> urls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = INLINE_MEDIA_REF.matcher(selftext);
        while (m.find() && urls.size() < MAX_GALLERY_IMAGES) {
            String mediaId = m.group(1);
            if (mediaId.isEmpty() || !seen.add(mediaId)) continue;
            String url = mediaUrlOf(metadata, mediaId);
            if (url != null) urls.add(url);
        }
        return urls;
    }

    /**
     * Walks {@code gallery_data.items} in display order, looking each
     * {@code media_id} up in {@code media_metadata}, collecting the {@code s.u}
     * preview URL of every valid image entry.
     */
    private List<String> extractGalleryUrls(JsonNode data) {
        JsonNode items = data.path("gallery_data").path("items");
        JsonNode metadata = data.path("media_metadata");
        if (!items.isArray() || metadata.isMissingNode() || metadata.isNull()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (JsonNode item : items) {
            if (urls.size() >= MAX_GALLERY_IMAGES) break;
            String mediaId = item.path("media_id").asText(null);
            if (mediaId == null || mediaId.isEmpty()) continue;
            String url = mediaUrlOf(metadata, mediaId);
            if (url != null) urls.add(url);
        }
        return urls;
    }

    /**
     * Resolves a single {@code media_id} against a {@code media_metadata} map,
     * returning the full-size preview URL ({@code s.u}) for valid
     * still/animated images, or {@code null} for missing, non-image, or invalid
     * entries. Shared by the gallery and inline-body image paths.
     */
    private String mediaUrlOf(JsonNode metadata, String mediaId) {
        JsonNode entry = metadata.path(mediaId);
        if (entry.isMissingNode() || entry.isNull()) return null;
        if (!"valid".equals(entry.path("status").asText(""))) return null;
        String type = entry.path("e").asText("");
        if (!"Image".equals(type) && !"AnimatedImage".equals(type)) return null;
        String url = entry.path("s").path("u").asText(null);
        if (url == null || url.isEmpty()) return null;
        return RedditText.unescapeHtml(url);
    }

    /**
     * Scans comment text for embedded URLs, identifies image links, and replaces
     * them with {@code [Image]} markers. After the URL pass, inline images
     * embedded via the rich-text editor are resolved against the comment node's
     * {@code media_metadata}. Inline refs carry no {@code http}, so the two
     * passes never collide.
     *
     * @return the modified text and a list of discovered image URLs
     */
    public ImageExtractionResult extractCommentImages(String text, JsonNode data,
            ThreadAnalysisContext context) {
        Matcher m = URL_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        List<String> imagesFound = new ArrayList<>();

        while (m.find()) {
            String fullMatch = m.group();
            String cleanUrl = RedditText.stripTrailingPunctuation(fullMatch);
            String suffix = fullMatch.substring(cleanUrl.length());
            String unescapedUrl = RedditText.unescapeHtml(cleanUrl);

            if (RedditText.isImageUrl(unescapedUrl)) {
                context.imageCounter++;
                imagesFound.add(unescapedUrl);
                m.appendReplacement(sb,
                        Matcher.quoteReplacement(cleanUrl + " [Image]" + suffix));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(fullMatch));
            }
        }
        m.appendTail(sb);

        String withInline = resolveInlineMediaRefs(sb.toString(), data, imagesFound, context);
        return new ImageExtractionResult(withInline, imagesFound);
    }

    /**
     * Resolves Reddit inline-media references ({@code ![img](<media_id>)}) in a
     * comment body against the comment node's {@code media_metadata}, appending
     * any newly discovered image URLs to {@code imagesFound} (kept
     * de-duplicated) and rewriting each resolved reference to an {@code [Image]}
     * marker. Unresolvable references are left untouched.
     */
    private String resolveInlineMediaRefs(String text, JsonNode data,
            List<String> imagesFound, ThreadAnalysisContext context) {
        JsonNode metadata = data.path("media_metadata");
        if (metadata.isMissingNode() || metadata.isNull()) return text;

        Matcher m = INLINE_MEDIA_REF.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String url = mediaUrlOf(metadata, m.group(1));
            if (url != null) {
                if (!imagesFound.contains(url)) {
                    imagesFound.add(url);
                    context.imageCounter++;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement("[Image]"));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
