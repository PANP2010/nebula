package org.nebula.statusui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;

/**
 * Panel displaying server information and runtime details.
 */
public final class ServerInfoPanel extends JPanel {

    private final InfoRow tickRow;
    private final InfoRow playersRow;
    private final InfoRow chunksRow;
    private final InfoRow uptimeRow;
    private final InfoRow memoryRow;
    private final InfoRow threadsRow;

    private final long startTime = System.currentTimeMillis();

    private static final Color BG_CARD = new Color(26, 26, 36);
    private static final Color TEXT_PRIMARY = new Color(229, 231, 235);
    private static final Color TEXT_SECONDARY = new Color(156, 163, 175);
    private static final Color TEXT_MUTED = new Color(107, 114, 128);
    private static final Color BORDER_COLOR = new Color(42, 42, 58);

    public ServerInfoPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(BG_CARD);
        setBorder(new LineBorder(BORDER_COLOR, 1));

        // Title
        JLabel title = new JLabel("SERVER INFORMATION");
        title.setFont(new Font("Inter", Font.BOLD, 10));
        title.setForeground(TEXT_SECONDARY);
        title.setBorder(new EmptyBorder(12, 16, 10, 16));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        tickRow = new InfoRow("Server Tick");
        playersRow = new InfoRow("Online Players");
        chunksRow = new InfoRow("Loaded Chunks");
        uptimeRow = new InfoRow("Uptime");
        memoryRow = new InfoRow("Memory Usage");
        threadsRow = new InfoRow("Active Threads");

        add(title);
        add(tickRow);
        add(playersRow);
        add(chunksRow);
        add(uptimeRow);
        add(memoryRow);
        add(threadsRow);

        // Start uptime timer
        Timer timer = new Timer(1000, e -> uptimeRow.setValue(formatUptime()));
        timer.start();
    }

    public void update(long tick, int players, int chunks) {
        tickRow.setValue(formatNumber(tick));
        playersRow.setValue(String.valueOf(players));
        chunksRow.setValue(formatNumber(chunks));
        memoryRow.setValue(formatMemory());
        threadsRow.setValue(String.valueOf(Thread.activeCount()));
    }

    private String formatNumber(long num) {
        if (num >= 1_000_000) {
            return String.format("%.1fM", num / 1_000_000.0);
        } else if (num >= 1_000) {
            return String.format("%.1fK", num / 1_000.0);
        }
        return String.valueOf(num);
    }

    private String formatUptime() {
        long elapsed = System.currentTimeMillis() - startTime;
        long seconds = elapsed / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return String.format("%dd %02d:%02d:%02d", days, hours % 24, minutes % 60, seconds % 60);
        }
        return String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
    }

    private String formatMemory() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        double percent = (used * 100.0) / max;
        return String.format("%.1f%%", percent);
    }

    private static final class InfoRow extends JPanel {
        private final JLabel nameLabel;
        private final JLabel valueLabel;

        private static final Color BG_CARD = new Color(26, 26, 36);
        private static final Color TEXT_PRIMARY = new Color(229, 231, 235);
        private static final Color TEXT_SECONDARY = new Color(156, 163, 175);

        public InfoRow(String name) {
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setBackground(BG_CARD);
            setBorder(new EmptyBorder(5, 16, 5, 16));
            setAlignmentX(Component.LEFT_ALIGNMENT);

            nameLabel = new JLabel(name);
            nameLabel.setFont(new Font("Inter", Font.PLAIN, 12));
            nameLabel.setForeground(TEXT_SECONDARY);

            valueLabel = new JLabel("--");
            valueLabel.setFont(new Font("JetBrains Mono", Font.BOLD, 12));
            valueLabel.setForeground(TEXT_PRIMARY);
            valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

            add(nameLabel);
            add(Box.createHorizontalGlue());
            add(valueLabel);
        }

        public void setValue(String value) {
            valueLabel.setText(value);
        }
    }
}
