package org.nebula.statusui;

import javax.swing.*;
import java.awt.*;

/**
 * The main status window that displays the Nebula dashboard.
 */
public final class NebulaStatusWindow extends JFrame {

    private final StatusProvider statusProvider;
    private NebulaStatusPanel statusPanel;

    public NebulaStatusWindow(StatusProvider statusProvider) {
        this.statusProvider = statusProvider;

        setTitle("Nebula Status Dashboard");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setLayout(new BorderLayout());

        // Premium dark background
        getContentPane().setBackground(new Color(13, 13, 18));

        // Set window properties
        setSize(1200, 750);
        setMinimumSize(new Dimension(1024, 600));

        // Center on screen
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation((screenSize.width - getWidth()) / 2, (screenSize.height - getHeight()) / 2);

        // Create the main panel
        statusPanel = new NebulaStatusPanel();
        statusPanel.setStatusProvider(statusProvider);
        add(statusPanel);
    }

    public void show() {
        setVisible(true);
        toFront();
        requestFocus();
        statusPanel.startUpdates();
    }

    public void hide() {
        setVisible(false);
        statusPanel.stopUpdates();
    }

    public void close() {
        statusPanel.stopUpdates();
        dispose();
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {
            statusPanel.startUpdates();
        } else {
            statusPanel.stopUpdates();
        }
    }
}
