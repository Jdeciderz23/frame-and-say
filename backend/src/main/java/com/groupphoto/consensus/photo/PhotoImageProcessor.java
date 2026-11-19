package com.groupphoto.consensus.photo;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

final class PhotoImageProcessor {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SIDE = 12_000;
    private static final long MAX_PIXELS = 24_000_000L;
    private static final int THUMBNAIL_SIDE = 320;

    record Processed(byte[] original, byte[] thumbnail, String mediaType) {}

    Processed process(MultipartFile file) {
        byte[] bytes = readOriginal(file);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw unsupported(null);
            }
            ImageReader reader = readers.next();
            try {
                String mediaType = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "png" -> "image/png";
                    default -> throw unsupported(null);
                };
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE
                        || (long) width * height > MAX_PIXELS) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Photo dimensions exceed the limit");
                }
                ImageReadParam parameters = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(width, height) / (THUMBNAIL_SIDE * 2));
                parameters.setSourceSubsampling(step, step, 0, 0);
                return new Processed(bytes, createThumbnail(reader.read(0, parameters)), mediaType);
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException error) {
            throw unsupported(error);
        }
    }

    private static byte[] readOriginal(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A photo file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Photo exceeds 8 MiB");
        }
        try (InputStream input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Photo exceeds 8 MiB");
            }
            return bytes;
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Photo could not be read", error);
        }
    }

    private static byte[] createThumbnail(BufferedImage image) throws IOException {
        double scale = Math.min(1d, (double) THUMBNAIL_SIDE / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(thumbnail, "png", output)) {
            throw new IllegalStateException("PNG writer missing");
        }
        return output.toByteArray();
    }

    private static ResponseStatusException unsupported(Exception cause) {
        return new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "A decodable JPEG or PNG photo is required", cause);
    }
}
