package de.bsommerfeld.tinyreddit.mapping;

import de.bsommerfeld.tinyreddit.json.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the images of a post or comment in Reddit's JSON: galleries, images
 * placed inline by the rich-text editor, the images of a crosspost's original,
 * a single-image post, and image links in comment text.
 */
public final class MediaExtractor {

    /** Galleries hold up to 20; ten is plenty for anything reading them. */
    static final int MAX_IMAGES = 10;

    private static final Pattern URL = Pattern.compile("https?://\\S+");

    /**
     * An inline media reference in markdown, {@code ![img](abc123 "caption")}:
     * the capture is a key into {@code media_metadata}, not a URL.
     */
    private static final Pattern INLINE_MEDIA = Pattern.compile("!\\[[^\\]]*]\\(([^)\\s\"]+)[^)]*\\)");

    private MediaExtractor() {
    }

    /**
     * A post's images, first source that has any: crosspost original,
     * gallery, inline body images, the post's own image URL.
     */
    public static List<String> postImages(JsonNode data) {
        JsonNode original = crosspostOriginal(data);
        if (original != null) {
            List<String> inherited = postImages(original);
            if (!inherited.isEmpty()) {
                return inherited;
            }
        }
        if (data.path("is_gallery").asBoolean(false)) {
            List<String> gallery = gallery(data);
            if (!gallery.isEmpty()) {
                return gallery;
            }
        }
        List<String> inline = inline(data.path("selftext").asText(""), data.path("media_metadata"));
        if (!inline.isEmpty()) {
            return inline;
        }
        String url = RedditText.unescapeHtml(
                data.path("url_overridden_by_dest").asText(data.path("url").asText(null)));
        return RedditText.isImageUrl(url) ? List.of(url) : List.of();
    }

    /** A comment's images: image links in its text, then inline media. */
    public static List<String> commentImages(String body, JsonNode data) {
        Set<String> images = new LinkedHashSet<>();
        Matcher matcher = URL.matcher(body);
        while (matcher.find() && images.size() < MAX_IMAGES) {
            String url = RedditText.unescapeHtml(RedditText.stripTrailingPunctuation(matcher.group()));
            if (RedditText.isImageUrl(url)) {
                images.add(url);
            }
        }
        for (String url : inline(body, data.path("media_metadata"))) {
            if (images.size() >= MAX_IMAGES) {
                break;
            }
            images.add(url);
        }
        return List.copyOf(images);
    }

    /** The original post of a crosspost, {@code null} for anything else. */
    public static JsonNode crosspostOriginal(JsonNode data) {
        JsonNode original = data.path("crosspost_parent_list").at(0);
        return original.isObject() ? original : null;
    }

    private static List<String> gallery(JsonNode data) {
        JsonNode metadata = data.path("media_metadata");
        List<String> urls = new ArrayList<>();
        for (JsonNode item : data.path("gallery_data").path("items").elements()) {
            if (urls.size() >= MAX_IMAGES) {
                break;
            }
            String url = mediaUrl(metadata, item.path("media_id").asText(""));
            if (url != null) {
                urls.add(url);
            }
        }
        return urls;
    }

    private static List<String> inline(String markdown, JsonNode metadata) {
        if (!metadata.isObject() || markdown.isEmpty()) {
            return List.of();
        }
        Set<String> urls = new LinkedHashSet<>();
        Matcher matcher = INLINE_MEDIA.matcher(markdown);
        while (matcher.find() && urls.size() < MAX_IMAGES) {
            String url = mediaUrl(metadata, matcher.group(1));
            if (url != null) {
                urls.add(url);
            }
        }
        return List.copyOf(urls);
    }

    /** The full-size URL ({@code s.u}) of a valid still or animated image, else {@code null}. */
    private static String mediaUrl(JsonNode metadata, String mediaId) {
        if (mediaId.isEmpty()) {
            return null;
        }
        JsonNode entry = metadata.path(mediaId);
        if (!"valid".equals(entry.path("status").asText(""))) {
            return null;
        }
        String type = entry.path("e").asText("");
        if (!type.equals("Image") && !type.equals("AnimatedImage")) {
            return null;
        }
        String url = entry.path("s").path("u").asText("");
        return url.isEmpty() ? null : RedditText.unescapeHtml(url);
    }
}
