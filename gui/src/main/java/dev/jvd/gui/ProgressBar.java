package dev.jvd.gui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * A thin progress bar that eases towards the real value.
 *
 * <p>The bar never lies about progress. It animates towards the value the work reports
 * and never past it, so the filled portion is always at or below the truth. A bar that
 * jumped straight to 100% would read as a frozen application during a long encryption.
 *
 * <p>The motion is frame-rate independent: the same wall-clock duration looks the same
 * whether the machine renders at 30 frames or 144.
 */
final class ProgressBar extends JComponent {

    private static final int FRAME_MS = 16;
    private static final double EASE = 0.18;

    private double target;
    private double shown;
    private long lastTick = System.nanoTime();
    private final Timer timer;

    /** The label that names what the bar is doing, so it cannot be read as a divider. */
    private final JLabel caption = new JLabel("");

    ProgressBar() {
        setPreferredSize(new Dimension(0, 6));
        setOpaque(false);
        timer = new Timer(FRAME_MS, e -> tick());
        timer.setCoalesce(true);
    }

    /** Sets the true progress in [0,1]. The bar eases towards it; it never overshoots. */
    void setProgress(double value, String label) {
        target = Math.max(0.0, Math.min(1.0, value));
        caption.setText(label == null ? "" : label);
        if (!timer.isRunning()) {
            lastTick = System.nanoTime();
            timer.start();
        }
        repaint();
    }

    /** Fills the bar completely and stops the animation. */
    void finish(String label) {
        target = 1.0;
        shown = 1.0;
        caption.setText(label == null ? "" : label);
        timer.stop();
        repaint();
    }

    /** Empties the bar and stops the animation. */
    void reset() {
        target = 0.0;
        shown = 0.0;
        caption.setText("");
        timer.stop();
        repaint();
    }

    private void tick() {
        long now = System.nanoTime();
        double dt = (now - lastTick) / 1_000_000_000.0;
        lastTick = now;
        double factor = 1.0 - Math.pow(1.0 - EASE, dt * 60.0);
        shown += (target - shown) * factor;
        if (Math.abs(target - shown) < 0.002) {
            shown = target;
            timer.stop();
        }
        repaint();
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        int r = h;
        // The track is visible, so a full bar reads as "finished" rather than as a rule
        // drawn across the window.
        g2.setColor(Theme.ELEVATED);
        g2.fill(new RoundRectangle2D.Float(0, 0, w, h, r, r));
        double frac = Math.max(0.0, Math.min(1.0, shown));
        int fillW = (int) Math.round(w * frac);
        if (fillW > 0) {
            g2.setColor(Theme.ACCENT);
            g2.fill(new RoundRectangle2D.Float(0, 0, Math.max(fillW, h), h, r, r));
        }
        g2.dispose();
    }
}
