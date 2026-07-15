package org.nebula.statusui;

import org.nebula.core.metrics.MicroStepRecorder;
import org.nebula.core.metrics.TickTimeRecorder;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Path2D;

/**
 * Nebula Server Status Dashboard - A premium, modern GUI for monitoring
 * the Nebula deterministic parallel tick execution system.
 *
 * Features:
 * - Real-time DAG execution metrics
 * - Tick time and microstep charts
 * - Component health indicators
 * - Server information panel
 * - Premium dark theme with accent gradients
 */
public final class NebulaStatusPanel extends JPanel {

    private static NebulaStatusPanel instance;

    // Status provider
    private StatusProvider statusProvider;
    private boolean isConnected = false;
    private javax.swing.Timer updateTimer;

    // UI Components
    private MetricPanel tickTimePanel;
    private MetricPanel microstepPanel;
    private MetricPanel dagLayersPanel;
    private MetricPanel fidelityPanel;
    private ComponentHealthPanel healthPanel;
    private RealTimeChartPanel tickChartPanel;
    private RealTimeChartPanel microstepChartPanel;
    private ServerInfoPanel infoPanel;
    private StatusBadge statusBadge;
    private TaskDagPanel dagPanel;
    private JLabel tickCounterLabel;
    private JLabel timeLabel;

    // Premium color palette
    private static final Color BG_DARK = new Color(13, 13, 18);
    private static final Color BG_CARD = new Color(26, 26, 36);
    private static final Color BG_HEADER = new Color(22, 33, 62);
    private static final Color ACCENT_PURPLE = new Color(168, 85, 247);
    private static final Color ACCENT_BLUE = new Color(99, 102, 241);
    private static final Color ACCENT_CYAN = new Color(34, 211, 238);
    private static final Color ACCENT_AMBER = new Color(245, 158, 11);
    private static final Color TEXT_PRIMARY = new Color(229, 231, 235);
    private static final Color TEXT_SECONDARY = new Color(156, 163, 175);
    private static final Color TEXT_MUTED = new Color(107, 114, 128);
    private static final Color BORDER_COLOR = new Color(42, 42, 58);
    private static final Color SUCCESS = new Color(34, 197, 94);
    private static final Color DANGER = new Color(239, 68, 68);

    public NebulaStatusPanel() {
        instance = this;
        setLayout(new BorderLayout(0, 0));
        setBackground(BG_DARK);
        setBorder(new EmptyBorder(0, 0, 0, 0));

        add(createHeader(), BorderLayout.NORTH);
        add(createMainContent(), BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);
    }

    public static NebulaStatusPanel getInstance() {
        return instance;
    }

    private JComponent createHeader() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(BG_HEADER);
        header.setBorder(new EmptyBorder(12, 20, 12, 20));
        header.setPreferredSize(new Dimension(0, 72));

        // Logo and title section
        JPanel titleSection = new JPanel();
        titleSection.setLayout(new BoxLayout(titleSection, BoxLayout.Y_AXIS));
        titleSection.setBackground(BG_HEADER);

        // Logo
        JLabel logoLabel = new JLabel();
        logoLabel.setIcon(createLogoIcon());
        logoLabel.setBorder(new EmptyBorder(0, 0, 6, 0));

        JLabel title = new JLabel("NEBULA");
        title.setFont(new Font("JetBrains Mono", Font.BOLD, 22));
        title.setForeground(ACCENT_PURPLE);

        JLabel subtitle = new JLabel("Deterministic Parallel Execution");
        subtitle.setFont(new Font("Inter", Font.PLAIN, 11));
        subtitle.setForeground(TEXT_MUTED);

        JPanel titleBox = new JPanel();
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.setBackground(BG_HEADER);
        titleBox.add(logoLabel);
        titleBox.add(title);
        titleBox.add(subtitle);

        header.add(titleBox, BorderLayout.WEST);

        // Status badge
        statusBadge = new StatusBadge(false);

        // Tick counter
        tickCounterLabel = new JLabel("Tick: --");
        tickCounterLabel.setFont(new Font("JetBrains Mono", Font.PLAIN, 13));
        tickCounterLabel.setForeground(TEXT_SECONDARY);

        JPanel rightSection = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        rightSection.setOpaque(false);
        rightSection.add(statusBadge);
        rightSection.add(tickCounterLabel);

        header.add(rightSection, BorderLayout.EAST);

        return header;
    }

    private ImageIcon createLogoIcon() {
        // Create a simple diamond logo using vector graphics
        int size = 32;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw diamond shape
        int[] xPoints = {size/2, size-4, size/2, 4};
        int[] yPoints = {2, size/2, size-2, size/2};
        Path2D.Double diamond = new Path2D.Double();
        diamond.moveTo(xPoints[0], yPoints[0]);
        for (int i = 1; i < 4; i++) {
            diamond.lineTo(xPoints[i], yPoints[i]);
        }
        diamond.closePath();

        // Gradient fill for diamond
        GradientPaint gradient = new GradientPaint(0, 0, ACCENT_PURPLE, size, size, ACCENT_BLUE);
        g2d.setPaint(gradient);
        g2d.fill(diamond);

        // Add glow effect
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
        g2d.setStroke(new BasicStroke(3));
        g2d.draw(diamond);

        g2d.dispose();
        return new ImageIcon(img);
    }

    private JComponent createMainContent() {
        JPanel content = new JPanel(new BorderLayout(16, 0));
        content.setBackground(BG_DARK);
        content.setBorder(new EmptyBorder(16, 20, 16, 20));

        // Left column - Key metrics
        JPanel leftColumn = new JPanel();
        leftColumn.setLayout(new BoxLayout(leftColumn, BoxLayout.Y_AXIS));
        leftColumn.setBackground(BG_DARK);
        leftColumn.setPreferredSize(new Dimension(280, 0));

        tickTimePanel = new MetricPanel("Tick Time", "ms", ACCENT_BLUE);
        microstepPanel = new MetricPanel("Microsteps", "", ACCENT_CYAN);
        dagLayersPanel = new MetricPanel("DAG Layers", "", ACCENT_PURPLE);
        fidelityPanel = new MetricPanel("Fidelity Tier", "", ACCENT_AMBER);

        tickTimePanel.setValues("--", "--", "--", "--");
        microstepPanel.setValues("--", "--", "--", "--");
        dagLayersPanel.setValues("--", "--", "--", "--");
        fidelityPanel.setValues("--", "--", "--", "--");

        leftColumn.add(tickTimePanel);
        leftColumn.add(Box.createVerticalStrut(12));
        leftColumn.add(microstepPanel);
        leftColumn.add(Box.createVerticalStrut(12));
        leftColumn.add(dagLayersPanel);
        leftColumn.add(Box.createVerticalStrut(12));
        leftColumn.add(fidelityPanel);

        // Center column - Charts
        JPanel centerColumn = new JPanel();
        centerColumn.setLayout(new BoxLayout(centerColumn, BoxLayout.Y_AXIS));
        centerColumn.setBackground(BG_DARK);
        centerColumn.setPreferredSize(new Dimension(480, 0));

        tickChartPanel = new RealTimeChartPanel("Tick Execution Time (ms)", 60, ACCENT_BLUE);
        microstepChartPanel = new RealTimeChartPanel("Microstep Count", 60, ACCENT_CYAN);
        dagPanel = new TaskDagPanel();

        centerColumn.add(tickChartPanel);
        centerColumn.add(Box.createVerticalStrut(12));
        centerColumn.add(microstepChartPanel);
        centerColumn.add(Box.createVerticalStrut(12));
        centerColumn.add(dagPanel);

        // Right column - Health & Info
        JPanel rightColumn = new JPanel();
        rightColumn.setLayout(new BoxLayout(rightColumn, BoxLayout.Y_AXIS));
        rightColumn.setBackground(BG_DARK);
        rightColumn.setPreferredSize(new Dimension(300, 0));

        healthPanel = new ComponentHealthPanel();
        infoPanel = new ServerInfoPanel();

        rightColumn.add(healthPanel);
        rightColumn.add(Box.createVerticalStrut(12));
        rightColumn.add(infoPanel);

        content.add(leftColumn, BorderLayout.WEST);
        content.add(centerColumn, BorderLayout.CENTER);
        content.add(rightColumn, BorderLayout.EAST);

        return content;
    }

    private JComponent createStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(new Color(10, 10, 15));
        statusBar.setBorder(new EmptyBorder(6, 20, 6, 20));
        statusBar.setPreferredSize(new Dimension(0, 28));

        JLabel versionLabel = new JLabel("Nebula Status UI v0.2.0");
        versionLabel.setFont(new Font("Inter", Font.PLAIN, 10));
        versionLabel.setForeground(TEXT_MUTED);

        timeLabel = new JLabel();
        timeLabel.setFont(new Font("JetBrains Mono", Font.PLAIN, 10));
        timeLabel.setForeground(TEXT_MUTED);

        statusBar.add(versionLabel, BorderLayout.WEST);
        statusBar.add(timeLabel, BorderLayout.EAST);

        // Update time
        javax.swing.Timer clockTimer = new javax.swing.Timer(1000, e -> {
            timeLabel.setText(new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()));
        });
        clockTimer.start();

        return statusBar;
    }

    public void startUpdates() {
        if (updateTimer != null) {
            updateTimer.stop();
        }
        updateTimer = new javax.swing.Timer(500, e -> updateStatus());
        updateTimer.start();
    }

    public void stopUpdates() {
        if (updateTimer != null) {
            updateTimer.stop();
            updateTimer = null;
        }
    }

    private void updateStatus() {
        if (statusProvider == null || !isConnected) return;

        try {
            // Update tick time metrics
            TickTimeRecorder.Snapshot tickSnap = statusProvider.getTickTimeSnapshot();
            if (tickSnap != null && tickSnap.count() > 0) {
                tickTimePanel.setValues(
                    String.format("%.2f", tickSnap.avgMs()),
                    String.format("%.2f", tickSnap.p50Ms()),
                    String.format("%.2f", tickSnap.p95Ms()),
                    String.format("%.2f", tickSnap.p99Ms())
                );
                tickChartPanel.addData(tickSnap.avgMs());
            }

            // Update microstep metrics
            MicroStepRecorder.Snapshot microSnap = statusProvider.getMicroStepSnapshot();
            if (microSnap != null && microSnap.count() > 0) {
                microstepPanel.setValues(
                    String.format("%.1f", microSnap.avg()),
                    String.valueOf(microSnap.p50()),
                    String.valueOf(microSnap.p95()),
                    String.valueOf(microSnap.p99())
                );
                microstepChartPanel.addData(microSnap.max());
            }

            // Update DAG stats
            int layers = statusProvider.getLastLayersExecuted();
            dagLayersPanel.setValues(String.valueOf(layers), "--", "--", "--");

            // Update fidelity tier
            int tier = statusProvider.getFidelityTier();
            fidelityPanel.setValues("T" + tier, "--", "--", "--");

            // Update component health
            healthPanel.update(
                statusProvider.getComponentCount(),
                statusProvider.getToggleSourceCount(),
                statusProvider.getRedstoneStateSize(),
                statusProvider.getEntityStateSize(),
                statusProvider.getBlockEntityStateSize()
            );

            // Update server info
            infoPanel.update(
                statusProvider.getServerTick(),
                statusProvider.getOnlinePlayers(),
                statusProvider.getLoadedChunks()
            );

            tickCounterLabel.setText("Tick: " + formatNumber(statusProvider.getServerTick()));

        } catch (Exception e) {
            // Silently handle update errors
        }
    }

    private String formatNumber(long num) {
        if (num >= 1_000_000) {
            return String.format("%.1fM", num / 1_000_000.0);
        } else if (num >= 1_000) {
            return String.format("%.1fK", num / 1_000.0);
        }
        return String.valueOf(num);
    }

    public void setStatusProvider(StatusProvider provider) {
        this.statusProvider = provider;
        this.isConnected = true;
        statusBadge.setConnected(true);
    }

    public void disconnect() {
        this.isConnected = false;
        statusBadge.setConnected(false);
    }

    public boolean isConnected() {
        return isConnected;
    }
}
