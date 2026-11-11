package dev.jvd.gui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The visual system: one dark palette, one type scale, one radius, one motion rule.
 *
 * <p>Everything the window draws comes from here, so the interface reads as one object
 * rather than a collection of default Swing widgets. The rules that matter:
 *
 * <ul>
 *   <li><b>One accent.</b> A single lime accent carries meaning: it marks the primary
 *       action, a completed step, and a verified result. Nothing else is lime, so lime
 *       always means "this is the thing you want".</li>
 *   <li><b>Colour is semantic, not decoration.</b> Amber means a limit, red means a
 *       failure, green means verified. The palette is tuned so those three stay
 *       distinguishable for a reader with colour vision deficiency, which is why
 *       the states are always paired with a word, never shown as colour alone.</li>
 *   <li><b>One radius.</b> Every rounded surface uses the same radius, so the window
 *       looks designed rather than assembled.</li>
 *   <li><b>Motion is motivated.</b> A control animates when something changes, so the
 *       interface confirms an action rather than simply repainting. Nothing loops.</li>
 * </ul>
 */
final class Theme {

    // --- surfaces ------------------------------------------------------
    static final Color BG = new Color(0x0B0D10);
    static final Color SURFACE = new Color(0x12151A);
    static final Color ELEVATED = new Color(0x171B22);
    static final Color FIELD = new Color(0x0E1116);
    static final Color BORDER = new Color(0x2A303B);
    static final Color BORDER_SOFT = new Color(0x20252E);

    // --- text ----------------------------------------------------------
    static final Color TEXT = new Color(0xECE9E2);
    static final Color MUTED = new Color(0xBCC3CE);
    static final Color FAINT = new Color(0x98A1B0);

    // --- meaning -------------------------------------------------------
    static final Color ACCENT = new Color(0xC8F56A);
    static final Color ACCENT_HOVER = new Color(0xDCFF8C);
    static final Color GREEN = new Color(0x7EE2A8);
    static final Color AMBER = new Color(0xF7CE85);
    static final Color RED = new Color(0xFF7B72);

    /** One radius for every rounded surface in the app. */
    static final int RADIUS = 10;

    private Theme() {
    }

    // --- type ----------------------------------------------------------

    static Font sans(int size) {
        return new Font(Font.SANS_SERIF, Font.PLAIN, size);
    }

    static Font sans(int size, int style) {
        return new Font(Font.SANS_SERIF, style, size);
    }

    /** The display face. A serif wordmark against a monospaced interface. */
    static Font serif(int size, int style) {
        return new Font(Font.SERIF, style, size);
    }

    static Font mono(int size) {
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    static Font mono(int size, int style) {
        return new Font(Font.MONOSPACED, style, size);
    }

    /** A small uppercase label that introduces a section. */
    static JLabel section(String text) {
        JLabel l = new JLabel(text.toUpperCase());
        l.setFont(mono(10, Font.BOLD));
        l.setForeground(FAINT);
        return l;
    }

    // --- surfaces ------------------------------------------------------

    /** A flat card with a hairline border and the app's radius. */
    static JPanel card(java.awt.LayoutManager layout) {
        JPanel p = new RoundedPanel(layout, SURFACE, BORDER_SOFT, RADIUS);
        p.setBorder(new EmptyBorder(14, 16, 14, 16));
        return p;
    }

    static Border fieldBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                new EmptyBorder(8, 10, 8, 10));
    }

    /**
     * Applies the palette to the Swing defaults.
     *
     * <p>Swing widgets that draw themselves (text fields, scroll bars, combo boxes) are
     * themed through the look and feel rather than by being replaced, so the app keeps
     * native behaviour while losing the default light appearance.
     */
    static void apply() {
        UIManager.put("Panel.background", BG);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.font", sans(13));
        UIManager.put("TextField.background", FIELD);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", ACCENT);
        UIManager.put("TextField.font", mono(12));
        UIManager.put("TextField.border", fieldBorder());
        UIManager.put("TextField.selectionBackground", new Color(0x33402A));
        UIManager.put("TextField.selectionForeground", TEXT);
        UIManager.put("TextArea.background", FIELD);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("TextArea.font", mono(12));
        UIManager.put("TextArea.caretForeground", ACCENT);
        UIManager.put("TextPane.background", FIELD);
        UIManager.put("TextPane.foreground", TEXT);
        UIManager.put("TextPane.font", mono(12));
        UIManager.put("TextPane.caretForeground", ACCENT);
        UIManager.put("CheckBox.background", SURFACE);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("CheckBox.font", sans(12));
        UIManager.put("Button.font", sans(12, Font.BOLD));
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.background", SURFACE);
        UIManager.put("ScrollBar.thumb", BORDER);
        UIManager.put("ScrollBar.track", SURFACE);
        UIManager.put("ScrollBar.thumbShadow", SURFACE);
        UIManager.put("ScrollBar.thumbHighlight", BORDER);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ToolTip.background", ELEVATED);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("OptionPane.messageFont", sans(13));
    }

    // --- controls ------------------------------------------------------

    /**
     * A button in the app's own style.
     *
     * <p>The primary variant carries the accent and is the one action the window wants
     * taken. The secondary variant is a quiet surface with a hairline border. Both have a
     * hover state, and both depress by a pixel, so pressing a button feels like pushing
     * something rather than clicking a rectangle.
     */
    static final class FlatButton extends JButton {

        private final boolean primary;
        private final Color base;
        private final Color hover;
        private boolean over;
        private boolean down;

        FlatButton(String text, boolean primary) {
            super(text);
            this.primary = primary;
            this.base = primary ? ACCENT : ELEVATED;
            this.hover = primary ? ACCENT_HOVER : new Color(0x202631);
            setFont(sans(13, Font.BOLD));
            setForeground(primary ? BG : TEXT);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(10, 16, 10, 16));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    over = true;
                    repaint();
                }

                @Override public void mouseExited(MouseEvent e) {
                    over = false;
                    down = false;
                    repaint();
                }

                @Override public void mousePressed(MouseEvent e) {
                    down = true;
                    repaint();
                }

                @Override public void mouseReleased(MouseEvent e) {
                    down = false;
                    repaint();
                }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = over ? hover : base;
            int dy = down ? 1 : 0;
            g2.setColor(fill);
            g2.fillRoundRect(0, dy, getWidth(), getHeight(), RADIUS, RADIUS);
            if (!primary) {
                g2.setColor(BORDER);
                g2.drawRoundRect(0, dy, getWidth() - 1, getHeight() - 1, RADIUS, RADIUS);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * A card that draws its own background.
     *
     * <p>Swing panels cannot have rounded, translucent backgrounds without painting them
     * by hand, so the app paints them.
     */
    static final class RoundedPanel extends JPanel {

        private final Color fill;
        private final Color border;
        private final int radius;

        RoundedPanel(java.awt.LayoutManager layout, Color fill, Color border, int radius) {
            super(layout);
            this.fill = fill;
            this.border = border;
            this.radius = radius;
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            if (border != null) {
                g2.setColor(border);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
