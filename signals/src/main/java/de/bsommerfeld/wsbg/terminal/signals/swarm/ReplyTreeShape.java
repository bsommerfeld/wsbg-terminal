package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reply-Baum-Topologie: klassifiziert die Wuchsform eines Kommentar-Waldes.
 * Flach-breit gewachsene Threads sind Zuruf/Zustimmung, tief-schmal gewachsene
 * Threads sind Schlagabtausch - die Unterscheidung folgt der Kaskaden-Analyse
 * von Diskussionsbäumen (strukturelle Viralität, Goel/Anderson/Hofman/Watts
 * 2016: breite vs. tiefe Diffusionsbäume).
 *
 * <p>Numerik: aus den parentId-Kanten wird der Wald aufgebaut (Kommentare mit
 * unbekanntem oder fehlendem Parent gelten als Wurzeln), per
 * Breitensuche werden Tiefen bestimmt (Wurzel = Tiefe 1). Der Konflikt-Index
 * ist die mittlere Blatt-Tiefe geteilt durch log2(n+1) - den Erwartungsmaßstab
 * eines balancierten Binärbaums; Werte deutlich darüber heißen: der Thread
 * wächst in Argument-Ketten statt in die Breite.
 *
 * <p>Input im Terminal: die Kommentar-IDs samt parentId aus dem
 * Reddit-Scraper (der Reply-Baum, den RSS nicht sehen kann - OAuth/.json-Pfad
 * liefert ihn, ReportBuilder nutzt ihn bereits für die Zuordnung).
 */
public final class ReplyTreeShape {

    /** Ein Kommentar als Kante im Reply-Wald; parentId null = Top-Level. */
    public record Comment(String id, String parentId) {
    }

    private static final String ID = "reply-tree-shape";
    private static final String TITLE = "Reply-Baum-Topologie (Konflikt-Index)";
    private static final String DEFINITION =
            "Misst, ob ein Thread flach-breit (Zuruf/Zustimmung) oder tief-schmal"
                    + " (Schlagabtausch) gewachsen ist.";

    private static final int MIN_COMMENTS = 10;
    private static final int THIN_COMMENTS = 20;

    private ReplyTreeShape() {
    }

    /**
     * Berechnet den Konflikt-Index über den Kommentar-Wald.
     * Mindestens {@value #MIN_COMMENTS} Kommentare, sonst {@link Optional#empty()}.
     */
    public static Optional<SignalReading> measure(List<Comment> comments) {
        if (comments == null || comments.size() < MIN_COMMENTS) {
            return Optional.empty();
        }
        Set<String> knownIds = new HashSet<>();
        for (Comment c : comments) {
            if (c != null && c.id() != null) {
                knownIds.add(c.id());
            }
        }
        Map<String, List<String>> children = new HashMap<>();
        List<String> roots = new ArrayList<>();
        for (Comment c : comments) {
            if (c == null || c.id() == null) {
                continue;
            }
            if (c.parentId() == null || !knownIds.contains(c.parentId())) {
                roots.add(c.id());
            } else {
                children.computeIfAbsent(c.parentId(), k -> new ArrayList<>()).add(c.id());
            }
        }
        if (roots.isEmpty()) {
            // Nur zyklische/defekte Kanten - keine auswertbare Baumstruktur.
            return Optional.empty();
        }

        // Breitensuche ab den Wurzeln (Wurzel = Tiefe 1); besuchte Menge schützt vor defekten Kanten.
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String root : roots) {
            depth.put(root, 1);
            queue.add(root);
        }
        int maxDepth = 1;
        long leafDepthSum = 0;
        int leafCount = 0;
        while (!queue.isEmpty()) {
            String id = queue.poll();
            int d = depth.get(id);
            maxDepth = Math.max(maxDepth, d);
            List<String> kids = children.get(id);
            if (kids == null || kids.isEmpty()) {
                leafDepthSum += d;
                leafCount++;
            } else {
                for (String kid : kids) {
                    if (depth.putIfAbsent(kid, d + 1) == null) {
                        queue.add(kid);
                    }
                }
            }
        }

        int n = depth.size();
        double meanLeafDepth = leafCount == 0 ? 0 : (double) leafDepthSum / leafCount;
        double rootBreadth = roots.size();
        double value = meanLeafDepth / (Math.log(n + 1) / Math.log(2));

        String interpretation = interpret(value, n);
        String formatted = MathKit.fmt(value, 2)
                + " (Konflikt-Index = mittlere Blatt-Tiefe / log2(n+1); max. Tiefe " + maxDepth
                + ", " + (int) rootBreadth + " Top-Level-Äste)";
        return Optional.of(new SignalReading(ID, TITLE, value, formatted, DEFINITION, interpretation));
    }

    private static String interpret(double value, int commentCount) {
        String band;
        if (value < 0.5) {
            band = "Zustimmungs-Echo: viele rufen dasselbe in den Raum, hoher Konsens,"
                    + " wenig neue Information.";
        } else if (value <= 1.0) {
            band = "Gemischte Wuchsform: teils Zuruf in die Breite, teils echte"
                    + " Antwort-Ketten.";
        } else {
            band = "Umkämpft: tiefe Argument-Ketten, echter Dissens über die Position -"
                    + " Volatilitätskandidat, die Story ist nicht ausgemacht.";
        }
        if (commentCount < THIN_COMMENTS) {
            band += " Vorsicht: nur " + commentCount + " Kommentare, die Baumform kann"
                    + " noch kippen.";
        }
        return band;
    }
}
