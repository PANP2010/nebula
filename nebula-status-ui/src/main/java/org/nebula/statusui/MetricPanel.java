package org.nebula.statusui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;

/**
 * A premium metric card with gradient header and glow effect.
 * Displays a key metric with its value and supporting statistics.
 */
public final class MetricPanel extends JPanel {

    private final JLabel titleLabel;
    private final JLabel valueLabel;
    private final JLabel avgValueLabel;
    private final JLabel p50ValueLabel;
    private final JLabel p95ValueLabel;
    private final JLabel p99ValueLabel;
    private final Color accentColor;

    private static final Color BG_CARD = new Color(26, 26, 36);
    private static final Color BG_STAT = new Color(18, 18, 26);
    private static final Color TEXT_PRIMARY = new Color(229, 231, 235);
    private static final Color TEXT_SECONDARY = new Color(156, 163, 175);
    private static final Color TEXT_MUTED = new Color(107, 114, 128);
    private static final Color BORDER_COLOR = new Color(42, 42, 58);

    public MetricPanel(String title, String unit, Color accentColor) {
        this.accentColor = accentColor;
        setLayout(new BorderLayout(0, 0));
        setBackground(BG_CARD);
        setPreferredSize(new Dimension(280, 110));

        // Header with gradient accent
        JPanel header = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();
                int w = getWidth();
                int h = getHeight();

                GradientPaint gradient = new GradientPaint(
                    0, 0, new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 50),
                    w, 0, new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0)
                );
                g2d.setPaint(gradient);
                g2d.fillRect(0, 0, w, h);

                g2d.setColor(accentColor);
                g2d.fillRect(0, 0, w, 3);

                g2d.dispose();
            }
        };
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(12, 16, 8, 16));

        titleLabel = new JLabel(title.toUpperCase());
        titleLabel.setFont(new Font("Inter", Font.BOLD, 10));
        titleLabel.setForeground(TEXT_SECONDARY);

        valueLabel = new JLabel("--");
        valueLabel.setFont(new Font("JetBrains Mono", Font.BOLD, 32));
        valueLabel.setForeground(TEXT_PRIMARY);

        header.add(titleLabel);
        header.add(Box.createVerticalStrut(4));
        header.add(valueLabel);

        // Body with statistics
        JPanel body = new JPanel(new GridLayout(1, 4, 6, 0));
        body.setBackground(BG_CARD);
        body.setBorder(new EmptyBorder(8, 16, 12, 16));

        JPanel avgBox = createStatBox("AVG");
        JPanel p50Box = createStatBox("P50");
        JPanel p95Box = createStatBox("P95");
        JPanel p99Box = createStatBox("P99");

        avgValueLabel = (JLabel) ((JPanel) avgBox.getComponent(1)).getComponent(0);
        p50ValueLabel = (JLabel) ((JPanel) p50Box.getComponent(1)).getComponent(0);
        p95ValueLabel = (JLabel) ((JPanel) p95Box.getComponent(1)).getComponent(0);
        p99ValueLabel = (JLabel) ((JPanel) p99Box.getComponent(1)).getComponent(0);

        body.add(avgBox);
        body.add(p50Box);
        body.add(p95Box);
        body.add(p99Box);

        setBorder(new LineBorder(BORDER_COLOR, 1));

        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    private JPanel createStatBox(String label) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(BG_STAT);
        box.setBorder(new EmptyBorder(6, 4, 6, 4));

        JLabel lbl = new JLabel(label);
        lbl.setFont(new Font("Inter", Font.PLAIN, 9));
        lbl.setForeground(TEXT_MUTED);
        lbl.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel val = new JLabel("--");
        val.setFont(new Font("JetBrains Mono", Font.BOLD, 13));
        val.setForeground(TEXT_PRIMARY);
        val.setAlignmentX(Component.CENTER_ALIGNMENT);

        box.add(lbl);
        box.add(Box.createVerticalStrut(2));
        box.add(val);

        return box;
    }

    public void setValues(String avg, String p50, String p95, String p99) {
        valueLabel.setText(avg);
        avgValueLabel.setText(avg);
        p50ValueLabel.setText(p50);
        p95ValueLabel.setText(p95);
        p99ValueLabel.setText(p99);
    }
}
