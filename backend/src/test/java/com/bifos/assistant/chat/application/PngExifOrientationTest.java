package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PngExifOrientationTest {
    @Test
    @DisplayName("PNG의 모든 EXIF 방향과 바이트 순서에서 Pillow 개요와 원본 영역의 픽셀이 같다")
    void matchesPillowOverviewAndCropForEveryOrientation() throws Exception {
        // 실제 image_conversion.convert의 Pillow 12.3.0 개요 출력이 기준이다. Java 변환으로 기준을 만들지 않는다.
        byte[] source = resource("source.png");
        for (ByteOrder order : List.of(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            for (int orientation = 1; orientation <= 8; orientation++) {
                byte[] original = withExif(source, tiff(orientation, order));
                byte[] unchanged = original.clone();
                BufferedImage expected = ImageIO.read(new ByteArrayInputStream(resource("overview-" + orientation + ".png")));
                BufferedImage full = ImageIO.read(new ByteArrayInputStream(
                        AttachmentInspection.inspect(original, "image/png", null).bytes()));
                assertPixels(full, expected, 0, 0, expected.getWidth(), expected.getHeight());
                List<Integer> region = List.of(0, expected.getHeight() - 6, 10, expected.getHeight());
                BufferedImage crop = ImageIO.read(new ByteArrayInputStream(
                        AttachmentInspection.inspect(original, "image/png", region).bytes()));
                assertPixels(crop, expected, 0, expected.getHeight() - 6, 10, 6);
                assertThat(original).isEqualTo(unchanged);
                assertThat(AgentImageResizer.orientation(original)).isEqualTo(orientation);
                BufferedImage copy = ImageIO.read(new ByteArrayInputStream(AgentImageResizer.toJpeg(original).orElseThrow()));
                assertThat(copy.getWidth()).isEqualTo(expected.getWidth());
                assertThat(copy.getHeight()).isEqualTo(expected.getHeight());
                AttachmentStore store = mock(AttachmentStore.class);
                ChatAttachment attachment = mock(ChatAttachment.class);
                when(attachment.isVisible()).thenReturn(true);
                when(store.open(attachment)).thenReturn(new ByteArrayInputStream(original));
                assertThat(new AttachmentInspection(store).displaySize(attachment))
                        .isEqualTo(expected.getWidth() + "x" + expected.getHeight());
            }
        }
    }

    @Test
    @DisplayName("큰 IDAT 뒤의 PNG EXIF도 표시 크기와 영역에 반영한다")
    void readsExifBeyondInitialHeader() throws Exception {
        BufferedImage source = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, random.nextInt());
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);
        byte[] png = encoded.toByteArray();
        assertThat(png.length).isGreaterThan(256 * 1024);
        byte[] original = withChunk(png, png.length - 12, new byte[] {'e', 'X', 'I', 'f'}, tiff(6, ByteOrder.BIG_ENDIAN));
        AttachmentStore store = mock(AttachmentStore.class);
        ChatAttachment attachment = mock(ChatAttachment.class);
        when(attachment.isVisible()).thenReturn(true);
        when(store.open(attachment)).thenReturn(new ByteArrayInputStream(original));
        assertThat(new AttachmentInspection(store).displaySize(attachment)).isEqualTo("300x400");
        BufferedImage crop = ImageIO.read(new ByteArrayInputStream(
                AttachmentInspection.inspect(original, "image/png", List.of(0, 394, 10, 400)).bytes()));
        assertThat(crop.getWidth()).isEqualTo(10);
        assertThat(crop.getHeight()).isEqualTo(6);
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 10; x++) {
                assertThat(crop.getRGB(x, y)).isEqualTo(source.getRGB(394 + y, 299 - x));
            }
        }
    }

    @Test
    @DisplayName("PNG EXIF의 잘린 청크와 초과 길이 및 손상 CRC를 안전하게 무시한다")
    void ignoresTruncatedChunksOversizedLengthsAndInvalidCrc() throws Exception {
        byte[] source = resource("source.png");
        byte[] original = withExif(source, tiff(6, ByteOrder.BIG_ENDIAN));
        for (int end = 0; end < 71; end++) {
            assertThat(AgentImageResizer.orientation(Arrays.copyOf(original, end))).isEqualTo(1);
        }
        byte[] badCrc = original.clone();
        badCrc[70] ^= 1;
        assertThat(AgentImageResizer.orientation(badCrc)).isEqualTo(1);
        for (int length : List.of(-1, Integer.MAX_VALUE, 0, 4, 1000)) {
            byte[] badLength = original.clone();
            ByteBuffer.wrap(badLength).putInt(33, length);
            assertThat(AgentImageResizer.orientation(badLength)).isEqualTo(1);
        }
        assertThat(AgentImageResizer.orientation(source)).isEqualTo(1);
        byte[] badSignature = original.clone();
        badSignature[7] ^= 1;
        assertThat(AgentImageResizer.orientation(badSignature)).isEqualTo(1);
    }

    @Test
    @DisplayName("TIFF 범위를 벗어난 IFD와 잘못된 방향 태그의 타입·개수·값을 무시한다")
    void ignoresInvalidTiffOffsetsAndOrientationEntries() throws Exception {
        byte[] source = resource("source.png");
        for (ByteOrder order : List.of(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            for (int offset : List.of(0, 7, 28, Integer.MAX_VALUE, -1)) {
                byte[] tiff = tiff(6, order);
                ByteBuffer.wrap(tiff).order(order).putInt(4, offset);
                assertThat(AgentImageResizer.orientation(withExif(source, tiff))).isEqualTo(1);
            }
            for (int count : List.of(0, 2, -1)) {
                byte[] tiff = tiff(6, order);
                ByteBuffer.wrap(tiff).order(order).putInt(14, count);
                assertThat(AgentImageResizer.orientation(withExif(source, tiff))).isEqualTo(1);
            }
            for (int orientation : List.of(0, 9, 65535)) {
                assertThat(AgentImageResizer.orientation(withExif(source, tiff(orientation, order))))
                        .isEqualTo(1);
            }
            byte[] wrongType = tiff(6, order);
            ByteBuffer.wrap(wrongType).order(order).putShort(12, (short) 4);
            assertThat(AgentImageResizer.orientation(withExif(source, wrongType))).isEqualTo(1);
            byte[] wrongEndian = tiff(6, order);
            wrongEndian[0] = 'X';
            assertThat(AgentImageResizer.orientation(withExif(source, wrongEndian))).isEqualTo(1);
            byte[] wrongMagic = tiff(6, order);
            wrongMagic[2] ^= 1;
            assertThat(AgentImageResizer.orientation(withExif(source, wrongMagic))).isEqualTo(1);
            assertThat(AgentImageResizer.orientation(withExif(source, Arrays.copyOf(tiff(6, order), 21))))
                    .isEqualTo(1);
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (var input = PngExifOrientationTest.class.getResourceAsStream("/attachments/png-orientation/" + name)) {
            return input.readAllBytes();
        }
    }

    private static byte[] tiff(int orientation, ByteOrder order) {
        ByteBuffer bytes = ByteBuffer.allocate(26).order(order);
        bytes.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        bytes.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        bytes.putShort((short) 42).putInt(8).putShort((short) 1);
        bytes.putShort((short) 274).putShort((short) 3).putInt(1).putShort((short) orientation);
        bytes.putShort((short) 0).putInt(0);
        return bytes.array();
    }

    private static byte[] withExif(byte[] png, byte[] tiff) throws Exception {
        return withChunk(png, 33, new byte[] {'e', 'X', 'I', 'f'}, tiff);
    }

    private static byte[] withChunk(byte[] png, int offset, byte[] type, byte[] body) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(png, 0, offset);
        result.write(ByteBuffer.allocate(4).putInt(body.length).array());
        result.write(type);
        result.write(body);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(body);
        result.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        result.write(png, offset, png.length - offset);
        return result.toByteArray();
    }

    private static void assertPixels(BufferedImage actual, BufferedImage expected, int left, int top, int width, int height) {
        assertThat(actual.getWidth()).isEqualTo(width);
        assertThat(actual.getHeight()).isEqualTo(height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertThat(actual.getRGB(x, y)).as("x=%s y=%s", x, y).isEqualTo(expected.getRGB(left + x, top + y));
            }
        }
    }
}
