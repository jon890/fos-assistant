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
