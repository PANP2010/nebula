package org.nebula.statusui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.basic.BasicProgressBarUI;
import java.awt.*;

/**
 * Panel showing the health and status of individual Nebula components.
 */
public final class ComponentHealthPanel extends JPanel {

    private final ComponentRow redstoneRow;
    private final ComponentRow entityRow;
    private final ComponentRow blockEntityRow;
    private final ComponentRow toggleSourceRow;
    private final ComponentRow stateHasherRow;

    private static final Color BG_CARD = new Color(26, 26, 36);
    private static final Color TEXT_PRIMARY = new Color(229, 231, 235);
    private static final Color TEXT_SECONDARY = new Color(156, 163, 175);
    private static final Color BORDER_COLOR = new Color(42, 42, 58);

    public ComponentHealthPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(BG_CARD);
        setBorder(new LineBorder(BORDER_COLOR, 1));

        // Title
        JLabel title = new JLabel("COMPONENT HEALTH");
        title.setFont(new Font("Inter", Font.BOLD, 10));
        title.setForeground(TEXT_SECONDARY);
        title.setBorder(new EmptyBorder(12, 16, 10, 16));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Component rows
        redstoneRow = new ComponentRow("Redstone", new Color(99, 102, 241), "#6366f1");
        entityRow = new ComponentRow("Entities", new Color(34, 211, 238), "#22d3ee");
        blockEntityRow = new ComponentRow("Block Entities", new Color(168, 85, 247), "#a855f7");
        toggleSourceRow = new ComponentRow("Toggle Sources", new Color(245, 158, 11), "#f59e0b");
        stateHasherRow = new ComponentRow("State Hashes", new Color(236, 72, 153), "#ec4899");

        add(title);
        add(redstoneRow);
        add(entityRow);
        add(blockEntityRow);
        add(toggleSourceRow);
        add(stateHasherRow);
    }

    public void update(int componentCount, int toggleSources, int redstoneStateSize,
                       int entityStateSize, int blockEntityStateSize) {
        redstoneRow.update(componentCount, redstoneStateSize);
        entityRow.update(entityStateSize, 0);
        blockEntityRow.update(blockEntityStateSize, 0);
        toggleSourceRow.update(toggleSources, 0);
        stateHasherRow.update(redstoneStateSize, 0);
    }

    private static final class ComponentRow extends JPanel {
        private final JLabel statusDot;
        private final JLabel nameLabel;
        private final JProgressBar progressBar;
        private final JLabel valueLabel;
        private final Color baseColor;

        private static final Color BG_CARD = new Color(26, 26, 36);
        private static final Color TEXT_PRIMARY = new Color(229, 231, 235);
        private static final Color BORDER_COLOR = new Color(42, 42, 58);

        public ComponentRow(String name, Color statusColor, String barColorHex) {
            this.baseColor = statusColor;
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setBackground(BG_CARD);
            setBorder(new EmptyBorder(6, 16, 6, 16));
            setAlignmentX(Component.LEFT_ALIGNMENT);

            // Status indicator dot
            statusDot = new JLabel() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setColor(getBackground());
                    g2d.fillOval(0, 0, getWidth(), getHeight());
                    g2d.setColor(statusColor);
                    g2d.fillOval(1, 1, getWidth() - 2, getHeight() - 2);
                    g2d.dispose();
                }
            };
            statusDot.setPreferredSize(new Dimension(10, 10));
            statusDot.setMinimumSize(new Dimension(10, 10));
            statusDot.setMaximumSize(new Dimension(10, 10));
            statusDot.setBackground(statusColor);

            nameLabel = new JLabel(name);
            nameLabel.setFont(new Font("Inter", Font.PLAIN, 11));
            nameLabel.setForeground(TEXT_PRIMARY);
            nameLabel.setPreferredSize(new Dimension(90, 0));

            progressBar = new JProgressBar(0, 100);
            progressBar.setValue(0);
            progressBar.setPreferredSize(new Dimension(70, 6));
            progressBar.setMinimumSize(new Dimension(70, 6));
            progressBar.setMaximumSize(new Dimension(70, 6));
            progressBar.setBorderPainted(false);
            progressBar.setUI(new BasicProgressBarUI() {
                @Override
                protected void paintIndeterminate(Graphics g, JComponent c) {}
            });

            Color barColor = Color.decode(barColorHex);
            progressBar.setForeground(barColor);
            progressBar.setBackground(new Color(42, 42, 58));

            valueLabel = new JLabel("0");
            valueLabel.setFont(new Font("JetBrains Mono", Font.BOLD, 11));
            valueLabel.setForeground(TEXT_PRIMARY);
            valueLabel.setPreferredSize(new Dimension(50, 0));
            valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

            add(Box.createHorizontalStrut(4));
            add(statusDot);
            add(Box.createHorizontalStrut(10));
            add(nameLabel);
            add(Box.createHorizontalGlue());
            add(progressBar);
            add(Box.createHorizontalStrut(10));
            add(valueLabel);
        }

        public void update(int primary, int secondary) {
            valueLabel.setText(formatNumber(primary));
            double normalized = Math.min(1.0, primary / 10000.0);
            progressBar.setValue((int) (normalized * 100));

            if (primary > 0) {
                statusDot.setBackground(baseColor);
            } else {
                statusDot.setBackground(new Color(75, 85, 99));
            }
        }

        private String formatNumber(int num) {
            if (num >= 1000) {
                return String.format("%.1fK", num / 1000.0);
            }
            return String.valueOf(num);
        }
    }
}
