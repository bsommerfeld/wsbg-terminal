package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Make-up" Reddit threads for the AI integration tests. Real threads get deleted,
 * so the pipeline ITs (subject extraction, attribution, compose) run against
 * synthetic-but-realistic threads built here — varied phrasings, varied cases
 * (an all-in poll, a red-day watchlist tied to a fixture image, gain-porn, a macro
 * post). The phrasing is deliberately different each call so a test doesn't lock
 * onto one exact wording.
 *
 * <p>Model-free: this only constructs domain objects. A test stores them in a
 * {@code RedditRepository}, clusters with {@code ClusterEngine}, then exercises the
 * editorial stages.
 */
final class SyntheticThreads {

    /** A thread plus its top-level comments. */
    record Synthetic(RedditThread thread, List<RedditComment> comments) {}

    private static final AtomicInteger SEQ = new AtomicInteger();

    private SyntheticThreads() {}

    /** "Which one stock would you go all-in on?" — many clean instrument names in comments. */
    static Synthetic allInPoll() {
        String[] bodies = {
            "Wenn ihr nur EINE Aktie für immer halten dürftet — welche?",
            "Nur noch ein Wert im Depot erlaubt. Was nehmt ihr und warum?",
            "All-in auf genau eine Position. Eure Wahl?"
        };
        String[] picks = {"Alphabet", "Apple", "NVIDIA", "ASML", "Berkshire Hathaway",
            "Münchener Rück", "SAP", "Rheinmetall"};
        return thread("Nur eine Aktie für immer", pick(bodies), List.of(), picks);
    }

    /** A red-day watchlist post tied to a fixture watchlist image (served by LocalImageServer). */
    static Synthetic redDayWatchlist(String imageUrl) {
        String[] bodies = {
            "Alles rot heute. Mein Depot blutet.",
            "Roter Tag, die Watchlist glüht. Wer kauft den Dip?",
            "Heute nur Minuszeichen — Zeit zum Nachkaufen?"
        };
        return thread("Alles rot", pick(bodies), List.of(imageUrl),
            "Apple", "NVIDIA", "Microsoft", "Infineon");
    }

    /** Gain-porn: a portfolio screenshot + bragging comments. */
    static Synthetic gainPorn(String imageUrl) {
        String[] bodies = {
            "Endlich grün auf der ganzen Linie. Diamond Hands zahlen sich aus.",
            "Mein Depot nach einem Jahr halten. Nicht verkaufen, niemals.",
            "Realisierte Gewinne? Nö. Wir halten bis zur Rente."
        };
        return thread("Gewinne realisieren?", pick(bodies), List.of(imageUrl),
            "NVIDIA", "Siemens");
    }

    /** A macro/statistics post: non-instrument subjects (Trump, QQQ, tariffs). */
    static Synthetic macroPost() {
        String[] bodies = {
            "Rein statistisch gibt es nach so einem Dip am Montag oft eine Erholung.",
            "Makro-Blick: Trumps 100%-Zoll-Drohung gegen China hat QQQ −3,5% gekostet.",
            "Spannweite historisch −6% bis +9%. Keine Garantie, nur Statistik."
        };
        return thread("Statistik zum Montag", pick(bodies), List.of(),
            "QQQ", "Trump", "China");
    }

    private static Synthetic thread(String title, String body, List<String> imageUrls, String... mentions) {
        int n = SEQ.incrementAndGet();
        String id = "t3_syn" + n;
        long now = System.currentTimeMillis() / 1000;
        RedditThread t = new RedditThread(id, "wallstreetbetsGER", title, "[user]",
                body, now, "/r/wallstreetbetsGER/comments/" + id, 0, 0.5, mentions.length,
                now, new ArrayList<>(imageUrls));
        List<RedditComment> comments = new ArrayList<>();
        String[] frames = {"Ich nehme %s.", "Klar %s, alles andere ist Müll.", "%s, langfristig der Gewinner.",
            "Geh all-in %s.", "%s ohne Frage."};
        int c = 0;
        for (String m : mentions) {
            String body2 = String.format(frames[c % frames.length], m);
            comments.add(new RedditComment("t1_syn" + n + "_" + c, id, id, "[user]",
                    body2, 0, now, now, now));
            c++;
        }
        return new Synthetic(t, comments);
    }

    private static String pick(String[] options) {
        // Deterministic-ish rotation so successive calls vary the wording.
        return options[SEQ.get() % options.length];
    }
}
