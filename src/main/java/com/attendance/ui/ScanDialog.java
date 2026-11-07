package com.attendance.ui;

import com.attendance.util.QRCodeUtil;
import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamResolution;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.function.Function;

/**
 * Live webcam scanner. Shows the camera feed, decodes every few frames with ZXing and hands each
 * decoded payload to {@code handler}, which returns the outcome (done on the camera thread, so the
 * UI never freezes). The result is shown as a big green/red banner with a beep. Stays open so a
 * whole class can be scanned one after another.
 */
public final class ScanDialog {

    /** What the caller decided about one scanned code. */
    public record Outcome(boolean success, String message) { }

    private static final long REPEAT_IGNORE_MS = 3000;

    private final Function<String, Outcome> handler;
    private final Runnable onMarked;
    private volatile boolean running = true;

    private final Stage stage = new Stage();
    private final ImageView preview = new ImageView();
    private final Label banner = new Label("Starting camera...");

    private ScanDialog(Window owner, Function<String, Outcome> handler, Runnable onMarked) {
        this.handler = handler;
        this.onMarked = onMarked;
        build(owner);
    }

    /** Opens the scanner window and starts the camera. */
    public static void open(Window owner, Function<String, Outcome> handler, Runnable onMarked) {
        ScanDialog dialog = new ScanDialog(owner, handler, onMarked);
        dialog.stage.show();
        dialog.startCamera();
    }

    private void build(Window owner) {
        preview.setFitWidth(560);
        preview.setFitHeight(420);
        preview.setPreserveRatio(true);

        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setAlignment(Pos.CENTER);
        banner.setPadding(new Insets(14));
        banner.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-background-color: #555; -fx-text-fill: white;");

        Button close = new Button("Done");
        close.setOnAction(e -> stage.close());
        Label hint = new Label("Hold the student's live QR code in front of the camera. Each code is only valid for about a minute.");
        hint.setWrapText(true);

        VBox root = new VBox(10, new StackPane(preview), banner, hint, new HBox(close));
        root.setPadding(new Insets(12));
        root.setAlignment(Pos.TOP_CENTER);

        stage.setTitle("Scan student QR codes");
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setScene(new Scene(root, 600, 640));
        if (owner != null && owner.getScene() != null) {
            stage.getScene().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        stage.setOnHidden(e -> running = false);
    }

    private void startCamera() {
        Thread thread = new Thread(this::cameraLoop, "qr-camera");
        thread.setDaemon(true);
        thread.start();
    }

    private void cameraLoop() {
        Webcam webcam = null;
        try {
            webcam = Webcam.getDefault();
            if (webcam == null) {
                setBanner("No camera found. Close this window and use 'Scan image file' instead.", "#c0392b");
                return;
            }
            webcam.setViewSize(WebcamResolution.VGA.getSize());
            webcam.open();
            setBanner("Camera ready - show a QR code", "#2c5aa0");

            String lastText = null;
            long lastAt = 0;
            int frame = 0;
            while (running) {
                BufferedImage image = webcam.getImage();
                if (image == null) {
                    continue;
                }
                javafx.scene.image.Image fx = SwingFXUtils.toFXImage(image, null);
                Platform.runLater(() -> preview.setImage(fx));

                if (++frame % 3 != 0) {
                    continue; // decode every 3rd frame - plenty fast, and keeps the CPU calm
                }
                String text = QRCodeUtil.decodeFrame(image);
                long now = System.currentTimeMillis();
                if (text == null || (text.equals(lastText) && now - lastAt < REPEAT_IGNORE_MS)) {
                    continue; // nothing found, or the same code still in view
                }
                lastText = text;
                lastAt = now;

                Outcome outcome = handler.apply(text);
                beep(outcome.success());
                setBanner((outcome.success() ? "OK  " : "X  ") + outcome.message(),
                        outcome.success() ? "#1e8449" : "#c0392b");
                if (outcome.success()) {
                    Platform.runLater(onMarked);
                }
            }
        } catch (RuntimeException e) {
            setBanner("Camera error: " + e.getMessage(), "#c0392b");
        } finally {
            if (webcam != null && webcam.isOpen()) {
                webcam.close();
            }
        }
    }

    private void setBanner(String text, String color) {
        Platform.runLater(() -> {
            banner.setText(text);
            banner.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: white; -fx-background-color: " + color + ";");
        });
    }

    private static void beep(boolean good) {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        toolkit.beep();
        if (!good) {
            try {
                Thread.sleep(180);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            toolkit.beep(); // two beeps = rejected
        }
    }
}
