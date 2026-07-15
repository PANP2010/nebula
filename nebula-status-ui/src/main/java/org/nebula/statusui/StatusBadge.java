package org.nebula.statusui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Animated status indicator showing connection state.
 */
public final class StatusBadge extends JPanel {

    private final JLabel dot;
    private final JLabel label;
    private boolean connected;

    private static final Color SUCCESS = new Color(34, 197, 94);
    private static final Color DANGER = new Color(239, 68, 68);

    public StatusBadge(boolean connected) {
        this.connected = connected;
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBackground(new Color(18, 18, 26));
        setBorder(new EmptyBorder(5, 10, 5, 10));
        setOpaque(true);

        dot = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color color = connected ? SUCCESS : DANGER;
                g2d.setColor(color);
                g2d.fillOval(1, 1, getWidth() - 2, getHeight() - 2);
                g2d.dispose();
            }
        };
        dot.setPreferredSize(new Dimension(9, 9));
        dot.setMinimumSize(new Dimension(9, 9));
        dot.setMaximumSize(new Dimension(9, 9));

        label = new JLabel(connected ? "Connected" : "Disconnected");
        label.setFont(new Font("Inter", Font.PLAIN, 11));
        label.setForeground(connected ? SUCCESS : DANGER);

        add(dot);
        add(Box.createHorizontalStrut(6));
        add(label);

        updateGlow();
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
        label.setText(connected ? "Connected" : "Disconnected");

        Color color = connected ? SUCCESS : DANGER;
        label.setForeground(color);
        dot.setBackground(color);
        dot.repaint();

        updateGlow();
    }

    private void updateGlow() {
        if (connected) {
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(34, 197, 94, 30), 1),
                new EmptyBorder(5, 10, 5, 10)
            ));
        } else {
            setBorder(new EmptyBorder(5, 10, 5, 10));
        }
    }
}
