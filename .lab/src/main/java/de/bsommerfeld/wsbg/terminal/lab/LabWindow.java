package de.bsommerfeld.wsbg.terminal.lab;

import com.google.inject.Guice;
import com.google.inject.Injector;
import de.bsommerfeld.wsbg.terminal.agent.OllamaServerManager;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The editorial harness as a small native desktop window (Swing — no browser,
 * no JavaFX).
 *
 * <p>One text field per Reddit thread link; <b>+</b> adds another field; <b>Los</b>
 * (bottom-right) runs the whole real pipeline over every filled-in link
 * ({@link LabRunner}: ingest → {@code ClusterEngine} → {@code EditorialAgent})
 * and streams the trace into the output area below. The window stays open between
 * runs so Ollama and the models stay warm; <b>Reset</b> clears the accumulated
 * cluster/headline state.
 */
public final class LabWindow extends JFrame {

    private final JPanel fieldsPanel = new JPanel();
    private final List<JTextField> fields = new ArrayList<>();
    private final JTextArea output = new JTextArea();
    private final JLabel status = new JLabel("Starte…");
    private final JButton addButton = new JButton("+");
    private final JButton resetButton = new JButton("Reset");
    private final JButton goButton = new JButton("Los");

    /** One background thread: model load, runs, and reset all serialise here, off the EDT. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lab-worker");
        t.setDaemon(true);
        return t;
    });

    private Injector injector;
    private OllamaServerManager ollama;
    private volatile LabRunner runner;

    public LabWindow() {
        super("editorial-lab");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdownAndExit(); }
        });

        buildUi();
        setSize(760, 660);
        setMinimumSize(new Dimension(560, 460));
        setLocationRelativeTo(null);
    }

    // ---- UI construction ----

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        // --- top: the link fields + the "+" button ---
        JPanel north = new JPanel(new BorderLayout(0, 6));
        JLabel header = new JLabel("Reddit-Thread-Links — ein Link pro Feld");
        north.add(header, BorderLayout.NORTH);

        fieldsPanel.setLayout(new BoxLayout(fieldsPanel, BoxLayout.Y_AXIS));
        JScrollPane fieldsScroll = new JScrollPane(fieldsPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        fieldsScroll.setBorder(BorderFactory.createEmptyBorder());
        fieldsScroll.setPreferredSize(new Dimension(0, 150));
        north.add(fieldsScroll, BorderLayout.CENTER);

        addButton.setToolTipText("Weiteres Link-Feld hinzufügen");
        addButton.addActionListener(e -> addField(""));
        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        addRow.add(addButton);
        north.add(addRow, BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);

        // --- center: live trace output ---
        output.setEditable(false);
        output.setLineWrap(true);
        output.setWrapStyleWord(true);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane outScroll = new JScrollPane(output);
        outScroll.setBorder(BorderFactory.createTitledBorder("Trace"));
        root.add(outScroll, BorderLayout.CENTER);

        // --- bottom: status (left) + Reset / Los (right) ---
        JPanel south = new JPanel(new BorderLayout());
        status.setForeground(status.getForeground().darker());
        south.add(status, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        resetButton.setToolTipText("Cluster, Threads und Headlines leeren (Ollama bleibt warm)");
        resetButton.addActionListener(e -> reset());
        goButton.setFont(goButton.getFont().deriveFont(Font.BOLD, 14f));
        goButton.addActionListener(e -> run());
        buttons.add(resetButton);
        buttons.add(goButton);
        south.add(buttons, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(goButton);
        setBusy(true); // stays busy until models are loaded
        addField("");
    }

    /** Adds one link row: a text field plus a small remove button. */
    private void addField(String text) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        JTextField tf = new JTextField(text);
        JButton remove = new JButton("✕");
        remove.setMargin(new java.awt.Insets(0, 6, 0, 6));
        remove.setToolTipText("Dieses Feld entfernen");
        remove.addActionListener(e -> removeField(tf));
        row.add(tf, BorderLayout.CENTER);
        row.add(remove, BorderLayout.EAST);

        fields.add(tf);
        fieldsPanel.add(row);
        fieldsPanel.add(Box.createVerticalStrut(6));
        fieldsPanel.revalidate();
        fieldsPanel.repaint();
        tf.requestFocusInWindow();
    }

    private void removeField(JTextField tf) {
        if (fields.size() <= 1) { // always keep one
            tf.setText("");
            return;
        }
        Component row = tf.getParent();
        int idx = indexOfComponent(row);
        fieldsPanel.remove(row);
        if (idx >= 0 && idx < fieldsPanel.getComponentCount()) {
            fieldsPanel.remove(idx); // the trailing vertical strut
        }
        fields.remove(tf);
        fieldsPanel.revalidate();
        fieldsPanel.repaint();
    }

    private int indexOfComponent(Component c) {
        Component[] all = fieldsPanel.getComponents();
        for (int i = 0; i < all.length; i++) if (all[i] == c) return i;
        return -1;
    }

    // ---- lifecycle / actions ----

    /** Kicks off model loading in the background; call after the window is shown. */
    public void boot() {
        worker.submit(() -> {
            try {
                append("Starte Ollama + lade Modelle…\n");
                injector = Guice.createInjector(new LabModule());
                ollama = injector.getInstance(OllamaServerManager.class);
                runner = new LabRunner(injector, this::append);
                SwingUtilities.invokeLater(() -> {
                    status.setText("bereit — Modell: " + runner.agentModelName());
                    setBusy(false);
                });
            } catch (Throwable t) {
                append("\nFEHLER beim Start: " + t + "\n(Läuft Ollama? Sind die Modelle installiert?)\n");
                SwingUtilities.invokeLater(() -> status.setText("Fehler — siehe Trace"));
            }
        });
    }

    private void run() {
        if (runner == null) return;
        List<String> urls = new ArrayList<>();
        for (JTextField tf : fields) {
            String s = tf.getText().trim();
            if (!s.isEmpty()) urls.add(s);
        }
        if (urls.isEmpty()) {
            status.setText("Bitte mindestens einen Link eintragen.");
            return;
        }
        setBusy(true);
        status.setText("läuft… (" + urls.size() + " Link(s))");
        worker.submit(() -> {
            try {
                runner.run(urls, this::append);
            } catch (Throwable t) {
                append("FATAL: " + t + "\n");
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    status.setText("bereit");
                });
            }
        });
    }

    private void reset() {
        if (runner == null) return;
        setBusy(true);
        worker.submit(() -> {
            try {
                runner.reset(this::append);
            } catch (Throwable t) {
                append("FATAL: " + t + "\n");
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    status.setText("bereit");
                });
            }
        });
    }

    private void setBusy(boolean busy) {
        goButton.setEnabled(!busy);
        resetButton.setEnabled(!busy);
    }

    /** EDT-safe append; splits multi-line emissions so the trace stays line-clean. */
    private void append(String chunk) {
        if (chunk == null) return;
        SwingUtilities.invokeLater(() -> {
            output.append(chunk.endsWith("\n") ? chunk : chunk + "\n");
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

    private void shutdownAndExit() {
        status.setText("beende…");
        worker.shutdownNow();
        try {
            if (injector != null) {
                safe(() -> injector.getInstance(RedditRepository.class).shutdown());
                safe(() -> injector.getInstance(AgentRepository.class).shutdown());
            }
            if (ollama != null) safe(ollama::shutdown);
        } finally {
            dispose();
            System.exit(0);
        }
    }

    private static void safe(Runnable r) {
        try { r.run(); } catch (Throwable ignored) { /* best-effort teardown */ }
    }
}
