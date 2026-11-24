package com.groupphoto.consensus.photo;

import static com.groupphoto.consensus.photo.SyntheticImages.decode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class PhotoImageProcessorTest {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final PhotoImageProcessor processor = new PhotoImageProcessor();

    @ParameterizedTest
    @CsvSource({"jpeg, image/jpeg", "png, image/png"})
    void decodesContentRegardlessOfFilenameAndDeclaredType(String format, String mediaType) throws Exception {
        byte[] original = SyntheticImages.image(format, 640, 320);
        var result = processor.process(file("../../not-an-image.txt", "text/plain", original));

        assertThat(result.original()).isEqualTo(original);
        assertThat(result.mediaType()).isEqualTo(mediaType);
        BufferedImage thumbnail = decode(result.thumbnail());
        assertThat(thumbnail.getWidth()).isEqualTo(320);
        assertThat(thumbnail.getHeight()).isEqualTo(160);
        assertThat(thumbnail.getRGB(40, 80) & 0xffffff).isEqualTo(0xffffff);
        assertThat(thumbnail.getRGB(280, 80) & 0xffffff).isZero();
    }

    @ParameterizedTest
    @CsvSource({"png, 32, 16", "jpeg, 16, 32", "png, 320, 160", "jpeg, 320, 640"})
    void preservesAspectRatioAndDoesNotUpscale(String format, int width, int height) throws Exception {
        var result = processor.process(file("photo", "application/octet-stream",
                SyntheticImages.image(format, width, height)));
        var thumbnail = decode(result.thumbnail());
        double scale = Math.min(1d, 320d / Math.max(width, height));
        assertThat(thumbnail.getWidth()).isEqualTo((int) Math.round(width * scale));
        assertThat(thumbnail.getHeight()).isEqualTo((int) Math.round(height * scale));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png"})
    void rejectsTextDisguisedAsAnImage(String format) {
        assertStatus(file("fake." + format, "image/" + format, new byte[]{1, 2, 3}),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png"})
    void rejectsOtherDecodableFormatsDisguisedAsAnImage(String format) throws Exception {
        byte[] gif = SyntheticImages.image("gif", 32, 16);
        assertThat(decode(gif)).isNotNull();
        assertStatus(file("fake." + format, "image/" + format, gif), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png"})
    void rejectsRecognizableButUndecodableImage(String format) throws Exception {
        byte[] bytes = SyntheticImages.image(format, 640, 320);
        // PNG keeps only IHDR. JPEG references an undefined quantization table.
        // Both expose valid dimensions, so rejecting them requires real decoding.
        byte[] corrupted = Arrays.copyOf(bytes, format.equals("png") ? 33 : bytes.length);
        if (format.equals("jpeg")) {
            boolean changed = false;
            for (int i = 2; i < bytes.length - 1; i++) {
                if ((bytes[i] & 0xff) == 0xff && (bytes[i + 1] & 0xff) == 0xc0) {
                    corrupted[i + 12] = 3;
                    changed = true;
                    break;
                }
            }
            assertThat(changed).isTrue();
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(corrupted))) {
            var reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input);
                assertThat(reader.getWidth(0)).isEqualTo(640);
                assertThat(reader.getHeight(0)).isEqualTo(320);
            } finally {
                reader.dispose();
            }
        }
        assertStatus(file("broken." + format, "image/" + format, corrupted), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void rejectsEmptyFile() {
        assertStatus(file("empty.png", "image/png", new byte[0]), HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png"})
    void acceptsExactlyEightMiB(String format) throws Exception {
        // Legal image followed by padding exercises the byte limit without random fixtures.
        byte[] bytes = Arrays.copyOf(SyntheticImages.image(format, 32, 16), MAX_BYTES);
        var result = processor.process(file("limit." + format, "image/" + format, bytes));
        assertThat(result.original()).hasSize(MAX_BYTES);
        assertThat(decode(result.thumbnail()).getWidth()).isEqualTo(32);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png"})
    void rejectsDeclaredOversizeBeforeOpeningStream(String format) {
        var file = new MockMultipartFile("file", "big." + format, "image/" + format, new byte[]{1}) {
            @Override public long getSize() { return MAX_BYTES + 1L; }
            @Override public InputStream getInputStream() { throw new AssertionError("Must not open oversized file"); }
        };
        assertStatus(file, HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void boundsStreamEvenWhenDeclaredSizeIsTooSmall() {
        var file = new MockMultipartFile("file", "underreported.png", "image/png", new byte[MAX_BYTES + 1]) {
            @Override public long getSize() { return 1; }
        };
        assertStatus(file, HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void reportsUnreadableInputAsBadRequest() {
        var file = new MockMultipartFile("file", "unreadable.png", "image/png", new byte[]{1}) {
            @Override public InputStream getInputStream() throws IOException { throw new IOException("synthetic failure"); }
        };
        assertStatus(file, HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @CsvSource({"png, 12000, 1", "jpeg, 1, 12000", "png, 6000, 4000", "jpeg, 6000, 4000"})
    void acceptsExactSideAndPixelLimits(String format, int width, int height) throws Exception {
        var result = processor.process(file("limit." + format, "image/" + format,
                SyntheticImages.image(format, width, height)));
        var thumbnail = decode(result.thumbnail());
        assertThat(Math.max(thumbnail.getWidth(), thumbnail.getHeight())).isEqualTo(320);
        assertThat(Math.min(thumbnail.getWidth(), thumbnail.getHeight())).isPositive();
    }

    @ParameterizedTest
    @CsvSource({"png, 12001, 1", "png, 1, 12001", "jpeg, 12001, 1", "jpeg, 1, 12001",
            "png, 6000, 4001", "jpeg, 6000, 4001"})
    void rejectsSideOrPixelOverflowEvenForSmallCompressedFiles(String format, int width, int height) throws Exception {
        byte[] bytes = SyntheticImages.image(format, width, height);
        assertThat(bytes.length).isLessThan(MAX_BYTES);
        assertStatus(file("large." + format, "image/" + format, bytes), HttpStatus.PAYLOAD_TOO_LARGE);
    }

    private void assertStatus(MockMultipartFile file, HttpStatus expected) {
        assertThatExceptionOfType(ResponseStatusException.class).isThrownBy(() -> processor.process(file))
                .satisfies(error -> assertThat(error.getStatusCode()).isEqualTo(expected));
    }

    private static MockMultipartFile file(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

}
