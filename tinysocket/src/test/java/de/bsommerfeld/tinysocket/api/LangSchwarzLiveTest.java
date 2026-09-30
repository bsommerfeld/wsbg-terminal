package de.bsommerfeld.tinysocket.api;

import de.bsommerfeld.tinyfetch.api.BrowserEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live quotes from Lang &amp; Schwarz, who allow bots on their push: a real
 * third-party socket through the real engine - opt-in, and run sparingly
 * ({@link SocketLiveTest} says how).
 *
 * <p>L&amp;S push is Lightstreamer 6 on {@code push.ls-tc.de}, in its legacy
 * protocol under the subprotocol {@code js.lightstreamer.com}, spoken here as
 * the site's own client speaks it (observed 2026-09-30): {@code create_session}
 * with {@code LS_client_version=6.1} - the server's license admits no other
 * client - then a {@code control} per subscription. The snapshot comes as
 * {@code z(table,item,values...)}, every update after as {@code d(...)}.
 */
@Tag("live")
class LangSchwarzLiveTest {

    private static final String SAP = "34313@1";
    private static final Pattern SESSION = Pattern.compile("start\\('([^']+)'");
    private static final Pattern SNAPSHOT = Pattern.compile("z\\(1,1,'([^']*)','([^']*)'");

    @Test
    void sapQuotesFromTheSocketTheSiteUses() throws Exception {
        Path target = Path.of(System.getProperty("tinybrowser.target", "../tinybrowser/target")).toAbsolutePath();
        assumeTrue(Files.isDirectory(target.resolve("engine")), "TinyBrowser not packaged - mvn package -pl tinybrowser -am");
        BrowserEngine engine = BrowserEngine.of(List.of(target.resolve("classes"), target.resolve("engine").resolve("*")),
                target.resolve("chromium"), target.resolve("socket-profile"));
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();

        // robots.txt: a page of the site's origin that costs next to nothing to load.
        try (TinySocket sockets = TinySocket.builder().engine(engine)
                .anchor("push.ls-tc.de", "https://www.ls-tc.de/robots.txt").build()) {
            WebSocket push = sockets.open("wss://push.ls-tc.de/lightstreamer", List.of("js.lightstreamer.com"),
                    new SocketListener() {
                        @Override
                        public void onText(WebSocket socket, String text) {
                            heard.add(text);
                        }
                    });
            assertEquals("js.lightstreamer.com", push.protocol());

            push.send("create_session\r\nLS_phase=1&LS_cause=new.api&LS_polling=false&LS_client_version=6.1"
                    + "&LS_adapter_set=WALLSTREETONLINE&LS_container=lsc&");
            String session = await(heard, SESSION).group(1);
            push.send("control\r\nLS_mode=MERGE&LS_id=" + SAP.replace("@", "%40") + "&LS_schema=bid%20ask"
                    + "&LS_data_adapter=QUOTE&LS_snapshot=true&LS_table=1&LS_req_phase=1&LS_win_phase=1&LS_op=add"
                    + "&LS_session=" + session + "&");
            Matcher snapshot = await(heard, SNAPSHOT);
            double bid = Double.parseDouble(snapshot.group(1));
            double ask = Double.parseDouble(snapshot.group(2));
            System.out.println("SAP at L&S: bid " + bid + ", ask " + ask + " (session " + session + ")");
            assertTrue(bid > 0 && ask >= bid, "bid " + bid + ", ask " + ask);
            push.close();
        }
    }

    /** The first message within 30 s that matches {@code pattern}. */
    private static Matcher await(BlockingQueue<String> heard, Pattern pattern) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String message = heard.poll(1, TimeUnit.SECONDS);
            Matcher matcher = message == null ? null : pattern.matcher(message);
            if (matcher != null && matcher.find()) {
                return matcher;
            }
        }
        assertNotNull(null, "nothing matched " + pattern + " within 30 s");
        return null;
    }
}
