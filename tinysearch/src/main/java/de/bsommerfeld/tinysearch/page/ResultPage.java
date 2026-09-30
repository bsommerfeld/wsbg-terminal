package de.bsommerfeld.tinysearch.page;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * One engine's search: the address a query goes to, and where on the
 * answer the hits are. The selectors are read off the pages measured
 * 2026-09-30 - the test fixtures are those pages, slimmed.
 *
 * <h2>Result page or not</h2>
 * Each engine has an element that every one of its result pages carries,
 * with hits or with its "nothing found" - and that a check page, an error
 * page or a rebuilt layout does not. Without it the answer is
 * {@linkplain UnreadablePageException unreadable}; with it, no hits means
 * the engine found nothing. Ads are never read as hits.
 */
public final class ResultPage {

    public static final ResultPage BING = new ResultPage(
            "https://www.bing.com/search?q={query}",
            "#b_results",
            "li.b_algo", "h2 a", "h2 a", ".b_caption p",
            Links::bing);

    public static final ResultPage BRAVE = new ResultPage(
            "https://search.brave.com/search?q={query}&source=web",
            "#search-page",
            "div.snippet[data-type=web]", "a[href]", ".title", ".generic-snippet .content",
            Links::direct);

    public static final ResultPage DUCKDUCKGO = new ResultPage(
            "https://html.duckduckgo.com/html/?q={query}",
            "div.results",
            "div.result:not(.result--ad)", "a.result__a", "a.result__a", ".result__snippet",
            Links::duckDuckGo);

    public static final ResultPage ECOSIA = new ResultPage(
            "https://www.ecosia.org/search?method=index&q={query}",
            "[data-test-id=mainline], [data-test-id=web-no-results]",
            "[data-test-id=mainline-result-web]", "a[data-test-id=result-link]",
            "[data-test-id=result-title]", "[data-test-id=result-description]",
            Links::direct);

    public static final ResultPage STARTPAGE = new ResultPage(
            "https://www.startpage.com/sp/search?query={query}",
            "div.w-gl, div.noresults",
            "div.w-gl div.result", "a.result-title", "a.result-title h2", "p.description",
            Links::direct);

    public static final ResultPage YAHOO = new ResultPage(
            "https://search.yahoo.com/search?p={query}",
            "#web",
            "#web div.algo", ".compTitle a", "h3.title", ".compText p",
            Links::yahoo);

    public static final ResultPage YANDEX = new ResultPage(
            "https://yandex.com/search/?text={query}",
            "#search-result",
            "#search-result li.serp-item", "a.OrganicTitle-Link", ".OrganicTitle-LinkText",
            ".OrganicTextContentSpan",
            Links::direct);

    private final String address;
    private final String resultPage;
    private final String hit;
    private final String link;
    private final String title;
    private final String snippet;
    private final UnaryOperator<URI> target;

    /**
     * @param address    the search address, {@code {query}} standing for the query
     * @param resultPage the element every result page carries, hits or not
     * @param hit        one organic hit
     * @param link       within a hit: the link to the page
     * @param title      within a hit: its title
     * @param snippet    within a hit: the text under the title
     * @param target     the page a link leads to, or {@code null} if none can be told
     */
    private ResultPage(String address, String resultPage, String hit, String link, String title, String snippet,
            UnaryOperator<URI> target) {
        this.address = address;
        this.resultPage = resultPage;
        this.hit = hit;
        this.link = link;
        this.title = title;
        this.snippet = snippet;
        this.target = target;
    }

    /** Where a search for {@code query} goes. */
    public String address(String query) {
        return address.replace("{query}", URLEncoder.encode(query, StandardCharsets.UTF_8));
    }

    /**
     * The hits on an answer, in the engine's order.
     *
     * @param html    the answer
     * @param baseUrl where it came from, for the relative links
     * @throws UnreadablePageException the answer is not a result page
     */
    public List<PageHit> read(String html, String baseUrl) throws UnreadablePageException {
        Document page = Jsoup.parse(html, baseUrl);
        if (page.selectFirst(resultPage) == null) {
            String pageTitle = page.title().strip();
            throw new UnreadablePageException("not a result page"
                    + (pageTitle.isEmpty() ? "" : ": \"" + pageTitle + "\""));
        }
        List<PageHit> hits = new ArrayList<>();
        for (Element element : page.select(hit)) {
            Element linkElement = element.selectFirst(link);
            URI linked = linkElement == null ? null : Links.parse(linkElement.absUrl("href"));
            URI url = linked == null ? null : target.apply(linked);
            String titleText = text(element.selectFirst(title));
            if (url == null || titleText.isEmpty()) {
                continue;
            }
            hits.add(new PageHit(url, titleText, text(element.selectFirst(snippet))));
        }
        return hits;
    }

    private static String text(Element element) {
        return element == null ? "" : element.text().strip();
    }
}
