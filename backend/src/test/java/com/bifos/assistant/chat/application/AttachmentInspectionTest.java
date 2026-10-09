package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AttachmentInspectionTest {
    @Test
    @DisplayName("원본 바이트와 영역의 픽셀을 그대로 보존한다")
    void preservesOriginalBytesAndCropPixels() throws Exception {
        BufferedImage image = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1999, 999, 0xff123456);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] original = output.toByteArray();
        assertThat(AttachmentInspection.inspect(original, "image/png", null).bytes())
                .isEqualTo(original);
        BufferedImage crop = ImageIO.read(new ByteArrayInputStream(
                AttachmentInspection.inspect(original, "image/png", List.of(1900, 900, 2000, 1000))
                        .bytes()));
        assertThat(crop.getWidth()).isEqualTo(100);
        assertThat(crop.getRGB(99, 99)).isEqualTo(image.getRGB(1999, 999));
        assertThatThrownBy(() -> AttachmentInspection.inspect(original, "image/png", List.of(0, 0, 2001, 1000)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AttachmentInspection.inspect(new byte[] {1}, "image/png", null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("EXIF 방향 8가지에서 표시 좌표 영역을 동일하게 자른다")
    void cropsDisplayCoordinatesForEveryExifOrientation() throws Exception {
        BufferedImage source = new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 60; x++) {
                source.setRGB(x, y, (x * 4 << 16) | (y * 6 << 8) | (x + y));
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);
        byte[] jpeg = encoded.toByteArray();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        for (int orientation = 1; orientation <= 8; orientation++) {
            byte[] exif = {
                (byte) 0xff,
                (byte) 0xe1,
                0,
                34,
                'E',
                'x',
                'i',
                'f',
                0,
                0,
                'M',
                'M',
                0,
                42,
                0,
                0,
                0,
                8,
                0,
                1,
                1,
                18,
                0,
                3,
                0,
                0,
                0,
                1,
                0,
                (byte) orientation,
                0,
                0,
                0,
                0,
                0,
                0
            };
            ByteArrayOutputStream withExif = new ByteArrayOutputStream();
            withExif.write(jpeg, 0, 2);
            withExif.write(exif);
            withExif.write(jpeg, 2, jpeg.length - 2);
            BufferedImage expected = AgentImageResizer.draw(decoded, orientation, Integer.MAX_VALUE);
            BufferedImage actual = ImageIO.read(new ByteArrayInputStream(
                    AttachmentInspection.inspect(withExif.toByteArray(), "image/jpeg", List.of(3, 5, 13, 15))
                            .bytes()));
            for (int y = 0; y < 10; y++) {
                for (int x = 0; x < 10; x++) {
                    assertThat(actual.getRGB(x, y))
                            .as("orientation=%s x=%s y=%s", orientation, x, y)
                            .isEqualTo(expected.getRGB(x + 3, y + 5));
                }
            }
        }
    }

    @Test
    @DisplayName("전체 픽셀 한도를 넘으면 영역 조회만 허용한다")
    void fullImageAbovePixelBudgetRequiresCrop() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4100, 4000, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] original = output.toByteArray();
        assertThatThrownBy(() -> AttachmentInspection.inspect(original, "image/png", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("smaller region");
        assertThat(AttachmentInspection.inspect(original, "image/png", List.of(0, 0, 100, 100))
                        .bytes())
                .isNotEmpty();
    }
}
