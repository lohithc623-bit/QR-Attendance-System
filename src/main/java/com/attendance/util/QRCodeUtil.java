package com.attendance.util;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** JavaFX-facing wrapper around {@link QRImageWriter}. */
public final class QRCodeUtil {

    private QRCodeUtil() {
    }

    public static Image generateQRImage(String content, int sizePx) {
        return SwingFXUtils.toFXImage(QRImageWriter.toImage(content, sizePx), null);
    }

    /** Decode a QR from an image file (fallback to live camera scanning). Returns null if none found. */
    public static String decodeQRImageFile(File imageFile) throws IOException {
        BufferedImage img = ImageIO.read(imageFile);
        if (img == null) {
            throw new IOException("Not a readable image file: " + imageFile.getName());
        }
        return QRImageWriter.decode(img);
    }

    /** Decode a QR from a camera frame. Returns null if the frame has no readable code. */
    public static String decodeFrame(BufferedImage frame) {
        return QRImageWriter.decode(frame);
    }
}
