import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.imageio.ImageIO;

/** 테스트에서만 원본의 첫 프레임을 회전한 실제 JPEG로 만든다. CP 호출 증거가 아니다. */
class PreparedMediaFixture {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        // 픽셀 중심 좌표를 계산하는 디코더와 독립적인 이미지 경계 affine 행렬이다.
        double[][] matrices = {
            {1,0,0,1,0,0}, {-1,0,0,1,224,0}, {-1,0,0,-1,224,160}, {1,0,0,-1,0,160},
            {0,1,1,0,0,0}, {0,1,-1,0,160,0}, {0,-1,-1,0,160,224}, {0,-1,1,0,0,224}
        };
        // 전송 JPEG의 알려진 좌표 벡터다. 위 행렬이나 디코더 함수를 호출하지 않는다.
        int[] forward = {28,52,76,100,124,148,172,196};
        int[] reverse = {195,171,147,123,99,75,51,27};
        int[][] columns = {forward,reverse,reverse,forward,forward,forward,reverse,reverse};
        int[][] rows = {{28,52},{28,52},{131,107},{131,107},{28,52},{131,107},{131,107},{28,52}};
        var checks = new ArrayList<String>();
        for (int id = 1; id <= 30; id++) {
            String format = id <= 8 ? "jpeg" : id % 3 == 0 ? "gif" : id % 3 == 1 ? "png" : "jpeg";
            int orientation = id <= 8 ? id : 1;
            BufferedImage raw = ImageIO.read(dir.resolve("asset-" + id + "." + format).toFile());
            BufferedImage prepared = new BufferedImage(orientation >= 5 ? 160 : 224,
                orientation >= 5 ? 224 : 160, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = prepared.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, prepared.getWidth(), prepared.getHeight());
            g.drawImage(raw, new AffineTransform(matrices[orientation - 1]), null);
            g.dispose();
            ImageIO.write(prepared, "jpeg", dir.resolve("received-" + (31 - id) + ".jpeg").toFile());
            if (id <= 8) {
                BufferedImage encoded = ImageIO.read(dir.resolve("received-" + (31 - id) + ".jpeg").toFile());
                var pixels = new ArrayList<String>();
                for (int bit = 0; bit < 16; bit++) {
                    int x = id <= 4 ? columns[id - 1][bit % 8] : rows[id - 1][bit / 8];
                    int y = id <= 4 ? rows[id - 1][bit / 8] : columns[id - 1][bit % 8];
                    pixels.add(Integer.toString(encoded.getRGB(x, y) & 0xffffff));
                }
                checks.add("{\"orientation\":" + id + ",\"width\":" + encoded.getWidth()
                    + ",\"height\":" + encoded.getHeight() + ",\"pixels\":[" + String.join(",", pixels) + "]}");
            }
        }
        BufferedImage ambiguous = ImageIO.read(dir.resolve("ambiguous.png").toFile());
        ImageIO.write(ambiguous, "jpeg", dir.resolve("ambiguous.jpeg").toFile());
        System.out.println("[" + String.join(",", checks) + "]");
    }
}
