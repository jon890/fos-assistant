"""Java가 출력과 바이트 단위로 대조하는 합성 JPEG를 Pillow로도 검사한다."""

import pathlib
import unittest

try:
    from PIL import Image
except ImportError:
    Image = None


@unittest.skipIf(Image is None, "Pillow가 설치된 환경에서 디코딩을 확인한다")
class AttachmentMpoJpegTest(unittest.TestCase):
    def test_first_jpeg_decodes_without_mpf_and_keeps_pixels_and_orientation(self):
        """보조 사진을 제거한 JPEG는 첫 사진의 픽셀과 EXIF 방향을 유지한다."""
        root = pathlib.Path(__file__).resolve().parents[2]
        fixtures = root / "backend/src/test/resources/attachments"
        with Image.open(fixtures / "synthetic.mpo") as mpo:
            self.assertEqual(mpo.n_frames, 2)
            mpo.seek(0)
            mpo.load()
            with Image.open(fixtures / "first.jpg") as jpeg:
                jpeg.load()
                self.assertEqual(jpeg.format, "JPEG")
                self.assertEqual(jpeg.size, mpo.size)
                self.assertEqual(jpeg.tobytes(), mpo.tobytes())
                self.assertEqual(jpeg.getexif()[274], 6)
                self.assertEqual(jpeg.getexif()[274], mpo.getexif()[274])
        self.assertNotIn(b"MPF\0", (fixtures / "first.jpg").read_bytes())
