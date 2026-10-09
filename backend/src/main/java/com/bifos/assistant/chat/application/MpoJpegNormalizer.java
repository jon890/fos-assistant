package com.bifos.assistant.chat.application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** MPO의 MPF 구간과 보조 사진만 제거한다. 판정이나 디코딩이 실패하면 원본을 돌려준다. */
public final class MpoJpegNormalizer {

    private MpoJpegNormalizer() {}

    public static byte[] normalize(byte[] original) {
        if (original.length < 4 || unsigned(original[0]) != 0xff || unsigned(original[1]) != 0xd8) {
            return original;
        }
        ByteArrayOutputStream first = new ByteArrayOutputStream(original.length);
        first.write(original, 0, 2);
        int offset = 2;
        boolean mpf = false;
        boolean scan = false;
        while (offset < original.length) {
            int start = offset;
            if (unsigned(original[offset]) != 0xff) {
                if (!scan) {
                    return original;
                }
                first.write(original[offset++]);
                continue;
            }
            while (offset < original.length && unsigned(original[offset]) == 0xff) {
                offset++;
            }
            if (offset == original.length) {
                return original;
            }
            int marker = unsigned(original[offset++]);
            if (scan && (marker == 0 || (marker >= 0xd0 && marker <= 0xd7))) {
                first.write(original, start, offset - start);
                continue;
            }
            if (marker == 0xd9) {
                first.write(original, start, offset - start);
                byte[] candidate = first.toByteArray();
                return mpf && decodes(candidate) ? candidate : original;
            }
            if (marker == 0 || marker == 0xd8 || (marker >= 0xd0 && marker <= 0xd7)) {
                return original;
            }
            if (marker == 1) {
                first.write(original, start, offset - start);
                continue;
            }
            if (offset + 2 > original.length) {
                return original;
            }
            int length = (unsigned(original[offset]) << 8) | unsigned(original[offset + 1]);
            if (length < 2 || length > original.length - offset) {
                return original;
            }
            boolean isMpf = marker == 0xe2
                    && length >= 6
                    && original[offset + 2] == 'M'
                    && original[offset + 3] == 'P'
                    && original[offset + 4] == 'F'
                    && original[offset + 5] == 0;
            offset += length;
            if (isMpf) {
                mpf = true;
            } else {
                first.write(original, start, offset - start);
            }
            // SOS가 여러 개인 progressive JPEG도 각 구간의 길이를 따라 처리한다.
            scan = marker == 0xda;
        }
        return original;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static boolean decodes(byte[] candidate) {
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(candidate))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                boolean[] warning = {false};
                reader.addIIOReadWarningListener((source, message) -> warning[0] = true);
                reader.setInput(input);
                var parameters = reader.getDefaultReadParam();
                // 압축 데이터는 끝까지 읽고 검증용 픽셀 배열만 작게 유지한다.
                int sampling = (Math.max(reader.getWidth(0), reader.getHeight(0)) + 1023) / 1024;
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                return reader.read(0, parameters) != null && !warning[0];
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }
}
