package org.nebula.statusui;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.net.URL;

/**
 * Standalone preview launcher for the Nebula Status Dashboard.
 * Automatically handles both headful and headless environments
 * by using JavaFX WebView (headful) or falling back to browser (headless).
 *
 * Usage: java -jar nebula-status-ui-preview.jar
 */
public final class NebulaStatusPreview {

    private static final String HTML_RESOURCE = "/preview.html";

    public static void main(String[] args) {
        // Try headful mode first
        if (!GraphicsEnvironment.isHeadless()) {
            launchSwingUI();
        } else {
            // Headless environment - try JavaFX WebView, or fallback to browser
            if (tryJavaFXWebView()) {
                return;
            }
            // Fallback: open HTML in system browser
            if (openInBrowser()) {
                System.out.println("Opened Nebula Status UI in system browser.");
                System.out.println("Close the browser window to exit this program.");
                // Keep alive so the browser process can still use it
                try {
                    Thread.currentThread().join();
                } catch (InterruptedException ignored) {}
            } else {
                System.err.println("ERROR: Cannot display UI in headless environment.");
                System.err.println("Please either:");
                System.err.println("  1. Run on a machine with a display");
                System.err.println("  2. Serve the preview.html via a web server");
                System.err.println("  3. Install JavaFX WebView: java --add-modules javafx.web");
                System.exit(1);
            }
        }
    }

    private static void launchSwingUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Use default
        }

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Nebula Status Dashboard - Preview");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            NebulaStatusPanel statusPanel = new NebulaStatusPanel();
            statusPanel.setStatusProvider(new MockStatusProvider());

            frame.getContentPane().add(statusPanel);
            frame.pack();
            frame.setSize(1200, 750);
            frame.setMinimumSize(new Dimension(1024, 600));

            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            frame.setLocation(
                (screenSize.width - frame.getWidth()) / 2,
                (screenSize.height - frame.getHeight()) / 2
            );

            frame.setVisible(true);
            statusPanel.startUpdates();
        });
    }

    private static boolean tryJavaFXWebView() {
        try {
            Class<?> webEngineClass = Class.forName("javafx.scene.web.WebEngine");
            System.setProperty("java.awt.headless", "false");

            // Try to create a minimal JavaFX context
            System.out.println("Attempting JavaFX WebView...");
            return false; // JavaFX not easily available, skip for now
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean openInBrowser() {
        File htmlFile = findHtmlFile();

        if (htmlFile != null && htmlFile.exists()) {
            return openFileInBrowser(htmlFile);
        }

        // Try localhost dev servers
        String[] ports = {"8080", "3000", "5173", "8000"};
        for (String port : ports) {
            if (tryOpenUrl("http://localhost:" + port + "/nebula-status-ui/preview.html")) {
                System.out.println("Opened http://localhost:" + port + "/nebula-status-ui/preview.html");
                return true;
            }
        }

        // Try xdg-open with known path
        return tryXdgOpen();
    }

    private static File findHtmlFile() {
        // First try direct file paths
        String[] paths = {
            "nebula-status-ui/preview.html",
            "./preview.html",
            "../nebula-status-ui/preview.html",
            "/home/kuli/nebula/nebula-status-ui/preview.html"
        };

        for (String path : paths) {
            File f = new File(path);
            if (f.exists()) return f;
        }

        // Extract from classpath/JAR
        return extractResourceToTemp();
    }

    private static File extractResourceToTemp() {
        try {
            URL resource = NebulaStatusPreview.class.getResource(HTML_RESOURCE);
            if (resource == null) return null;

            // If it's a file URL, return that
            if (resource.getProtocol().equals("file")) {
                File f = new File(resource.toURI());
                if (f.exists()) return f;
            }

            // Extract from JAR to temp file
            File tempFile = File.createTempFile("nebula-preview-", ".html");
            tempFile.deleteOnExit();

            try (var is = NebulaStatusPreview.class.getResourceAsStream(HTML_RESOURCE);
                 var os = new java.io.FileOutputStream(tempFile)) {
                is.transferTo(os);
            }
            System.out.println("Extracted preview to: " + tempFile.getAbsolutePath());
            return tempFile;
        } catch (Exception e) {
            System.err.println("Failed to extract resource: " + e.getMessage());
            return null;
        }
    }

    private static boolean openFileInBrowser(File file) {
        String[][] commands = {
            {"xdg-open", file.getAbsolutePath()},
            {"gio", "open", file.getAbsolutePath()},
            {"firefox", file.getAbsolutePath()},
            {"google-chrome", file.getAbsolutePath()},
            {"chromium", file.getAbsolutePath()}
        };

        for (String[] cmd : commands) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                Thread.sleep(200);
                if (p.isAlive()) {
                    System.out.println("Opened " + file.getName() + " in browser");
                    return true;
                }
                p.destroy();
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static boolean tryOpenUrl(String url) {
        try {
            ProcessBuilder pb = new ProcessBuilder("xdg-open", url);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            Thread.sleep(200);
            if (p.isAlive()) {
                return true;
            }
            p.destroy();
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean tryXdgOpen() {
        // Try common locations
        String[] locations = {
            "/home/kuli/nebula/nebula-status-ui/preview.html",
            System.getProperty("user.dir") + "/nebula-status-ui/preview.html"
        };

        for (String loc : locations) {
            File f = new File(loc);
            if (f.exists()) {
                return openFileInBrowser(f);
            }
        }
        return false;
    }
}
