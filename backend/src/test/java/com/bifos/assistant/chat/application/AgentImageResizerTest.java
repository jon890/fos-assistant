package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentImageResizerTest {

    @Test
    @DisplayName("큰 PNG 는 긴 변 1600 의 JPEG 로 줄인다")
    void shrinksLargePngToJpegWithLongSide1600() throws IOException {
        byte[] png = png(new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB));

        BufferedImage result = jpegOf(AgentImageResizer.toJpeg(png).orElseThrow());

        assertThat(result.getWidth()).isEqualTo(1600);
        assertThat(result.getHeight()).isEqualTo(1200);
    }

    @Test
    @DisplayName("긴 변이 1600 보다 작은 사진은 키우지 않는다")
    void keepsSmallImageSize() throws IOException {
        byte[] png = png(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB));

        BufferedImage result = jpegOf(AgentImageResizer.toJpeg(png).orElseThrow());

        assertThat(result.getWidth()).isEqualTo(800);
        assertThat(result.getHeight()).isEqualTo(600);
    }

    @Test
    @DisplayName("EXIF 방향 6 인 가로 JPEG 는 세로로 세워 줄인다")
    void appliesExifOrientationSix() throws IOException {
        byte[] jpeg = withExifOrientation(jpeg(new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB), 0.85f), 6);
        assertThat(AgentImageResizer.orientation(jpeg)).isEqualTo(6);

        BufferedImage result = jpegOf(AgentImageResizer.toJpeg(jpeg).orElseThrow());

        assertThat(result.getWidth()).isEqualTo(1200);
        assertThat(result.getHeight()).isEqualTo(1600);
    }

    @Test
    @DisplayName("투명 PNG 의 투명한 부분은 흰색으로 채운다")
    void fillsTransparentPixelsWithWhite() throws IOException {
        byte[] png = png(new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB));

        BufferedImage result = jpegOf(AgentImageResizer.toJpeg(png).orElseThrow());

        Color center = new Color(result.getRGB(100, 50));
        assertThat(center.getRed()).as("red").isGreaterThanOrEqualTo(240);
        assertThat(center.getGreen()).as("green").isGreaterThanOrEqualTo(240);
        assertThat(center.getBlue()).as("blue").isGreaterThanOrEqualTo(240);
    }

    @Test
    @DisplayName("3MB 를 넘는 JPEG 의 사본은 긴 변 1600 이고 1MB 이하다")
    void shrinksLargeJpegUnderOneMegabyte() throws IOException {
        byte[] large = jpeg(noisyGradient(4000, 3000), 0.95f);
        assertThat(large.length).as("원본 크기").isGreaterThan(3 * 1024 * 1024);

        byte[] small = AgentImageResizer.toJpeg(large).orElseThrow();

        BufferedImage result = jpegOf(small);
        assertThat(Math.max(result.getWidth(), result.getHeight())).isEqualTo(1600);
        assertThat(small.length).as("사본 크기").isLessThanOrEqualTo(1024 * 1024);
    }

    @Test
    @DisplayName("이미지가 아닌 바이트는 빈 값이다")
    void returnsEmptyForNonImageBytes() {
        assertThat(AgentImageResizer.toJpeg(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}))
                .isEmpty();
    }

    @Test
    @DisplayName("화소 수가 상한을 넘는지 int 가 넘치지 않게 판정한다")
    void judgesPixelLimitWithoutIntOverflow() {
        assertThat(AgentImageResizer.withinPixelLimit(50_000, 50_000, AgentImageResizer.MAX_PIXELS))
                .isFalse();
        assertThat(AgentImageResizer.withinPixelLimit(8_000, 6_000, AgentImageResizer.MAX_PIXELS))
                .isTrue();
    }

    private static BufferedImage jpegOf(byte[] bytes) throws IOException {
        assertThat(bytes[0] & 0xff).as("SOI 첫 바이트").isEqualTo(0xff);
        assertThat(bytes[1] & 0xff).as("SOI 둘째 바이트").isEqualTo(0xd8);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).as("JPEG 로 읽힌다").isNotNull();
        return image;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** 가로세로 그라데이션에 ±8 잡음을 섞는다. 잡음이 있어야 품질 0.95 JPEG 가 3MB 를 넘는다. */
    private static BufferedImage noisyGradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = clamp(x * 255 / width + random.nextInt(17) - 8);
                int green = clamp(y * 255 / height + random.nextInt(17) - 8);
                int blue = clamp((x + y) * 255 / (width + height) + random.nextInt(17) - 8);
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /** SOI 바로 뒤에 IFD0 에 방향 태그 하나만 둔 APP1 Exif 구간을 끼운다. 바이트 순서는 big-endian(MM)이다. */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        ByteBuffer tiff = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 0x2a).putInt(8); // TIFF 머리. IFD0 은 8 바이트 뒤다
        tiff.putShort((short) 1); // 항목 수
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1); // 방향 태그, SHORT, 1개
        tiff.putShort((short) orientation).putShort((short) 0); // 값은 앞 2 바이트에 둔다
        tiff.putInt(0); // 다음 IFD 없음
        byte[] header = {'E', 'x', 'i', 'f', 0, 0};
        int length = 2 + header.length + tiff.capacity();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xff);
        out.write(0xe1);
        out.write(length >> 8);
        out.write(length & 0xff);
        out.writeBytes(header);
        out.writeBytes(tiff.array());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
