package org.nebula.statusui;

import javax.swing.*;

/**
 * Standalone launcher for the Nebula Status Dashboard.
 * This allows testing the UI without a full Bukkit server environment.
 */
public final class NebulaStatusLauncher {

    public static void main(String[] args) {
        // Set look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Use default look and feel
        }

        // Launch on the Event Dispatch Thread
        SwingUtilities.invokeLater(() -> {
            NebulaStatusWindow window = new NebulaStatusWindow(new MockStatusProvider());
            window.show();
        });
    }
}
