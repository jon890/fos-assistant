import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/** OS 글꼴 없이 합성 사진을 만들고 디코딩한 픽셀에서 ID를 회수한다. */
class MediaFixtureGenerator {
    static final int WIDTH = 224, HEIGHT = 160, CELL = 24, OFFSET = 16;
    static final String[] GLYPHS = {
        "00001000", "01001000", "01001100", "10101000", "10101000", "00001000", "00001000", "00001000",
        "11101000", "01001000", "10101000", "00001000", "00000000", "10000000", "10000000", "11111110"
    };

    public static void main(String[] args) throws Exception {
        if (args[0].equals("inspect-prepared-batch")) {
            int count = (args.length - 1) / 2;
            if (args.length % 2 != 1 || count < 1 || count > 30) {
                throw new IllegalArgumentException("준비 이미지 배치는 1~30장이다");
            }
            var measurements = new ArrayList<String>();
            for (int index = 1; index < args.length; index += 2) {
                measurements.add(inspectPrepared(Path.of(args[index]), Integer.parseInt(args[index + 1])));
            }
            System.out.println("[" + String.join(",", measurements) + "]");
            return;
        }
        if (args[0].equals("special")) {
            System.out.println(special(Path.of(args[1])));
            return;
        }
        if (args[0].equals("inspect")) {
            System.out.println(inspect(Path.of(args[1]), args[2], Integer.parseInt(args[3])));
            return;
        }
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        int count = Integer.parseInt(args[1]);
        var entries = new ArrayList<String>();
        for (int id = 1; id <= count; id++) {
            String format = id <= 8 ? "JPEG" : id % 3 == 0 ? "GIF" : id % 3 == 1 ? "PNG" : "JPEG";
            int orientation = id <= 8 ? id : 1;
            Path file = dir.resolve("asset-" + id + "." + format.toLowerCase());
            BufferedImage image = image(id, format.equals("PNG"));
            write(file, format, image, id);
            if (format.equals("JPEG")) Files.write(file, withExif(Files.readAllBytes(file), orientation));
            entries.add("{\"ordinal\":" + id + ",\"format\":\"" + format
                + "\",\"orientation\":" + orientation + ",\"measurement\":" + inspect(file, format, orientation) + "}");
        }
        BufferedImage ambiguous = image(1, false);
        Graphics2D g = ambiguous.createGraphics();
        g.setColor(new Color(128, 128, 128));
        g.fillRect(OFFSET, OFFSET, CELL, CELL);
        g.dispose();
        ImageIO.write(ambiguous, "png", dir.resolve("ambiguous.png").toFile());
        Files.write(dir.resolve("corrupt.jpeg"), new byte[]{(byte)255, (byte)216, 0, 0});
        System.out.println("[" + String.join(",", entries) + "]");
    }

    /**
     * 특수 사례를 만든다. 고해상도 간판의 작은 글씨, 글씨 없는 풍경, 바이트가 같은 사진, 몇 화소만 다른 사진이다.
     * 사진마다 같은 방식의 표식을 크기에 맞춰 키워 그리고, 원본과 Control Plane 사본 규격(긴 변 1600px, JPEG 0.85)으로
     * 줄인 사본에서 표식과 글씨를 따로 회수한다.
     */
    static String special(Path dir) throws Exception {
        Files.createDirectories(dir);
        var entries = new ArrayList<String>();
        // 4032x3024 간판: 큰 제목 글씨와 1px 획의 작은 메뉴 글씨를 함께 둔다.
        BufferedImage sign = canvas(4032, 3024, Color.WHITE);
        markerAt(sign, 40, 18);
        glyphAt(sign, 96, 1500, 24);
        for (int row = 0; row < 20; row++) glyphAt(sign, 96, 2100 + row * 40, 1);
        entries.add(writeSpecial(dir, "sign-hires", "JPEG", sign, 40, 18, true));
        // 1920x1080 풍경: 하늘과 땅의 그라데이션과 해만 두고 글씨는 두지 않는다.
        BufferedImage landscape = canvas(1920, 1080, Color.WHITE);
        Graphics2D g = landscape.createGraphics();
        for (int y = 0; y < 1080; y++) {
            g.setColor(y < 600 ? new Color(120, 170 + y / 10, 230) : new Color(70, 150 - (y - 600) / 8, 80));
            g.fillRect(0, y, 1920, 1);
        }
        g.setColor(new Color(250, 220, 120));
        g.fillOval(1500, 120, 220, 220);
        g.dispose();
        markerAt(landscape, 41, 4);
        entries.add(writeSpecial(dir, "landscape", "JPEG", landscape, 41, 4, false));
        // 같은 사진 두 장: 두 번째 파일은 첫 파일의 바이트 복사본이다.
        entries.add(writeSpecial(dir, "identical-a", "PNG", image(42, false), 42, 1, true));
        Files.copy(dir.resolve("identical-a.png"), dir.resolve("identical-b.png"));
        entries.add(specialEntry(dir.resolve("identical-b.png"), "identical-b", "PNG", 42, 1, true));
        // 거의 같은 사진 두 장: 표식과 글씨 밖의 2x2 화소만 다르다.
        entries.add(writeSpecial(dir, "near-a", "PNG", image(44, false), 44, 1, true));
        BufferedImage near = image(44, false);
        for (int y = 140; y < 142; y++) for (int x = 200; x < 202; x++) near.setRGB(x, y, 0x808080);
        entries.add(writeSpecial(dir, "near-b", "PNG", near, 44, 1, true));
        return "[" + String.join(",", entries) + "]";
    }

    static BufferedImage canvas(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    /** 224x160 사진과 같은 격자 표식을 {@code scale} 배로 그린다. */
    static void markerAt(BufferedImage image, int id, int scale) {
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, (OFFSET * 2 + CELL * 8) * scale, (OFFSET * 2 + CELL * 2) * scale);
        int marker = 0xa500 | id;
        for (int bit = 0; bit < 16; bit++) {
            g.setColor((marker & (1 << (15 - bit))) == 0 ? Color.BLACK : Color.WHITE);
            g.fillRect((OFFSET + bit % 8 * CELL) * scale, (OFFSET + bit / 8 * CELL) * scale, CELL * scale, CELL * scale);
        }
        g.dispose();
    }

    /** 「사진」 bitmap 의 한 화소를 {@code scale} 화소 정사각형으로 그린다. */
    static void glyphAt(BufferedImage image, int left, int top, int scale) {
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        for (int row = 0; row < GLYPHS.length; row++) {
            for (int col = 0; col < 8; col++) {
                if (GLYPHS[row].charAt(col) == '1') {
                    g.fillRect(left + (row / 8 * 10 + col) * scale, top + row % 8 * scale, scale, scale);
                }
            }
        }
        g.dispose();
    }

    /** 글씨 bitmap 의 화소 중심이 모두 정확한 흑백으로 읽히면 참이다. */
    static boolean glyphReadable(BufferedImage image, double left, double top, double scale) {
        for (int row = 0; row < GLYPHS.length; row++) {
            for (int col = 0; col < 8; col++) {
                int x = (int)Math.floor(left + (row / 8 * 10 + col + 0.5) * scale);
                int y = (int)Math.floor(top + (row % 8 + 0.5) * scale);
                int pixel = image.getRGB(x, y);
                int r = pixel >> 16 & 255, gr = pixel >> 8 & 255, b = pixel & 255;
                boolean black = r <= 64 && gr <= 64 && b <= 64, white = r >= 192 && gr >= 192 && b >= 192;
                if (GLYPHS[row].charAt(col) == '1' ? !black : !white) return false;
            }
        }
        return true;
    }

    /** {@code scale} 배 표식을 읽는다. 표식 칸의 중심 화소만 본다. */
    static Integer recoverScaled(BufferedImage image, double scale) {
        int marker = 0;
        for (int bit = 0; bit < 16; bit++) {
            int x = (int)Math.floor((OFFSET + bit % 8 * CELL + CELL / 2.0) * scale);
            int y = (int)Math.floor((OFFSET + bit / 8 * CELL + CELL / 2.0) * scale);
            int pixel = image.getRGB(x, y);
            int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
            boolean black = r <= 64 && g <= 64 && b <= 64, white = r >= 192 && g >= 192 && b >= 192;
            if (!black && !white) return null;
            marker = marker << 1 | (white ? 1 : 0);
        }
        return (marker >> 8) == 0xa5 ? marker & 255 : null;
    }

    /** EXIF 가 없는 특수 사례를 Control Plane 처럼 subsampling 디코딩한 뒤 1600px JPEG 로 줄인다. */
    static BufferedImage prepared(Path file) throws Exception {
        BufferedImage source;
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input, true, true);
                int sampling = Math.max(1, Math.max(reader.getWidth(0), reader.getHeight(0)) / 1600);
                var parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                source = reader.read(0, parameters);
            } finally { reader.dispose(); }
        }
        double ratio = Math.min(1.0, 1600.0 / Math.max(source.getWidth(), source.getHeight()));
        int width = (int)Math.round(source.getWidth() * ratio), height = (int)Math.round(source.getHeight() * ratio);
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(scaled, null, null), param);
        } finally { writer.dispose(); }
        return ImageIO.read(new java.io.ByteArrayInputStream(bytes.toByteArray()));
    }

    static String writeSpecial(Path dir, String key, String format, BufferedImage image, int id, int scale, boolean text)
        throws Exception {
        Path file = dir.resolve(key + "." + format.toLowerCase());
        write(file, format, image, id);
        return specialEntry(file, key, format, id, scale, text);
    }

    /** 실제 파일을 다시 읽어 측정한다. 기대 ID 는 출력에만 적고 회수에는 쓰지 않는다. */
    static String specialEntry(Path file, String key, String format, int id, int scale, boolean text) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        BufferedImage raw = ImageIO.read(file.toFile());
        BufferedImage copy = prepared(file);
        double ratio = (double)copy.getWidth() / raw.getWidth();
        // 이름표 글씨는 간판에서 24배 제목이고, 작은 사진에서는 기존 위치(16,100)의 1배 bitmap 이다. 메뉴 글씨는 간판에만 있다.
        boolean sign = key.equals("sign-hires");
        double left = sign ? 96 : 16, top = sign ? 1500 : 100, glyph = sign ? 24 : 1;
        int textPixels = 0;
        int markerRight = (OFFSET * 2 + CELL * 8) * scale, markerBottom = (OFFSET * 2 + CELL * 2) * scale;
        for (int y = 0; y < raw.getHeight(); y++) for (int x = 0; x < raw.getWidth(); x++) {
            if (x < markerRight && y < markerBottom) continue;
            int pixel = raw.getRGB(x, y);
            if ((pixel >> 16 & 255) <= 64 && (pixel >> 8 & 255) <= 64 && (pixel & 255) <= 64) textPixels++;
        }
        return "{\"assetKey\":\"" + key + "\",\"format\":\"" + format + "\",\"expectedMarker\":" + id
            + ",\"fileName\":\"" + file.getFileName() + "\",\"width\":" + raw.getWidth() + ",\"height\":" + raw.getHeight()
            + ",\"byteLength\":" + bytes.length + ",\"bytesSha256\":\""
            + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "\""
            + ",\"recoveredId\":" + recoverScaled(raw, scale)
            + ",\"preparedWidth\":" + copy.getWidth() + ",\"preparedHeight\":" + copy.getHeight()
            + ",\"preparedRecoveredId\":" + recoverScaled(copy, scale * ratio)
            + ",\"textPixels\":" + textPixels
            + ",\"labelTextOriginal\":" + (text && glyphReadable(raw, left, top, glyph))
            + ",\"labelTextPrepared\":" + (text && glyphReadable(copy, left * ratio, top * ratio, glyph * ratio))
            + ",\"menuTextOriginal\":" + (sign && glyphReadable(raw, 96, 2100, 1))
            + ",\"menuTextPrepared\":" + (sign && glyphReadable(copy, 96 * ratio, 2100 * ratio, ratio)) + "}";
    }

    /** 원본 inspect와 분리한다. 방향이 이미 적용된 실제 JPEG의 크기와 픽셀만 읽는다. */
    static String inspectPrepared(Path file, int orientation) throws Exception {
        if (orientation < 1 || orientation > 8) throw new IllegalArgumentException("EXIF 방향 오류");
        byte[] bytes = Files.readAllBytes(file);
        Integer width = null, height = null, recovered = null;
        String error = null;
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) error = "DECODE_FAILED";
            else {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in);
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                    if (!reader.getFormatName().equalsIgnoreCase("JPEG")) error = "NOT_JPEG";
                    else if (width != (orientation >= 5 ? HEIGHT : WIDTH)
                        || height != (orientation >= 5 ? WIDTH : HEIGHT)) error = "DIMENSION_MISMATCH";
                    else {
                        BufferedImage image = reader.read(0);
                        int marker = 0;
                        for (int bit = 0; bit < 16; bit++) {
                            int x = 28 + 24 * (bit % 8), y = 28 + 24 * (bit / 8);
                            // 기대 ID와 파일 이름을 읽지 않는다. 수신 크기의 픽셀 좌표로만 회수한다.
                            int[] point = switch (orientation) {
                                case 2 -> new int[]{width - 1 - x, y};
                                case 3 -> new int[]{width - 1 - x, height - 1 - y};
                                case 4 -> new int[]{x, height - 1 - y};
                                case 5 -> new int[]{y, x};
                                case 6 -> new int[]{width - 1 - y, x};
                                case 7 -> new int[]{width - 1 - y, height - 1 - x};
                                case 8 -> new int[]{y, height - 1 - x};
                                default -> new int[]{x, y};
                            };
                            int pixel = image.getRGB(point[0], point[1]);
                            int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
                            boolean black = r <= 64 && g <= 64 && b <= 64;
                            boolean white = r >= 192 && g >= 192 && b >= 192;
                            if (!black && !white) { error = "MARKER_UNREADABLE"; break; }
                            marker = marker << 1 | (white ? 1 : 0);
                        }
                        if (error == null) {
                            if ((marker >> 8) == 0xa5) recovered = marker & 255;
                            else error = "MARKER_UNREADABLE";
                        }
                    }
                } finally { reader.dispose(); }
            }
        } catch (java.io.IOException exception) { error = "DECODE_FAILED"; }
        return "{\"recoveredId\":" + recovered + ",\"width\":" + width + ",\"height\":" + height
            + ",\"byteLength\":" + bytes.length + ",\"bytesSha256\":\""
            + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "\""
            + (error == null ? "" : ",\"errorCode\":\"" + error + "\"") + "}";
    }

    static BufferedImage image(int id, boolean alpha) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT,
            alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        int marker = 0xa500 | id;
        for (int bit = 0; bit < 16; bit++) {
            g.setColor((marker & (1 << (15 - bit))) == 0 ? Color.BLACK : Color.WHITE);
            g.fillRect(OFFSET + bit % 8 * CELL, OFFSET + bit / 8 * CELL, CELL, CELL);
        }
        // 「사진」의 작은 고정 bitmap이다. ID 회수와 OCR 의미 판독은 별개다.
        g.setColor(Color.BLACK);
        for (int row = 0; row < GLYPHS.length; row++) {
            for (int col = 0; col < 8; col++) {
                if (GLYPHS[row].charAt(col) == '1') g.fillRect(16 + row / 8 * 10 + col, 100 + row % 8, 1, 1);
            }
        }
        g.dispose();
        if (alpha) image.setRGB(WIDTH - 1, HEIGHT - 1, 0);
        return image;
    }

    static void write(Path file, String format, BufferedImage image, int id) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext() || !ImageIO.getImageReadersByFormatName(format).hasNext()) {
            throw new IllegalStateException("reader/writer 없음: " + format);
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            if (format.equals("GIF")) {
                writer.prepareWriteSequence(null);
                writer.writeToSequence(new IIOImage(image, null, null), null);
                writer.writeToSequence(new IIOImage(image(id + 64, false), null, null), null);
                writer.endWriteSequence();
            } else writer.write(image);
        } finally { writer.dispose(); }
    }

    static byte[] withExif(byte[] jpeg, int orientation) throws Exception {
        byte[] exif = {69,120,105,102,0,0,73,73,42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,
            (byte)orientation,0,0,0,0,0,0,0};
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(new byte[]{(byte)255,(byte)225,0,(byte)(exif.length + 2)});
        out.write(exif);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    static int[] displayed(int x, int y, int orientation) {
        return switch (orientation) {
            case 2 -> new int[]{WIDTH - 1 - x, y};
            case 3 -> new int[]{WIDTH - 1 - x, HEIGHT - 1 - y};
            case 4 -> new int[]{x, HEIGHT - 1 - y};
            case 5 -> new int[]{y, x};
            case 6 -> new int[]{HEIGHT - 1 - y, x};
            case 7 -> new int[]{HEIGHT - 1 - y, WIDTH - 1 - x};
            case 8 -> new int[]{y, WIDTH - 1 - x};
            default -> new int[]{x, y};
        };
    }

    static Integer recover(BufferedImage image, boolean jpeg) {
        int marker = 0;
        for (int bit = 0; bit < 16; bit++) {
            int pixel = image.getRGB(OFFSET + bit % 8 * CELL + CELL / 2, OFFSET + bit / 8 * CELL + CELL / 2);
            int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
            boolean black = jpeg ? r <= 64 && g <= 64 && b <= 64 : r == 0 && g == 0 && b == 0;
            boolean white = jpeg ? r >= 192 && g >= 192 && b >= 192 : r == 255 && g == 255 && b == 255;
            if (!black && !white) return null;
            marker = marker << 1 | (white ? 1 : 0);
        }
        return (marker >> 8) == 0xa5 ? marker & 255 : null;
    }

    static String inspect(Path file, String format, int orientation) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return "{\"errorCode\":\"DECODE_FAILED\"}";
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                BufferedImage raw = reader.read(0);
                Integer recovered = recover(raw, format.equals("JPEG"));
                int frames = reader.getNumImages(true);
                Integer second = frames > 1 ? recover(reader.read(1), false) : null;
                BufferedImage display = new BufferedImage(orientation >= 5 ? HEIGHT : WIDTH,
                    orientation >= 5 ? WIDTH : HEIGHT, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                    int[] point = displayed(x, y, orientation);
                    display.setRGB(point[0], point[1], raw.getRGB(x, y));
                }
                var glyphRows = new ArrayList<String>();
                for (int row = 0; row < GLYPHS.length; row++) {
                    var pixels = new ArrayList<String>();
                    for (int col = 0; col < 8; col++) pixels.add(Integer.toString(raw.getRGB(16 + row / 8 * 10 + col, 100 + row % 8) & 0xffffff));
                    glyphRows.add("[" + String.join(",", pixels) + "]");
                }
                var samples = new ArrayList<String>();
                for (int bit = 0; bit < 16; bit++) {
                    int x = OFFSET + bit % 8 * CELL + CELL / 2, y = OFFSET + bit / 8 * CELL + CELL / 2;
                    int[] point = displayed(x, y, orientation);
                    samples.add("{\"x\":" + x + ",\"y\":" + y + ",\"displayX\":" + point[0]
                        + ",\"displayY\":" + point[1] + ",\"rgb\":" + (raw.getRGB(x,y) & 0xffffff)
                        + ",\"displayRgb\":" + (display.getRGB(point[0],point[1]) & 0xffffff) + "}");
                }
                return "{\"recoveredId\":" + recovered + ",\"secondFrameId\":" + second
                    + ",\"frames\":" + frames + ",\"byteLength\":" + bytes.length
                    + ",\"bytesSha256\":\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    + "\",\"alpha\":" + (raw.getRGB(WIDTH-1,HEIGHT-1) >>> 24)
                    + ",\"glyphPixels\":[" + String.join(",", glyphRows) + "]"
                    + ",\"samples\":[" + String.join(",", samples) + "]}";
            } catch (java.io.IOException exception) {
                return "{\"errorCode\":\"DECODE_FAILED\"}";
            } finally { reader.dispose(); }
        }
    }
}
