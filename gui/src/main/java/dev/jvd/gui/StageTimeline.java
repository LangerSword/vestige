package dev.jvd.gui;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The stepper that shows what the tool is doing while it does it.
 *
 * <p>A destruction takes real time, and a frozen window tells the user nothing: they
 * cannot tell a slow operation from a hung one. Each stage appears as it is reached, the
 * marker fills with the accent, and the running stage shows a single sweeping arc. The
 * animation is short and it stops when the work stops, so it confirms progress rather than
 * performing it.
 *
 * <p>The whole plan is visible before the run begins. Seeing the steps in advance is what
 * turns the wait into something a person can follow.
 */
final class StageTimeline extends JComponent {

    /** Where a stage sits in its own lifecycle. */
    enum Phase { PENDING, RUNNING, DONE, FAILED }

    /** How long a stage takes to fade in, in milliseconds. */
    private static final int FADE_MS = 260;

    private static final int ROW_HEIGHT = 46;
    private static final int MARKER_RADIUS = 6;

    private final List<Row> rows = new ArrayList<>();
    private final Timer timer;
    private long lastTick = System.nanoTime();

    private static final class Row {
        final String title;
        final String detail;
        Phase phase = Phase.PENDING;
        double fade = 0.0;      // 0..1 entry animation
        float sweep = 0.0f;     // 0..1 running shimmer

        Row(String title, String detail) {
            this.title = title;
            this.detail = detail;
        }
    }

    StageTimeline() {
        timer = new Timer(16, e -> tick());
        timer.setCoalesce(true);
    }

    /** Declares the stages up front, so the user sees the shape of the work. */
    void setStages(List<StageEvent> stages) {
        rows.clear();
        for (StageEvent s : stages) rows.add(new Row(s.title(), s.detail()));
        timer.stop();
        revalidate();
        repaint();
    }

    /** Marks a stage as running. */
    void markRunning(int index) {
        if (index < 0 || index >= rows.size()) return;
        for (int i = 0; i < index; i++) {
            if (rows.get(i).phase == Phase.PENDING) rows.get(i).phase = Phase.DONE;
        }
        rows.get(index).phase = Phase.RUNNING;
        startTicking();
    }

    /** Marks a stage as done; it fades in with the accent. */
    void markDone(int index) {
        if (index < 0 || index >= rows.size()) return;
        Row r = rows.get(index);
        r.phase = Phase.DONE;
        r.fade = 0.0;
        startTicking();
    }

    /** Marks a stage as failed. */
    void markFailed(int index) {
        if (index < 0 || index >= rows.size()) return;
        Row r = rows.get(index);
        r.phase = Phase.FAILED;
        r.fade = 0.0;
        startTicking();
    }

    /** Clears every stage back to pending. */
    void reset() {
        for (Row r : rows) {
            r.phase = Phase.PENDING;
            r.fade = 0.0;
            r.sweep = 0.0f;
        }
        timer.stop();
        repaint();
    }

    private void startTicking() {
        if (!timer.isRunning()) {
            lastTick = System.nanoTime();
            timer.start();
        }
    }

    private void tick() {
        long now = System.nanoTime();
        float dt = (float) ((now - lastTick) / 1_000_000_000.0);
        lastTick = now;
        boolean active = false;
        for (Row r : rows) {
            if (r.fade < 1.0) {
                r.fade = Math.min(1.0, r.fade + (dt * 1000.0f) / FADE_MS);
                active = true;
            }
            if (r.phase == Phase.RUNNING) {
                r.sweep = (r.sweep + dt * 1.5f) % 1.0f;
                active = true;
            }
        }
        if (!active) timer.stop();
        repaint();
    }

    @Override public Dimension getPreferredSize() {
        return new Dimension(420, Math.max(1, rows.size()) * ROW_HEIGHT + 12);
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        if (rows.isEmpty()) {
            g2.setFont(Theme.sans(13));
            g2.setColor(Theme.FAINT);
            g2.drawString("The steps of a destruction appear here.", 4, 22);
            g2.drawString("Choose a file and press Destroy this file.", 4, 42);
            g2.dispose();
            return;
        }

        int markerX = 12;
        int top = 8;

        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int y = top + i * ROW_HEIGHT;
            // A pending stage is visible but quiet: the plan is legible before the run.
            float alpha = r.phase == Phase.PENDING ? 0.45f : (float) r.fade;
            if (alpha <= 0f) continue;

            if (i < rows.size() - 1) {
                g2.setColor(withAlpha(r.phase == Phase.DONE ? Theme.ACCENT : Theme.BORDER_SOFT, alpha * 0.6f));
                g2.setStroke(new BasicStroke(1));
                g2.draw(new Line2D.Float(markerX + MARKER_RADIUS, y + 14,
                        markerX + MARKER_RADIUS, y + ROW_HEIGHT));
            }

            drawMarker(g2, markerX + MARKER_RADIUS, y + 10, r, alpha);

            g2.setFont(Theme.sans(13, Font.BOLD));
            g2.setColor(withAlpha(r.phase == Phase.FAILED ? Theme.RED : Theme.TEXT, alpha));
            g2.drawString(r.title, markerX + 22, y + 14);

            g2.setFont(Theme.sans(11));
            g2.setColor(withAlpha(Theme.MUTED, alpha * 0.95f));
            g2.drawString(ellipsize(g2, r.detail, getWidth() - markerX - 30), markerX + 22, y + 30);
        }
        g2.dispose();
    }

    private void drawMarker(Graphics2D g2, int cx, int cy, Row r, float alpha) {
        Color ring;
        Color fill;
        switch (r.phase) {
            case DONE -> {
                ring = Theme.ACCENT;
                fill = Theme.ACCENT;
            }
            case FAILED -> {
                ring = Theme.RED;
                fill = Theme.RED;
            }
            case RUNNING -> {
                ring = Theme.ACCENT;
                fill = Theme.BG;
            }
            default -> {
                ring = Theme.BORDER;
                fill = Theme.BG;
            }
        }
        g2.setColor(withAlpha(ring, alpha));
        g2.fill(new Ellipse2D.Float(cx - MARKER_RADIUS, cy - MARKER_RADIUS,
                MARKER_RADIUS * 2f, MARKER_RADIUS * 2f));
        if (r.phase != Phase.DONE && r.phase != Phase.FAILED) {
            g2.setColor(withAlpha(fill, alpha));
            g2.fill(new Ellipse2D.Float(cx - MARKER_RADIUS + 2, cy - MARKER_RADIUS + 2,
                    (MARKER_RADIUS - 2) * 2f, (MARKER_RADIUS - 2) * 2f));
        }
        if (r.phase == Phase.RUNNING) {
            // One arc sweeping around the marker: it reads as "working" without
            // implying an unknown duration.
            g2.setColor(withAlpha(Theme.ACCENT, alpha * 0.9f));
            g2.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawArc(cx - MARKER_RADIUS - 4, cy - MARKER_RADIUS - 4,
                    (MARKER_RADIUS + 4) * 2, (MARKER_RADIUS + 4) * 2,
                    (int) (r.sweep * 360), 90);
        }
    }

    private static Color withAlpha(Color c, float alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(),
                Math.max(0, Math.min(255, Math.round(c.getAlpha() * alpha))));
    }

    private static String ellipsize(Graphics2D g2, String text, int maxWidth) {
        if (maxWidth <= 0 || text == null) return "";
        if (g2.getFontMetrics().stringWidth(text) <= maxWidth) return text;
        int n = text.length();
        while (n > 1 && g2.getFontMetrics().stringWidth(text.substring(0, n) + "...") > maxWidth) {
            n--;
        }
        return text.substring(0, n) + "...";
    }
}
