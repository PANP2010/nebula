package org.nebula.statusui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Real-time line chart with smooth rendering and gradient styling.
 */
public final class RealTimeChartPanel extends JPanel {

    private static final int MAX_DATA_POINTS = 60;

    private final String title;
    private final Color chartColor;
    private final Queue<Double> dataBuffer;
    private final JLabel currentValueLabel;
    private final JLabel minValueLabel;
    private final JLabel maxValueLabel;
    private final JLabel avgValueLabel;
    private final ChartCanvas canvas;

    private static final Color BG_CARD = new Color(26, 26, 36);
    private static final Color BG_CHART = new Color(18, 18, 28);
    private static final Color BG_STAT = new Color(18, 18, 26);
    private static final Color TEXT_PRIMARY = new Color(229, 231, 235);
    private static final Color TEXT_SECONDARY = new Color(156, 163, 175);
    private static final Color TEXT_MUTED = new Color(107, 114, 128);
    private static final Color BORDER_COLOR = new Color(42, 42, 58);
    private static final Color GRID_COLOR = new Color(35, 35, 50);

    public RealTimeChartPanel(String title, int maxPoints, Color color) {
        this.title = title;
        this.chartColor = color;
        this.dataBuffer = new LinkedList<>();

        setLayout(new BorderLayout(0, 0));
        setBackground(BG_CARD);
        setBorder(new LineBorder(BORDER_COLOR, 1));
        setPreferredSize(new Dimension(0, 180));

        // Header
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BG_CARD);
        header.setBorder(new EmptyBorder(12, 16, 8, 16));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Inter", Font.BOLD, 11));
        titleLabel.setForeground(TEXT_SECONDARY);

        currentValueLabel = new JLabel("--");
        currentValueLabel.setFont(new Font("JetBrains Mono", Font.BOLD, 16));
        currentValueLabel.setForeground(chartColor);
        currentValueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        header.add(titleLabel, BorderLayout.WEST);
        header.add(currentValueLabel, BorderLayout.EAST);

        // Chart canvas
        canvas = new ChartCanvas();
        canvas.setBackground(BG_CHART);
        canvas.setBorder(new EmptyBorder(8, 16, 8, 16));

        // Stats footer
        JPanel footer = new JPanel(new GridLayout(1, 3, 12, 0));
        footer.setBackground(BG_CARD);
        footer.setBorder(new EmptyBorder(8, 16, 12, 16));

        JPanel minBox = createStatBox("MIN");
        JPanel maxBox = createStatBox("MAX");
        JPanel avgBox = createStatBox("AVG");

        minValueLabel = (JLabel) ((JPanel) minBox.getComponent(1)).getComponent(0);
        maxValueLabel = (JLabel) ((JPanel) maxBox.getComponent(1)).getComponent(0);
        avgValueLabel = (JLabel) ((JPanel) avgBox.getComponent(1)).getComponent(0);

        footer.add(minBox);
        footer.add(maxBox);
        footer.add(avgBox);

        add(header, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    private JPanel createStatBox(String label) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(BG_STAT);
        box.setBorder(new EmptyBorder(6, 8, 6, 8));

        JLabel lbl = new JLabel(label);
        lbl.setFont(new Font("Inter", Font.PLAIN, 9));
        lbl.setForeground(TEXT_MUTED);
        lbl.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel val = new JLabel("--");
        val.setFont(new Font("JetBrains Mono", Font.BOLD, 12));
        val.setForeground(TEXT_PRIMARY);
        val.setAlignmentX(Component.CENTER_ALIGNMENT);

        box.add(lbl);
        box.add(val);

        return box;
    }

    public void addData(double value) {
        dataBuffer.offer(value);
        if (dataBuffer.size() > MAX_DATA_POINTS) {
            dataBuffer.poll();
        }

        currentValueLabel.setText(String.format("%.2f", value));

        List<Double> values = new LinkedList<>(dataBuffer);
        double min = values.stream().min(Double::compare).orElse(0.0);
        double max = values.stream().max(Double::compare).orElse(0.0);
        double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        minValueLabel.setText(String.format("%.2f", min));
        maxValueLabel.setText(String.format("%.2f", max));
        avgValueLabel.setText(String.format("%.2f", avg));

        canvas.repaint();
    }

    public void clear() {
        dataBuffer.clear();
        currentValueLabel.setText("--");
        minValueLabel.setText("--");
        maxValueLabel.setText("--");
        avgValueLabel.setText("--");
        canvas.repaint();
    }

    private class ChartCanvas extends JPanel {
        public ChartCanvas() {
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int padding = 4;

            g2d.setColor(GRID_COLOR);
            for (int i = 0; i <= 4; i++) {
                int y = padding + (height - 2 * padding) * i / 4;
                g2d.drawLine(padding, y, width - padding, y);
            }

            if (dataBuffer.size() > 1) {
                List<Double> values = new LinkedList<>(dataBuffer);
                double maxValue = values.stream().max(Double::compare).orElse(1.0);
                double minValue = values.stream().min(Double::compare).orElse(0.0);
                double range = Math.max(maxValue - minValue, 0.001);

                int[] xPoints = new int[values.size()];
                int[] yPoints = new int[values.size()];

                for (int i = 0; i < values.size(); i++) {
                    xPoints[i] = padding + (width - 2 * padding) * i / Math.max(1, values.size() - 1);
                    double normalized = (values.get(i) - minValue) / range;
                    yPoints[i] = height - padding - (int) ((height - 2 * padding) * normalized);
                }

                Path2D gradientPath = new Path2D.Double();
                gradientPath.moveTo(xPoints[0], height - padding);
                for (int i = 0; i < xPoints.length; i++) {
                    gradientPath.lineTo(xPoints[i], yPoints[i]);
                }
                gradientPath.lineTo(xPoints[xPoints.length - 1], height - padding);
                gradientPath.closePath();

                GradientPaint fillGradient = new GradientPaint(
                    0, 0, new Color(chartColor.getRed(), chartColor.getGreen(), chartColor.getBlue(), 60),
                    0, height, new Color(chartColor.getRed(), chartColor.getGreen(), chartColor.getBlue(), 0)
                );
                g2d.setPaint(fillGradient);
                g2d.fill(gradientPath);

                g2d.setStroke(new BasicStroke(3));
                g2d.setColor(new Color(chartColor.getRed(), chartColor.getGreen(), chartColor.getBlue(), 40));
                g2d.drawPolyline(xPoints, yPoints, xPoints.length);

                g2d.setStroke(new BasicStroke(2));
                g2d.setColor(chartColor);
                g2d.drawPolyline(xPoints, yPoints, xPoints.length);

                g2d.setColor(chartColor);
                for (int i = 0; i < xPoints.length; i += Math.max(1, xPoints.length / 10)) {
                    g2d.fillOval(xPoints[i] - 3, yPoints[i] - 3, 6, 6);
                }
            }

            g2d.dispose();
        }
    }
}
