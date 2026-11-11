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
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        root.add(buildTopPanel(), BorderLayout.NORTH);
        root.add(buildMiddlePanel(), BorderLayout.CENTER);
        root.add(buildBottomPanel(), BorderLayout.SOUTH);

        setContentPane(root);
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
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Destroy a file securely");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(title);
        top.add(Box.createVerticalStrut(8));

        // Row: [File to destroy:] [path............] [Choose file...]
        JPanel fileRow = new JPanel(new BorderLayout(8, 0));
        JLabel fileLabel = new JLabel("File to destroy:");
        fileRow.add(fileLabel, BorderLayout.WEST);
        fileField.setEditable(false);
        fileField.setToolTipText("The file that will be destroyed.");
        fileRow.add(fileField, BorderLayout.CENTER);
        chooseButton.addActionListener(e -> chooseFile());
        fileRow.add(chooseButton, BorderLayout.EAST);
        top.add(fileRow);
        top.add(Box.createVerticalStrut(8));

        // Row: [Destroy this file]
        JPanel actionRow = new JPanel(new BorderLayout());
        destroyButton.setFont(destroyButton.getFont().deriveFont(Font.BOLD, 13f));
        destroyButton.addActionListener(e -> startDestroy());
        actionRow.add(destroyButton, BorderLayout.WEST);
        top.add(actionRow);
        top.add(Box.createVerticalStrut(6));

        keyLabel.setText("Your files are protected with a key that is generated automatically and never shown.");
        keyLabel.setFont(keyLabel.getFont().deriveFont(Font.ITALIC, 11f));
        keyLabel.setForeground(new java.awt.Color(90, 90, 90));
        keyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(keyLabel);

        return top;
    }

    /** The scrollable areas: progress, result, proof. */
    private JPanel buildMiddlePanel() {
        JPanel middle = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 4, 6, 4);
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 1;

        c.gridx = 0; c.gridy = 0; c.weighty = 0.35;
        middle.add(titled("What we are doing", progressArea), c);

        c.gridy = 1; c.weighty = 0.3;
        middle.add(titled("Result", resultArea), c);

        c.gridy = 2; c.weighty = 0.35;
        middle.add(titled("The proof", proofArea), c);

        return middle;
    }

    private JPanel buildBottomPanel() {
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(statusLabel, BorderLayout.WEST);
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.LINE_AXIS));
        verifyButton.setToolTipText("Check that your history of destructions has not been altered.");
        verifyButton.addActionListener(e -> startVerify());
        right.add(verifyButton);
        right.add(Box.createHorizontalStrut(8));
        JButton openLog = new JButton("Where are my files kept?");
        openLog.addActionListener(e -> showPaths());
        right.add(openLog);
        bottom.add(right, BorderLayout.EAST);
        return bottom;
    }

    private JPanel titled(String title, JTextArea area) {
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        panel.add(label, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(700, 130));
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
                }
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    DestroyResult result = get();
                    showResult(result);
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                    showError(Friendly.describeFailure(cause));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
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

    /** What the app says when it is started on a machine with no display. */
    public static final String HEADLESS_MESSAGE =
            "The desktop app needs a graphical display.\n"
                    + "On a headless machine use the command-line tool instead: "
                    + "java -jar cli-0.1.0.jar destroy-file <path> --kek-hex <hex> --work <dir> --log <dir>";
}
