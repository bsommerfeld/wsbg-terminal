package de.bsommerfeld.tinyrss.markup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a feed's HTML says and shows: its text, and its pictures. Read with
 * the same {@link MarkupParser} as the feed, so a {@code <} in a sentence or
 * an unclosed tag costs nothing.
 */
public final class HtmlText {

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u200B]+");
    private static final Set<String> SILENT = Set.of("script", "style", "noscript", "template", "head");
    private static final Set<String> BLOCKS = Set.of(
            "address", "article", "aside", "blockquote", "br", "dd", "div", "dl", "dt", "figcaption", "figure",
            "footer", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre",
            "section", "table", "td", "th", "tr", "ul");

    private HtmlText() {
    }

    /**
     * The text of an HTML fragment on one line: tags dropped - block tags
     * leave a space, so paragraphs do not run into each other - references
     * decoded, whitespace collapsed. Empty for {@code null}.
     */
    public static String text(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        if (html.indexOf('<') < 0 && html.indexOf('&') < 0) {
            return collapse(html);
        }
        StringBuilder text = new StringBuilder(html.length());
        appendText(MarkupParser.parse(html, null), text);
        return collapse(text.toString());
    }

    /**
     * The pictures of an HTML fragment - every {@code <img>} but tracking
     * pixels (1x1) and inline {@code data:} images, resolved against
     * {@code base}, in order, each once.
     */
    public static List<String> images(String html, String base) {
        if (html == null || !html.toLowerCase(Locale.ROOT).contains("<img")) {
            return List.of();
        }
        Set<String> images = new LinkedHashSet<>();
        collectImages(MarkupParser.parse(html, base), images);
        return new ArrayList<>(images);
    }

    /** {@code text} with its whitespace runs, non-breaking spaces included, as single spaces. */
    public static String collapse(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    /** {@code text} as HTML: the three characters that would read as markup escaped. */
    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void appendText(Element element, StringBuilder text) {
        for (Object child : element.children()) {
            if (child instanceof Element nested) {
                String name = nested.localName().toLowerCase(Locale.ROOT);
                if (SILENT.contains(name)) {
                    continue;
                }
                boolean block = BLOCKS.contains(name);
                if (block) {
                    text.append(' ');
                }
                appendText(nested, text);
                if (block) {
                    text.append(' ');
                }
            } else {
                text.append((String) child);
            }
        }
    }

    private static void collectImages(Element element, Set<String> images) {
        for (Element nested : element.elements()) {
            if (nested.localName().equalsIgnoreCase("img") && !isPixel(nested)) {
                String url = Urls.resolve(nested.base(), source(nested));
                if (Urls.isWeb(url)) {
                    images.add(url);
                }
            }
            collectImages(nested, images);
        }
    }

    /** {@code src}, else what a lazy loader holds back ({@code data-src}), else the first {@code srcset} entry. */
    private static String source(Element image) {
        String source = image.attribute("src");
        if (source == null || source.isBlank() || source.startsWith("data:")) {
            source = image.attribute("data-src");
        }
        if ((source == null || source.isBlank()) && image.attribute("srcset") != null) {
            source = image.attribute("srcset").trim().split("[\\s,]+")[0];
        }
        return source;
    }

    private static boolean isPixel(Element image) {
        return isAtMostOne(image.attribute("width")) && isAtMostOne(image.attribute("height"));
    }

    private static boolean isAtMostOne(String size) {
        if (size == null) {
            return false;
        }
        String digits = size.trim().replace("px", "");
        return digits.equals("0") || digits.equals("1");
    }
}
