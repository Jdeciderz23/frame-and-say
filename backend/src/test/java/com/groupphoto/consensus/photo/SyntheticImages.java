package com.groupphoto.consensus.photo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Generated pixels only; no personal photos or disk-backed ImageIO cache. */
final class SyntheticImages {
    private SyntheticImages() {}

    static BufferedImage decode(byte[] bytes) throws IOException {
        try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Expected a decodable synthetic image");
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, Math.max(1, width / 2), height);
        } finally {
            graphics.dispose();
        }
        var bytes = new ByteArrayOutputStream();
        try (var output = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalArgumentException("No image writer for " + format);
            }
        }
        return bytes.toByteArray();
    }
}
