package com.bifos.assistant.chat.application;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 사진을 에이전트에게 보일 긴 변 1600px JPEG 로 줄인다. 근거는 ADR-20261009 / native-image-input 에 있다.
 *
 * <p>원본 해상도를 그대로 디코딩하지 않고 줄여 읽어(subsampling) 메모리를 결과 크기에 가깝게 둔다. EXIF 회전을
 * 반영하고 투명한 부분은 흰색으로 채운다. 작은 사진은 키우지 않는다. 읽는 리더가 없는 형식(WebP), 화소가
 * {@link #MAX_PIXELS} 를 넘는 사진, 디코딩이나 인코딩이 실패한 사진은 빈 값이다.
 *
 * <p>{@link #shrink} 는 보낼 때 사진이 많으면 이 사본을 더 작은 긴 변과 낮춘 JPEG 품질로 다시 줄인다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class AgentImageResizer {

    /** 결과의 긴 변 상한(px)이다. */
    static final int LONG_SIDE = 1600;

    /** JPEG 인코딩 품질이다. */
    static final float QUALITY = 0.85f;

    /** 이보다 화소가 많은 사진은 디코딩하지 않는다. 머리의 크기만 보고 거르므로 큰 사진에 디코딩 시간을 쓰지 않는다. */
    static final long MAX_PIXELS = 60_000_000L;

    private static final int DEFAULT_ORIENTATION = 1;
    private static final int ORIENTATION_TAG = 0x0112;
    private static final int SHORT_TYPE = 3;

    /** 줄인 JPEG 를 돌려준다. 읽지 못하면 빈 값이다. */
    static Optional<byte[]> toJpeg(byte[] original) {
        if (original == null || original.length == 0) {
            return Optional.empty();
        }
        try {
            BufferedImage decoded = decode(original);
            if (decoded == null) {
                return Optional.empty();
            }
            return Optional.of(encode(draw(decoded, orientation(original), LONG_SIDE), QUALITY));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * 긴 변이 {@code longSide} 를 넘으면 그 길이로 줄인 크기를, 넘지 않으면 받은 크기를 그대로 돌려준다. 짧은 변은 비율대로
     * 줄이되 1 아래로 내리지 않는다. 가로와 세로가 같으면 가로를 긴 변으로 본다. 단계를 고를 때 계산한 화소와 실제로 줄인 사진의
     * 화소가 어긋나지 않게 {@link #draw} 가 이 함수로 크기를 정한다.
     */
    static Dimension scaledSize(int width, int height, int longSide) {
        if (Math.max(width, height) <= longSide) {
            return new Dimension(width, height);
        }
        if (width >= height) {
            return new Dimension(longSide, Math.max(1, (int) Math.round((double) height * longSide / width)));
        }
        return new Dimension(Math.max(1, (int) Math.round((double) width * longSide / height)), longSide);
    }

    /** 디코딩하지 않고 머리의 가로와 세로만 읽는다. 읽는 리더가 없거나 읽지 못하면 빈 값이다. */
    static Optional<Dimension> dimensions(byte[] image) {
        if (image == null || image.length == 0) {
            return Optional.empty();
        }
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(image))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return Optional.of(new Dimension(reader.getWidth(0), reader.getHeight(0)));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * {@link #toJpeg} 가 만든 사본 JPEG 를 긴 변 {@code longSide} 로 다시 줄인다. 품질은 {@link #QUALITY} 다. 긴 변이 이미
     * {@code longSide} 이하이면 받은 바이트를 그대로 돌려준다. 읽거나 쓰지 못하면 빈 값이다.
     */
    static Optional<byte[]> shrink(byte[] jpeg, int longSide) {
        return shrink(jpeg, longSide, QUALITY);
    }

    /**
     * {@link #toJpeg} 가 만든 사본 JPEG 를 긴 변 {@code longSide}, JPEG 품질 {@code quality} 로 다시 줄인다. 사본은 EXIF 가
     * 없고 이미 바로 서 있어 방향을 읽지 않는다. 긴 변이 이미 {@code longSide} 이하이고 품질이 {@link #QUALITY} 이면 받은
     * 바이트를 그대로 돌려준다. 긴 변이 이하라도 품질이 다르면 같은 크기로 다시 인코딩한다. 읽거나 쓰지 못하면 빈 값이다.
     */
    static Optional<byte[]> shrink(byte[] jpeg, int longSide, float quality) {
        if (jpeg == null || jpeg.length == 0) {
            return Optional.empty();
        }
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (decoded == null) {
                return Optional.empty();
            }
            if (Math.max(decoded.getWidth(), decoded.getHeight()) <= longSide && quality == QUALITY) {
                return Optional.of(jpeg);
            }
            return Optional.of(encode(draw(decoded, DEFAULT_ORIENTATION, longSide), quality));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** 화소 수가 상한 안인지 본다. {@code int} 곱은 넘치므로 {@code long} 으로 곱한다. */
    static boolean withinPixelLimit(long width, long height, long maxPixels) {
        return width > 0 && height > 0 && width * height <= maxPixels;
    }

    /**
     * JPEG 의 APP1 {@code Exif} 구간에서 IFD0 의 방향 태그(1~8)를 읽는다. JPEG 가 아니거나 읽지 못하면 1 이다.
     */
    static int orientation(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || unsigned(jpeg[0]) != 0xff || unsigned(jpeg[1]) != 0xd8) {
            return DEFAULT_ORIENTATION;
        }
        int offset = 2;
        while (offset + 4 <= jpeg.length) {
            if (unsigned(jpeg[offset]) != 0xff) {
                return DEFAULT_ORIENTATION;
            }
            int marker = unsigned(jpeg[offset + 1]);
            if (marker == 0xff) {
                // 채움 바이트다.
                offset++;
                continue;
            }
            if (marker == 0xda || marker == 0xd9) {
                // 영상 데이터가 시작되면 그 뒤에 EXIF 가 오지 않는다.
                return DEFAULT_ORIENTATION;
            }
            int length = (unsigned(jpeg[offset + 2]) << 8) | unsigned(jpeg[offset + 3]);
            int body = offset + 4;
            int end = offset + 2 + length;
            if (length < 2 || end > jpeg.length) {
                return DEFAULT_ORIENTATION;
            }
            if (marker == 0xe1 && isExif(jpeg, body, end)) {
                return exifOrientation(jpeg, body + 6, end);
            }
            offset = end;
        }
        return DEFAULT_ORIENTATION;
    }

    private static BufferedImage decode(byte[] original) throws IOException {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                // EXIF 는 직접 읽으므로 리더가 메타데이터를 담아 두지 않게 한다.
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (!withinPixelLimit(width, height, MAX_PIXELS)) {
                    return null;
                }
                ImageReadParam parameters = reader.getDefaultReadParam();
                int sampling = Math.max(1, Math.max(width, height) / LONG_SIDE);
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                return reader.read(0, parameters);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 긴 변을 {@code longSide} 이하로 줄이고 방향을 바로잡아 흰 바탕에 그린다. */
    private static BufferedImage draw(BufferedImage source, int orientation, int longSide) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        Dimension size = scaledSize(sourceWidth, sourceHeight, longSide);
        int width = size.width;
        int height = size.height;
        boolean swapsSides = orientation >= 5 && orientation <= 8;
        BufferedImage result =
                new BufferedImage(swapsSides ? height : width, swapsSides ? width : height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            AffineTransform transform = orientationTransform(orientation, width, height);
            transform.concatenate(
                    AffineTransform.getScaleInstance((double) width / sourceWidth, (double) height / sourceHeight));
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /**
     * 줄인 크기 {@code width}×{@code height} 의 사진을 EXIF 방향대로 바로 세우는 변환이다. 5~8 은 가로와 세로가 바뀐다.
     */
    private static AffineTransform orientationTransform(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
    }

    private static byte[] encode(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("no jpeg writer");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static boolean isExif(byte[] jpeg, int body, int end) {
        return end - body >= 6
                && jpeg[body] == 'E'
                && jpeg[body + 1] == 'x'
                && jpeg[body + 2] == 'i'
                && jpeg[body + 3] == 'f'
                && jpeg[body + 4] == 0
                && jpeg[body + 5] == 0;
    }

    /** {@code tiff} 에서 시작하는 TIFF 머리와 IFD0 을 읽는다. 오프셋은 TIFF 머리 기준이다. */
    private static int exifOrientation(byte[] jpeg, int tiff, int end) {
        if (tiff + 8 > end) {
            return DEFAULT_ORIENTATION;
        }
        boolean littleEndian;
        if (jpeg[tiff] == 'I' && jpeg[tiff + 1] == 'I') {
            littleEndian = true;
        } else if (jpeg[tiff] == 'M' && jpeg[tiff + 1] == 'M') {
            littleEndian = false;
        } else {
            return DEFAULT_ORIENTATION;
        }
        if (readShort(jpeg, tiff + 2, littleEndian) != 0x2a) {
            return DEFAULT_ORIENTATION;
        }
        long ifd = readInt(jpeg, tiff + 4, littleEndian);
        if (ifd < 8 || tiff + ifd + 2 > end) {
            return DEFAULT_ORIENTATION;
        }
        int entries = tiff + (int) ifd;
        int count = readShort(jpeg, entries, littleEndian);
        for (int i = 0; i < count; i++) {
            int entry = entries + 2 + i * 12;
            if (entry + 12 > end) {
                return DEFAULT_ORIENTATION;
            }
            if (readShort(jpeg, entry, littleEndian) == ORIENTATION_TAG) {
                if (readShort(jpeg, entry + 2, littleEndian) != SHORT_TYPE) {
                    return DEFAULT_ORIENTATION;
                }
                int value = readShort(jpeg, entry + 8, littleEndian);
                return value >= 1 && value <= 8 ? value : DEFAULT_ORIENTATION;
            }
        }
        return DEFAULT_ORIENTATION;
    }

    private static int readShort(byte[] bytes, int offset, boolean littleEndian) {
        int first = unsigned(bytes[offset]);
        int second = unsigned(bytes[offset + 1]);
        return littleEndian ? (second << 8) | first : (first << 8) | second;
    }

    private static long readInt(byte[] bytes, int offset, boolean littleEndian) {
        long value = 0;
        for (int i = 0; i < 4; i++) {
            int index = littleEndian ? offset + 3 - i : offset + i;
            value = (value << 8) | unsigned(bytes[index]);
        }
        return value;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
