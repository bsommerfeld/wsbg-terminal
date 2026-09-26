package de.bsommerfeld.tinyreddit;

/**
 * Reddit payloads in the shape Reddit sends them ({@code raw_json=1}), cut
 * down to the fields that matter. Hand-built from Reddit's documented
 * structure; the live endpoints refused this network when they were written.
 */
public final class RedditFixtures {

    private RedditFixtures() {
    }

    /** {@code /r/wallstreetbetsGER/new.json}: self post, link post, gallery, crosspost, poll, and an ad-like non-post. */
    public static final String LISTING = """
            {"kind": "Listing", "data": {"after": "t3_e5", "children": [
              {"kind": "t3", "data": {
                "name": "t3_a1", "id": "a1", "subreddit": "wallstreetbetsGER",
                "title": "SAP über 250?", "author": "affe42", "is_self": true,
                "selftext": "Calls gekauft, Hopium & Mondfahrt", "created_utc": 1782345600.0,
                "permalink": "/r/wallstreetbetsGER/comments/a1/sap_uber_250/", "score": 128,
                "upvote_ratio": 0.93, "num_comments": 41,
                "url": "https://www.reddit.com/r/wallstreetbetsGER/comments/a1/sap_uber_250/"}},
              {"kind": "t3", "data": {
                "name": "t3_b2", "subreddit": "wallstreetbetsGER", "title": "Chart", "author": "[deleted]",
                "is_self": false, "selftext": "", "created_utc": 1782345000,
                "permalink": "/r/wallstreetbetsGER/comments/b2/chart/", "score": 5, "upvote_ratio": 0.6,
                "num_comments": 2, "url_overridden_by_dest": "https://i.redd.it/chart.png?width=640&amp;s=x",
                "url": "https://i.redd.it/chart.png"}},
              {"kind": "t3", "data": {
                "name": "t3_c3", "subreddit": "wallstreetbetsGER", "title": "Depot", "author": "bär",
                "is_self": false, "is_gallery": true, "selftext": "", "created_utc": 1782344000,
                "permalink": "/r/wallstreetbetsGER/comments/c3/depot/", "score": 9, "num_comments": 0,
                "url": "https://www.reddit.com/gallery/c3",
                "gallery_data": {"items": [{"media_id": "m2"}, {"media_id": "m1"}, {"media_id": "vid"}]},
                "media_metadata": {
                  "m1": {"status": "valid", "e": "Image", "s": {"u": "https://preview.redd.it/m1.jpg?a=1&amp;b=2"}},
                  "m2": {"status": "valid", "e": "Image", "s": {"u": "https://preview.redd.it/m2.png"}},
                  "vid": {"status": "valid", "e": "RedditVideo", "s": {"u": "https://v.redd.it/x"}}}}},
              {"kind": "t3", "data": {
                "name": "t3_d4", "subreddit": "wallstreetbetsGER", "title": "Crosspost", "author": "xposter",
                "is_self": false, "selftext": "", "created_utc": 1782343000,
                "permalink": "/r/wallstreetbetsGER/comments/d4/crosspost/", "score": 3, "num_comments": 1,
                "url": "/r/mauerstrassenwetten/comments/zz/original/",
                "crosspost_parent_list": [{"selftext": "Original-Text", "url": "https://i.redd.it/orig.jpg"}]}},
              {"kind": "t3", "data": {
                "name": "t3_e5", "subreddit": "wallstreetbetsGER", "title": "Rot oder grün?", "author": "umfrage",
                "is_self": true, "selftext": "", "created_utc": 1782342000,
                "permalink": "/r/wallstreetbetsGER/comments/e5/rot_oder_grun/", "score": 1, "num_comments": 0,
                "poll_data": {"total_vote_count": 27, "voting_end_timestamp": 1782400000000,
                  "options": [{"id": "o1", "text": "rot", "vote_count": 13},
                              {"id": "o2", "text": " ", "vote_count": 0},
                              {"id": "o3", "text": "grün", "vote_count": 14}]}}},
              {"kind": "t5", "data": {"name": "t5_sub", "display_name": "not a post"}}
            ]}}
            """;

    /** {@code /comments/a1.json}: post, a reply chain, a removed comment with a live reply, a "more" stub. */
    public static final String DISCUSSION = """
            [
              {"kind": "Listing", "data": {"children": [{"kind": "t3", "data": {
                "name": "t3_a1", "subreddit": "wallstreetbetsGER", "title": "SAP über 250?", "author": "affe42",
                "is_self": true, "selftext": "Calls gekauft", "created_utc": 1782345600,
                "permalink": "/r/wallstreetbetsGER/comments/a1/sap_uber_250/", "score": 128, "num_comments": 4}}]}},
              {"kind": "Listing", "data": {"children": [
                {"kind": "t1", "data": {
                  "name": "t1_c1", "parent_id": "t3_a1", "link_id": "t3_a1", "author": "bulle",
                  "body": "Mond! https://i.redd.it/rakete.gif.", "score": 17, "created_utc": 1782345700,
                  "replies": {"kind": "Listing", "data": {"children": [
                    {"kind": "t1", "data": {
                      "name": "t1_c2", "parent_id": "t1_c1", "author": "bär", "body": "Blut.",
                      "score": -3, "created_utc": 1782345800, "replies": ""}}]}}}},
                {"kind": "t1", "data": {
                  "name": "t1_c3", "parent_id": "t3_a1", "author": "[deleted]", "body": null,
                  "score": 1, "created_utc": 1782345900,
                  "replies": {"kind": "Listing", "data": {"children": [
                    {"kind": "t1", "data": {
                      "name": "t1_c4", "parent_id": "t1_c3", "author": "zeuge", "body": "Was stand da?",
                      "score": 2, "created_utc": 1782346000, "replies": "",
                      "media_metadata": {"img9": {"status": "valid", "e": "Image",
                        "s": {"u": "https://preview.redd.it/img9.png"}}}}}]}}}},
                {"kind": "more", "data": {"count": 12, "children": ["c9", "c10"]}}
              ]}}
            ]
            """;

    /** {@code /r/wallstreetbetsGER/comments.json}: the sub-wide stream. */
    public static final String COMMENT_STREAM = """
            {"kind": "Listing", "data": {"children": [
              {"kind": "t1", "data": {"name": "t1_s1", "link_id": "t3_a1", "parent_id": "t1_c1",
                "author": "stream", "body": "Neu hier", "score": 4, "created_utc": 1782346100,
                "permalink": "/r/wallstreetbetsGER/comments/a1/sap_uber_250/s1/"}},
              {"kind": "t1", "data": {"name": "t1_s2", "parent_id": "t3_q7",
                "author": "ohnelink", "body": "Link fehlt", "score": 1, "created_utc": 1782346200,
                "permalink": "/r/wallstreetbetsGER/comments/q7/anderer_post/s2/"}}
            ]}}
            """;

    /** {@code /r/wallstreetbetsGER/new.rss}. */
    public static final String LISTING_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
              <title>neueste Beiträge</title>
              <entry>
                <author><name>/u/affe42</name><uri>https://www.reddit.com/user/affe42</uri></author>
                <content type="html">&lt;div class="md"&gt;&lt;p&gt;Calls gekauft &amp;amp; Hopium&lt;/p&gt;&lt;/div&gt; submitted by &lt;a href="https://www.reddit.com/user/affe42"&gt; /u/affe42 &lt;/a&gt;</content>
                <id>t3_a1</id>
                <link href="https://www.reddit.com/r/wallstreetbetsGER/comments/a1/sap_uber_250/"/>
                <updated>2026-06-25T00:00:00+00:00</updated>
                <published>2026-06-25T00:00:00+00:00</published>
                <title>SAP über 250?</title>
              </entry>
              <entry>
                <author><name>/u/bär</name></author>
                <content type="html">&lt;a href="https://i.redd.it/chart.png"&gt;&lt;img src="https://preview.redd.it/chart.png?width=640&amp;amp;s=x"/&gt;&lt;/a&gt; submitted by /u/bär</content>
                <id>t3_b2</id>
                <media:thumbnail url="https://b.thumbs.redditmedia.com/t.jpg"/>
                <link href="https://www.reddit.com/r/wallstreetbetsGER/comments/b2/chart/"/>
                <published>2026-06-24T23:50:00+00:00</published>
                <title>Chart</title>
              </entry>
            </feed>
            """;

    /** {@code /r/wallstreetbetsGER/comments/a1/sap_uber_250/.rss}: the post, then its comments. */
    public static final String DISCUSSION_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <author><name>/u/affe42</name></author>
                <content type="html">&lt;p&gt;Calls gekauft&lt;/p&gt;</content>
                <id>t3_a1</id>
                <link href="https://www.reddit.com/r/wallstreetbetsGER/comments/a1/sap_uber_250/"/>
                <published>2026-06-25T00:00:00+00:00</published>
                <title>SAP über 250?</title>
              </entry>
              <entry>
                <author><name>/u/bulle</name></author>
                <content type="html">&lt;p&gt;Mond!&lt;/p&gt;</content>
                <id>t1_c1</id>
                <link href="https://www.reddit.com/r/wallstreetbetsGER/comments/a1/sap_uber_250/c1/"/>
                <updated>2026-06-25T00:01:40+00:00</updated>
                <title>/u/bulle on SAP über 250?</title>
              </entry>
            </feed>
            """;
}
