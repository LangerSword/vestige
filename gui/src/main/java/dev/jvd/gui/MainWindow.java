package dev.jvd.gui;

import dev.jvd.evidence.EvidenceRecord;
import dev.jvd.evidence.Signer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * The desktop window.
 *
 * <p>Design intent, in the user's own terms: <b>pick a file, press one
 * button, watch it happen.</b> The window therefore leads with the file
 * picker and the one button that matters; everything else (the progress log,
 * the result, the proof) is arranged below it so the primary action is never
 * hidden.
 *
 * <p>Three things this class is careful about:
 * <ol>
 *   <li><b>No jargon reaches the screen.</b> Every string shown here is
 *       plain language. The cryptographic machinery lives in
 *       {@link DestroyRunner}; the window only displays what that class
 *       reports.</li>
 *   <li><b>Nothing blocks the interface.</b> Every piece of real work runs on
 *       a {@link javax.swing.SwingWorker} background thread; the event
 *       dispatch thread only ever paints. This is a correctness requirement,
 *       not a nicety — the destruction involves file I/O and encryption that
 *       would otherwise freeze the window for the duration.</li>
 *   <li><b>Errors are shown, never thrown at the user.</b> A failed run puts a
 *       plain-language explanation in the result panel and leaves the app
 *       usable.</li>
 * </ol>
 */
public final class MainWindow extends JFrame {

    /** Swing serialisation id; the window is never serialised, but the warning is noise. */
    private static final long serialVersionUID = 1L;

    private final AppPaths paths;
    private final SessionKey sessionKey;
    private final DestroyRunner runner;
    private final EvidenceVerifier verifier;
    private final LogKeyStore keyStore;

    // --- widgets -------------------------------------------------------
    private final JTextField fileField = new JTextField();
    private final JButton chooseButton = new JButton("Choose file...");
    private final JButton destroyButton = new JButton("Destroy this file");
    private final JButton verifyButton = new JButton("Check my history");
    private final JTextArea progressArea = new JTextArea();
    private final StageTimeline timeline = new StageTimeline();
    private final ProgressBar progress = new ProgressBar();
    private final JLabel progressCaption = new JLabel("");
    private final JTextArea resultArea = new JTextArea();
    private final JTextArea proofArea = new JTextArea();
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel keyLabel = new JLabel();

    public MainWindow(AppPaths paths, SessionKey sessionKey) {
        super("Secure File Destruction");
        this.paths = paths;
        this.sessionKey = sessionKey;
        this.runner = new DestroyRunner(paths, sessionKey);
        this.verifier = new EvidenceVerifier(paths.logDir());
        this.keyStore = new LogKeyStore(paths.logDir());

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        buildUi();
        setSize(760, 720);
        setLocationRelativeTo(null);

        // When the app closes, the session key is wiped from memory. By then it
        // cannot open anything either way — the destruction destroyed the keys
        // that mattered — but leaving key bytes in a heap dump is exactly the
        // habit this project exists to discourage.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                sessionKey.wipe();
            }
        });
    }

    // ---------- layout ----------

    private void buildUi() {
        Theme.apply();

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 14, 20));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildMiddlePanel(), BorderLayout.CENTER);
        root.add(buildBottomPanel(), BorderLayout.SOUTH);

        setContentPane(root);
    }

    /** The header: the product name, the one-line promise, and the honest limit. */
    private JPanel buildHeader() {
        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(Theme.BG);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Destroy a file securely");
        title.setFont(Theme.serif(30, Font.BOLD));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(title);
        left.add(Box.createVerticalStrut(6));

        JLabel tagline = new JLabel("DESTROY  ->  MEASURE  ->  PROVE");
        tagline.setFont(Theme.mono(10, Font.BOLD));
        tagline.setForeground(Theme.ACCENT);
        tagline.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(tagline);

        // The limit sits next to the promise, not buried at the end. A person should
        // read it before they trust the result, and they should not have to hunt for it.
        JLabel limit = new JLabel("Logical level only: we check the software side. "
                + "Copies the drive keeps for itself cannot be checked from here.");
        limit.setFont(Theme.sans(11));
        limit.setForeground(Theme.AMBER);
        limit.setAlignmentX(Component.LEFT_ALIGNMENT);

        head.add(left, BorderLayout.CENTER);
        JPanel limitWrap = new JPanel(new BorderLayout());
        limitWrap.setOpaque(false);
        limitWrap.add(limit, BorderLayout.NORTH);
        head.add(limitWrap, BorderLayout.SOUTH);
        return head;
    }

    /**
     * The action area: pick a file, press the button.
     *
     * <p>Laid out with nested panels rather than one grid, because the two
     * things the user must do — choose a file and press the button — must
     * never overlap or be pushed off-screen, whatever the window size.
     */
    private JPanel buildTopPanel() {
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        // Row: [File to destroy:] [path............] [Choose file...]
        JPanel fileRow = new JPanel(new BorderLayout(8, 0));
        fileRow.setOpaque(false);
        JLabel fileLabel = new JLabel("File to destroy:");
        fileLabel.setForeground(Theme.MUTED);
        fileRow.add(fileLabel, BorderLayout.WEST);
        fileField.setEditable(false);
        fileField.setForeground(Theme.TEXT);
        fileField.setBackground(Theme.FIELD);
        fileField.setCaretColor(Theme.ACCENT);
        fileField.setFont(Theme.mono(12));
        fileField.setBorder(Theme.fieldBorder());
        fileField.setToolTipText("The file that will be destroyed.");
        fileRow.add(fileField, BorderLayout.CENTER);
        chooseButton.setFont(Theme.sans(12, Font.BOLD));
        chooseButton.setForeground(Theme.TEXT);
        chooseButton.setBackground(Theme.ELEVATED);
        chooseButton.setFocusPainted(false);
        chooseButton.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        chooseButton.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        chooseButton.addActionListener(e -> chooseFile());
        fileRow.add(chooseButton, BorderLayout.EAST);
        top.add(fileRow);
        top.add(Box.createVerticalStrut(10));

        // Row: [Destroy this file]
        JPanel actionRow = new JPanel(new BorderLayout());
        actionRow.setOpaque(false);
        destroyButton.setFont(Theme.sans(13, Font.BOLD));
        destroyButton.setForeground(Theme.BG);
        destroyButton.setBackground(Theme.ACCENT);
        destroyButton.setFocusPainted(false);
        destroyButton.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        destroyButton.setBorder(BorderFactory.createEmptyBorder(11, 18, 11, 18));
        destroyButton.addActionListener(e -> startDestroy());
        actionRow.add(destroyButton, BorderLayout.WEST);
        top.add(actionRow);
        top.add(Box.createVerticalStrut(8));

        keyLabel.setText("Your files are protected with a key that is generated automatically and never shown.");
        keyLabel.setFont(Theme.sans(11, Font.ITALIC));
        keyLabel.setForeground(Theme.FAINT);
        keyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(keyLabel);

        return top;
    }

    /** The scrollable areas: the stepper, the result, and the proof. */
    private JPanel buildMiddlePanel() {
        JPanel middle = new JPanel(new GridBagLayout());
        middle.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 0, 6, 0);
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 1;

        // The stepper leads, because it is what the user watches while it works.
        c.gridx = 0;
        c.gridy = 0;
        c.weighty = 0.45;
        JPanel steps = new JPanel(new BorderLayout(0, 10));
        steps.setOpaque(false);
        steps.add(Theme.section("What we are doing"), BorderLayout.NORTH);
        steps.add(timeline, BorderLayout.CENTER);
        JPanel barRow = new JPanel(new BorderLayout(10, 0));
        barRow.setOpaque(false);
        progress.setPreferredSize(new Dimension(0, 6));
        barRow.add(progress, BorderLayout.CENTER);
        progressCaption.setFont(Theme.mono(10));
        progressCaption.setForeground(Theme.FAINT);
        barRow.add(progressCaption, BorderLayout.EAST);
        steps.add(barRow, BorderLayout.SOUTH);
        middle.add(steps, c);

        c.gridy = 1;
        c.weighty = 0.27;
        middle.add(titled("Result", resultArea), c);

        c.gridy = 2;
        c.weighty = 0.28;
        middle.add(titled("The proof", proofArea), c);

        return middle;
    }

    private JPanel buildBottomPanel() {
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER_SOFT));
        statusLabel.setForeground(Theme.MUTED);
        statusLabel.setFont(Theme.mono(11));
        bottom.add(statusLabel, BorderLayout.WEST);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.LINE_AXIS));
        verifyButton.setFont(Theme.sans(12, Font.BOLD));
        verifyButton.setForeground(Theme.TEXT);
        verifyButton.setBackground(Theme.ELEVATED);
        verifyButton.setFocusPainted(false);
        verifyButton.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        verifyButton.setToolTipText("Check that your history of destructions has not been altered.");
        verifyButton.addActionListener(e -> startVerify());
        right.add(verifyButton);
        right.add(Box.createHorizontalStrut(8));
        JButton openLog = new JButton("Where are my files kept?");
        openLog.setFont(Theme.sans(12, Font.BOLD));
        openLog.setForeground(Theme.TEXT);
        openLog.setBackground(Theme.ELEVATED);
        openLog.setFocusPainted(false);
        openLog.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        openLog.addActionListener(e -> showPaths());
        right.add(openLog);
        bottom.add(right, BorderLayout.EAST);
        return bottom;
    }

    private JPanel titled(String title, JTextArea area) {
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Theme.mono(12));
        area.setForeground(Theme.TEXT);
        area.setBackground(Theme.FIELD);
        area.setCaretColor(Theme.ACCENT);
        area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        panel.add(Theme.section(title), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(null);
        scroll.setBackground(Theme.FIELD);
        scroll.getViewport().setBackground(Theme.FIELD);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    // ---------- actions ----------

    /** The file chooser. Nothing is destroyed until the user presses the button. */
    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose the file to destroy");
        chooser.setFileFilter(new FileNameExtensionFilter("Files", "*"));
        chooser.setAcceptAllFileFilterUsed(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            setSelectedFile(chooser.getSelectedFile().toPath());
        }
    }

    /**
     * The name of a planned stage, used by the progress readout.
     *
     * <p>The name is the stage's own title rather than a separate label, so the readout
     * and the timeline cannot disagree about what is happening.
     */
    private static String stageName(int index) {
        var stages = Friendly.plannedStages();
        return index >= 0 && index < stages.size() ? stages.get(index).title() : "";
    }

    /** Puts a file into the "file to destroy" field and clears the panels. */
    void setSelectedFile(java.nio.file.Path file) {
        fileField.setText(file.toAbsolutePath().toString());
        progressArea.setText("");
        resultArea.setText("");
        proofArea.setText("");
        statusLabel.setText("Ready when you are.");
    }

    /** The path currently shown in the "file to destroy" field. */
    java.nio.file.Path selectedFile() {
        return fileField.getText().isBlank() ? null : java.nio.file.Path.of(fileField.getText());
    }

    /**
     * Destroys a temporary file so the window can be shown with real content.
     *
     * <p>Used by the screenshot mode: a picture of the app with empty panels proves
     * nothing, and a picture of a mock-up would be dishonest. This runs the real
     * transaction on a real temporary file, so the result and the proof on screen are
     * what the tool actually produces.
     */
    void runDemoDestruction() {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("jvd-demo-");
            java.nio.file.Path file = dir.resolve("tax-return-2026.pdf");
            java.nio.file.Files.writeString(file,
                    "This is a sample document for the screenshot. It is destroyed for real.\n".repeat(20));
            setSelectedFile(file);
            startDestroy();
        } catch (Exception e) {
            statusLabel.setText("Could not prepare the demo: " + e.getMessage());
        }
    }

    /**
     * Saves the window to a PNG.
     *
     * <p>The frame is painted offscreen rather than grabbed from the screen, so the
     * capture works the same way on a machine with no compositor and does not show
     * whatever else is on the desktop.
     */
    void capture(String file) throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                Math.max(1, getWidth()), Math.max(1, getHeight()),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        printAll(g);
        g.dispose();
        javax.imageio.ImageIO.write(image, "png", new java.io.File(file));
    }

    // --- text accessors, used by the scripted run (a development aid) ---

    String progressText() {
        return progressArea.getText();
    }

    String resultText() {
        return resultArea.getText();
    }

    String proofText() {
        return proofArea.getText();
    }

    String statusText() {
        return statusLabel.getText();
    }

    /**
     * Runs the destruction on a background thread.
     *
     * <p>The whole point of the SwingWorker here is that the destruction
     * (file read, encryption, overwrite, delete, signing) can take a while for
     * a large file. Doing it on the event dispatch thread would freeze the
     * window and make the app look broken.
     */
    private void startDestroy() {
        String chosen = fileField.getText().isBlank() ? null : fileField.getText();
        if (chosen == null) {
            showError("Please choose a file first.");
            return;
        }
        java.nio.file.Path source;
        try {
            source = java.nio.file.Path.of(chosen);
        } catch (Exception e) {
            showError("That file path is not valid.");
            return;
        }

        setBusy(true);
        progressArea.setText("");
        resultArea.setText("");
        proofArea.setText("");
        statusLabel.setText("Working...");
        // Show the plan before the work starts, so the wait is something the user can
        // follow rather than a frozen window.
        timeline.setStages(Friendly.plannedStages());
        timeline.reset();
        timeline.markRunning(0);
        progress.reset();

        new SwingWorker<DestroyResult, StageEvent>() {

            @Override
            protected DestroyResult doInBackground() throws Exception {
                return runner.destroy(source, event -> publish(event));
            }

            @Override
            protected void process(java.util.List<StageEvent> chunks) {
                for (StageEvent e : chunks) {
                    progressArea.append(e.line() + "\n");
                    progressArea.setCaretPosition(progressArea.getDocument().getLength());
                    int index = Friendly.stageIndex(e);
                    if (index >= 0) {
                        timeline.markDone(index);
                        if (index + 1 < Friendly.plannedStages().size()) {
                            timeline.markRunning(index + 1);
                        }
                        double fraction = (index + 1.0) / Friendly.plannedStages().size();
                        progress.setProgress(fraction,
                                Math.round(fraction * 100) + "%  " + stageName(index));
                    }
                }
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    DestroyResult result = get();
                    // The bar settles on its final value with a name, so the reader sees
                    // what the run ended with rather than just a full bar.
                    if (result.destroyed()) {
                        progress.finish("100%  Finished");
                    } else {
                        progress.reset();
                    }
                    showResult(result);
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                    progress.reset();
                    showError(Friendly.describeFailure(cause));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    progress.reset();
                    showError("The job was interrupted.");
                }
            }
        }.execute();
    }

    /** Runs the offline evidence check on a background thread. */
    private void startVerify() {
        setBusy(true);
        statusLabel.setText("Checking your history...");

        new SwingWorker<VerifyResult, Void>() {

            @Override
            protected VerifyResult doInBackground() {
                return verifier.verify(keyStore.logPublicKey(), keyStore.witnessPublicKey());
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    VerifyResult result = get();
                    resultArea.setText(result.headline() + "\n\n" + result.explanation()
                            + "\n\n" + Friendly.LIMITATION);
                    statusLabel.setText(result.ok() ? "Your history is intact." : "Please read the result above.");
                    EvidenceRecord last = verifier.lastRecord();
                    if (last != null) {
                        proofArea.setText(ProofView.of(last, verifySignature(last)).asText());
                    }
                } catch (Exception e) {
                    showError(Friendly.describeFailure(e));
                }
            }
        }.execute();
    }

    private boolean verifySignature(dev.jvd.evidence.EvidenceRecord record) {
        var pub = keyStore.logPublicKey();
        return pub != null && dev.jvd.evidence.Signer.verify(record, pub);
    }

    // ---------- display ----------

    private void showResult(DestroyResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(result.fileName()).append('\n');
        sb.append(result.headline()).append("\n\n");
        sb.append(result.whatWasVerified()).append("\n\n");
        sb.append(result.limitation());
        if (result.errorMessage() != null) {
            sb.append("\n\nDetails: ").append(result.errorMessage());
        }
        resultArea.setText(sb.toString());
        resultArea.setCaretPosition(0);

        if (result.proof() != null) {
            proofArea.setText(result.proof().asText());
        }
        statusLabel.setText(result.destroyed() ? "Finished." : "Please read the result above.");
    }

    private void showError(String message) {
        resultArea.setText("Something went wrong.\n\n" + message);
        resultArea.setCaretPosition(0);
        statusLabel.setText("Please read the result above.");
    }

    private void showPaths() {
        resultArea.setText("Your working files are kept here:\n\n"
                + paths.workRoot() + "\n\n"
                + "Your history of destructions is kept here:\n\n"
                + paths.logDir() + "\n\n"
                + "Nothing in either place can open a destroyed file.");
        resultArea.setCaretPosition(0);
    }

    /** Disables the buttons while a job runs, so a second job cannot start. */
    private void setBusy(boolean busy) {
        destroyButton.setEnabled(!busy);
        chooseButton.setEnabled(!busy);
        verifyButton.setEnabled(!busy);
        destroyButton.setText(busy ? "Working..." : "Destroy this file");
    }

    // ---------- entry point ----------

    /**
     * Launches the app.
     *
     * <p>Headless safety: if there is no display, we do not throw. A Swing
     * app cannot run without a display, so the honest behaviour is to print a
     * clear message and exit non-zero rather than to die with a
     * HeadlessException stack trace — which is also what keeps the module
     * buildable and testable in CI, where there is no X server.
     */
    public static void main(String[] args) {
        int code = launch();
        if (code != 0) System.exit(code);
    }

    /**
     * Starts the app, or explains why it cannot start.
     *
     * <p>Split from {@link #main} so the headless behaviour can be asserted in
     * a test without the test JVM being killed by {@code System.exit}.
     *
     * <p>Two different failures are handled, because they look the same to a
     * user but are different bugs:
     * <ul>
     *   <li><b>No display configured</b> — {@code DISPLAY} is unset, so
     *       {@link GraphicsEnvironment#isHeadless()} is true. This is the
     *       normal CI case.</li>
     *   <li><b>Display configured but unreachable</b> — {@code DISPLAY} points
     *       at an X server that is not running. {@code isHeadless()} says
     *       false, and the failure only surfaces when the toolkit is touched,
     *       as an {@link java.awt.AWTError}. Left unhandled it prints a stack
     *       trace at the user, which is exactly the "the app is broken"
     *       impression this app is meant to avoid.</li>
     * </ul>
     *
     * @return 0 when the window was opened, 2 when there is no usable display
     */
    public static int launch() {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(HEADLESS_MESSAGE);
            return 2;
        }
        try {
            // Touching the toolkit is what actually connects to the display, so
            // this is the check that catches an unreachable X server.
            java.awt.Toolkit.getDefaultToolkit();
        } catch (Throwable t) {
            System.err.println(HEADLESS_MESSAGE);
            System.err.println("The display could not be opened: " + t.getMessage());
            return 2;
        }
        try {
            SwingUtilities.invokeLater(() -> {
                AppPaths paths = AppPaths.defaultPaths();
                SessionKey key = SessionKey.generate();
                MainWindow window = new MainWindow(paths, key);
                window.setVisible(true);
            });
        } catch (Throwable t) {
            System.err.println(HEADLESS_MESSAGE);
            System.err.println("The window could not be opened: " + t.getMessage());
            return 2;
        }
        return 0;
    }

    /**
     * Opens the window, optionally running a destruction first and saving a screenshot.
     *
     * <p>The screenshot mode exists because the window cannot be reviewed by reading its
     * source: a layout bug, a clipped label, or a colour that fights the text is only
     * visible in the rendered frame. It is also how the README gets a picture of the
     * real application rather than a mock-up.
     *
     * @param args {@code --screenshot=FILE} writes a PNG and exits;
     *              {@code --demo} destroys a temporary file first so the result and
     *              proof panels are populated
     * @return 0 on success, 2 when there is no usable display
     */
    public static int launch(String[] args) {
        String screenshot = null;
        boolean demo = false;
        for (String a : args) {
            if (a.startsWith("--screenshot=")) screenshot = a.substring("--screenshot=".length());
            else if (a.equals("--demo")) demo = true;
        }
        final String shotFile = screenshot;
        final boolean runDemo = demo;
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(HEADLESS_MESSAGE);
            return 2;
        }
        try {
            // Touching the toolkit is what actually connects to the display, so
            // this is the check that catches an unreachable X server.
            java.awt.Toolkit.getDefaultToolkit();
        } catch (Throwable t) {
            System.err.println(HEADLESS_MESSAGE);
            System.err.println("The display could not be opened: " + t.getMessage());
            return 2;
        }
        try {
            SwingUtilities.invokeAndWait(() -> {
                AppPaths paths = AppPaths.defaultPaths();
                SessionKey key = SessionKey.generate();
                MainWindow window = new MainWindow(paths, key);
                window.setVisible(true);
                if (runDemo) {
                    window.runDemoDestruction();
                }
                if (shotFile != null) {
                    // Let the layout settle before the frame is captured.
                    javax.swing.Timer t = new javax.swing.Timer(900, e -> {
                        try {
                            window.capture(shotFile);
                            System.out.println("SHOT_OK " + shotFile);
                        } catch (Exception ex) {
                            System.err.println("could not write the screenshot: " + ex);
                        }
                        System.exit(0);
                    });
                    t.setRepeats(false);
                    t.start();
                }
            });
        } catch (Throwable t) {
            System.err.println(HEADLESS_MESSAGE);
            System.err.println("The window could not be opened: " + t.getMessage());
            return 2;
        }
        return 0;
    }

    /** What the app says when it is started on a machine with no display. */
    public static final String HEADLESS_MESSAGE =
            "The desktop app needs a graphical display.\n"
                    + "On a headless machine use the command-line tool instead: "
                    + "java -jar cli-0.1.0.jar destroy-file <path> --work <dir> --log <dir>";
}
