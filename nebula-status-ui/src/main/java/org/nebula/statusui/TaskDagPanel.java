package org.nebula.statusui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Visualizes the task execution DAG showing dependencies between subsystems.
 */
public final class TaskDagPanel extends JPanel {

    private static final Color BG_CARD = new Color(26, 26, 36);
    private static final Color BG_CHART = new Color(13, 13, 18);
    private static final Color TEXT_SECONDARY = new Color(156, 163, 175);
    private static final Color TEXT_MUTED = new Color(107, 114, 128);
    private static final Color BORDER_COLOR = new Color(42, 42, 58);
    private static final Color ACCENT = new Color(0, 255, 136);

    private final DagCanvas canvas;
    private final List<DagNode> nodes;
    private final List<int[]> edges;

    private static class DagNode {
        final String id;
        final String label;
        final double x;
        final double y;
        double pulsePhase;

        DagNode(String id, String label, double x, double y) {
            this.id = id;
            this.label = label;
            this.x = x;
            this.y = y;
            this.pulsePhase = Math.random() * Math.PI * 2;
        }
    }

    public TaskDagPanel() {
        setLayout(new BorderLayout());
        setBackground(BG_CARD);
        setBorder(new EmptyBorder(12, 16, 12, 16));

        JLabel title = new JLabel("Task DAG");
        title.setFont(new Font("Inter", Font.BOLD, 11));
        title.setForeground(TEXT_SECONDARY);
        add(title, BorderLayout.NORTH);

        canvas = new DagCanvas();
        canvas.setBackground(BG_CHART);
        add(canvas, BorderLayout.CENTER);

        // Define DAG structure
        nodes = new ArrayList<>();
        nodes.add(new DagNode("tick", "Tick", 0.5, 0.12));
        nodes.add(new DagNode("scheduler", "Scheduler", 0.2, 0.35));
        nodes.add(new DagNode("events", "Events", 0.8, 0.35));
        nodes.add(new DagNode("redstone", "Redstone", 0.1, 0.6));
        nodes.add(new DagNode("ai", "AI", 0.3, 0.6));
        nodes.add(new DagNode("entities", "Entities", 0.5, 0.6));
        nodes.add(new DagNode("chunks", "Chunks", 0.7, 0.6));
        nodes.add(new DagNode("players", "Players", 0.9, 0.6));
        nodes.add(new DagNode("finish", "Finish", 0.5, 0.85));

        edges = new ArrayList<>();
        edges.add(new int[]{0, 1}); // tick -> scheduler
        edges.add(new int[]{0, 2}); // tick -> events
        edges.add(new int[]{1, 3}); // scheduler -> redstone
        edges.add(new int[]{1, 4}); // scheduler -> ai
        edges.add(new int[]{1, 5}); // scheduler -> entities
        edges.add(new int[]{2, 6}); // events -> chunks
        edges.add(new int[]{2, 7}); // events -> players
        edges.add(new int[]{3, 8}); // redstone -> finish
        edges.add(new int[]{4, 8}); // ai -> finish
        edges.add(new int[]{5, 8}); // entities -> finish
        edges.add(new int[]{6, 8}); // chunks -> finish
        edges.add(new int[]{7, 8}); // players -> finish

        // Animate pulse
        javax.swing.Timer timer = new javax.swing.Timer(50, e -> {
            for (DagNode node : nodes) {
                node.pulsePhase += 0.1;
            }
            canvas.repaint();
        });
        timer.start();
    }

    private class DagCanvas extends JPanel {
        public DagCanvas() {
            setOpaque(true);
            setPreferredSize(new Dimension(0, 180));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int nodeRadius = 20;

            // Draw edges
            g2d.setStroke(new BasicStroke(1));
            for (int[] edge : edges) {
                DagNode from = nodes.get(edge[0]);
                DagNode to = nodes.get(edge[1]);
                int x1 = (int) (from.x * width);
                int y1 = (int) (from.y * height);
                int x2 = (int) (to.x * width);
                int y2 = (int) (to.y * height);

                // Gradient edge
                GradientPaint edgeGradient = new GradientPaint(
                    x1, y1, new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 30),
                    x2, y2, new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 60)
                );
                g2d.setPaint(edgeGradient);
                g2d.drawLine(x1, y1, x2, y2);
            }

            // Draw nodes
            for (DagNode node : nodes) {
                int x = (int) (node.x * width);
                int y = (int) (node.y * height);

                double pulse = (Math.sin(node.pulsePhase) + 1) * 0.5 * 0.4 + 0.6;

                // Outer glow
                g2d.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), (int) (40 * pulse)));
                g2d.fill(new Ellipse2D.Double(x - nodeRadius - 4, y - nodeRadius - 4, (nodeRadius + 4) * 2, (nodeRadius + 4) * 2));

                // Node circle
                g2d.setColor(new Color(26, 26, 36));
                g2d.fill(new Ellipse2D.Double(x - nodeRadius, y - nodeRadius, nodeRadius * 2, nodeRadius * 2));

                // Border with pulse
                g2d.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), (int) (80 + 80 * pulse)));
                g2d.setStroke(new BasicStroke(2));
                g2d.draw(new Ellipse2D.Double(x - nodeRadius, y - nodeRadius, nodeRadius * 2, nodeRadius * 2));

                // Label
                g2d.setColor(TEXT_MUTED);
                g2d.setFont(new Font("Inter", Font.PLAIN, 9));
                FontMetrics fm = g2d.getFontMetrics();
                int labelWidth = fm.stringWidth(node.label);
                g2d.drawString(node.label, x - labelWidth / 2, y + nodeRadius + 14);
            }

            g2d.dispose();
        }
    }
}
