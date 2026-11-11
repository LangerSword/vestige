package dev.jvd.gui;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A scripted run of the real desktop app under a virtual display.
 *
 * <p>It launches the actual {@link MainWindow}, presses the real
 * "Destroy this file" button, and saves a screenshot of the window once the
 * background work has finished. It is a development aid, not a test: it needs
 * a display, so it is deliberately <em>not</em> under {@code src/test} (the
 * headless CI suite must never depend on it).
 *
 * <p>Run it with a display available:
 * <pre>
 *   DISPLAY=:99 java -cp gui/target/gui-0.1.0.jar dev.jvd.gui.ScriptedRun /tmp/shot.png
 * </pre>
 */
public final class ScriptedRun {

    private ScriptedRun() {}

    public static void main(String[] args) throws Exception {
        Path shot = Path.of(args.length > 0 ? args[0] : "/tmp/gui-shot.png");
        Path dir = Files.createTempDirectory("jvd-gui-shot");
        Path victim = dir.resolve("my-private-notes.txt");
        Files.writeString(victim, "This is a file the user wanted gone.\n");

        AppPaths paths = new AppPaths(dir.resolve("work"), dir.resolve("log"));

        MainWindow[] holder = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            MainWindow w = new MainWindow(paths, SessionKey.generate());
            w.setVisible(true);
            holder[0] = w;
        });
        Thread.sleep(1200);
        MainWindow window = holder[0];

        java.awt.Point winPos = window.getLocationOnScreen();
        Rectangle bounds = new Rectangle(winPos.x, winPos.y, window.getWidth(), window.getHeight());
        System.out.println("window bounds : " + bounds);
        System.out.println("victim file   : " + victim);
        System.out.println("file exists   : " + Files.exists(victim));

        // The file chooser cannot be driven reliably by a script, so the
        // selection is made the same way the chooser would have made it.
        SwingUtilities.invokeAndWait(() -> window.setSelectedFile(victim));

        // Press the real button. The click goes through the same listener a
        // user's mouse would trigger, and the work happens on a background
        // thread exactly as it does in the real app.
        JButton destroy = findButton(window, "Destroy this file");
        if (destroy == null) throw new IllegalStateException("destroy button not found");
        Robot robot = new Robot();
        robot.setAutoDelay(80);
        java.awt.Point onScreen = destroy.getLocationOnScreen();
        robot.mouseMove(onScreen.x + destroy.getWidth() / 2, onScreen.y + destroy.getHeight() / 2);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        System.out.println("pressed the button at " + onScreen);

        // Wait for the background job to finish.
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && Files.exists(victim)) {
            Thread.sleep(200);
        }
        Thread.sleep(2500);

        System.out.println("file exists after : " + Files.exists(victim) + "   (must be false)");
        System.out.println("result area text  :");
        System.out.println(window.resultText().indent(4));
        System.out.println("progress area text:");
        System.out.println(window.progressText().indent(4));
        System.out.println("proof area text   :");
        System.out.println(window.proofText().indent(4));
        System.out.println("status text       : " + window.statusText());

        BufferedImage image = robot.createScreenCapture(bounds);
        ImageIO.write(image, "png", shot.toFile());
        System.out.println("screenshot: " + shot.toAbsolutePath());

        // Now press "Check my history" and capture that too.
        JButton check = findButton(window, "Check my history");
        if (check != null) {
            java.awt.Point p = check.getLocationOnScreen();
            robot.mouseMove(p.x + check.getWidth() / 2, p.y + check.getHeight() / 2);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(2000);
            System.out.println();
            System.out.println("--- after pressing 'Check my history' ---");
            System.out.println(window.resultText().indent(4));
            System.out.println("status text       : " + window.statusText());
            Path shot2 = shot.resolveSibling(shot.getFileName().toString().replace(".png", "-history.png"));
            ImageIO.write(robot.createScreenCapture(bounds), "png", shot2.toFile());
            System.out.println("screenshot: " + shot2.toAbsolutePath());
        }
        System.exit(0);
    }

    /** Finds a button by its label anywhere in the window's component tree. */
    private static JButton findButton(java.awt.Container root, String text) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof JButton b && text.equals(b.getText())) return b;
            if (c instanceof java.awt.Container container) {
                JButton found = findButton(container, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
