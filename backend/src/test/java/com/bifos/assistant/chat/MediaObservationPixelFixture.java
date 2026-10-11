package com.bifos.assistant.chat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 실제로 디코딩할 수 있는 고유 픽셀 표식을 가진 DB 검사 전용 파일이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class MediaObservationPixelFixture {
    static byte[] gif(int marker) {
        var image = new BufferedImage(224, 160, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 224, 160);
            for (int bit = 0; bit < 16; bit++) {
                graphics.setColor(((0xa500 | marker) & (1 << (15 - bit))) == 0 ? Color.BLACK : Color.WHITE);
                graphics.fillRect(20 + 24 * (bit % 8), 20 + 24 * (bit / 8), 16, 16);
            }
        } finally {
            graphics.dispose();
        }
        try (var bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "gif", bytes)) {
                throw new IllegalStateException("합성 GIF 인코더가 없다");
            }
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }


    static int marker(byte[] bytes) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null || image.getWidth() != 224 || image.getHeight() != 160) {
            throw new IOException("합성 GIF 디코딩 실패");
        }
        int value = 0;
        for (int bit = 0; bit < 16; bit++) {
            int rgb = image.getRGB(28 + 24 * (bit % 8), 28 + 24 * (bit / 8)) & 0xffffff;
            if (rgb != 0 && rgb != 0xffffff) {
                throw new IOException("합성 표식이 흑백이 아니다");
            }
            value = (value << 1) | (rgb == 0xffffff ? 1 : 0);
        }
        if ((value >>> 8) != 0xa5) {
            throw new IOException("합성 표식 접두어가 다르다");
        }
        return value & 0xff;
    }
}
