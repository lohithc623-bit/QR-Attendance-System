package com.attendance.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/** ZXing encode/decode with NO JavaFX dependency, so the headless API server can use it too. */
public final class QRImageWriter {

    private QRImageWriter() {
    }

    public static BufferedImage toImage(String content, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            BufferedImage image = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < sizePx; x++) {
                for (int y = 0; y < sizePx; y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            return image;
        } catch (WriterException e) {
            throw new IllegalStateException("Failed to generate QR code", e);
        }
    }

    public static byte[] toPng(String content, int sizePx) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(toImage(content, sizePx), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode PNG", e);
        }
    }

    /** @return the decoded text, or null if the image contains no readable QR code. */
    public static String decode(BufferedImage image) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            return new MultiFormatReader().decode(bitmap).getText();
        } catch (NotFoundException e) {
            return null;
        }
    }
}
