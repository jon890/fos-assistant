"""합성 사진으로 첫 프레임·방향·alpha와 decode 이전 자원 경계를 검증한다."""
import importlib
import io
import pathlib
import struct
import sys
import threading
import time
import unittest
from unittest.mock import patch

from PIL import Image, ImageOps
from test_fos_ctx import load_ctx
from plugin_loading import load_plugin

PLUGIN = pathlib.Path(__file__).resolve().parents[1] / "plugins" / "fos-ctx"
sys.path.insert(0, str(PLUGIN))
from image_conversion import convert, ResultLimit, LimitedWriter
from image_headers import preflight, MAX_INPUT


def encode(image, format, **kwargs):
    output = io.BytesIO()
    image.save(output, format=format, **kwargs)
    return output.getvalue()


class ImageFormatsTest(unittest.TestCase):
    def test_lossy_lossless_alpha_and_exif_eight_display_regions(self):
        source = Image.new("RGBA", (19, 13))
        for y in range(13):
            for x in range(19):
                source.putpixel((x, y), (x * 10, y * 15, 30, 0 if x == 0 else 120))
        for lossless in (True, False):
            for orientation in range(1, 9):
                exif = Image.Exif()
                exif[274] = orientation
                raw = encode(source, "WEBP", lossless=lossless, exif=exif)
                with Image.open(io.BytesIO(raw)) as decoded:
                    reference = ImageOps.exif_transpose(decoded).convert("RGBA").crop((0, 0, 9, 7))
                png = convert(raw, "image/webp", [0, 0, 9, 7])
                with Image.open(io.BytesIO(png)) as result:
                    self.assertEqual(result.size, reference.size)
                    self.assertEqual(result.tobytes(), reference.tobytes())
                    self.assertNotIn(274, result.getexif())

    def test_gif_transparency_and_animation_only_first_frame(self):
        first = Image.new("RGBA", (7, 5), "red")
        first.putpixel((0, 0), (0, 0, 0, 0))
        for animation in (False, True):
            kwargs = {"save_all": True, "append_images": [Image.new("RGBA", (7, 5), "blue")]} if animation else {}
            raw = encode(first, "GIF", **kwargs)
            result = Image.open(io.BytesIO(convert(raw, "image/gif", None)))
            self.assertEqual(result.getpixel((1, 1)), (255, 0, 0, 255))
            self.assertEqual(result.getpixel((0, 0))[3], 0)

    def test_animated_webp_checks_all_frame_headers_and_returns_first(self):
        raw = encode(Image.new("RGB", (8, 6), "red"), "WEBP", lossless=True, save_all=True,
                     append_images=[Image.new("RGB", (8, 6), "blue")], duration=100)
        self.assertEqual(preflight(raw, "image/webp"), (8, 6))
        with Image.open(io.BytesIO(convert(raw, "image/webp", None))) as result:
            self.assertEqual(result.getpixel((1, 1)), (255, 0, 0, 255))
        corrupted = bytearray(raw)
        offset = corrupted.rindex(b"ANMF") + 8
        corrupted[offset + 6:offset + 9] = (10000).to_bytes(3, "little")
        with patch.object(Image, "open", side_effect=AssertionError("must reject before codec")):
            with self.assertRaises(ValueError):
                convert(bytes(corrupted), "image/webp", None)

    def test_mime_truncation_riff_padding_bombs_and_canvas_mismatch_precede_open(self):
        raw = encode(Image.new("RGB", (8, 6)), "WEBP", lossless=True)
        extended = b"VP8X" + struct.pack("<I", 10) + b"\x00" * 4 + b"\x00" * 6
        mismatch = b"RIFF" + struct.pack("<I", len(raw) - 8 + len(extended)) + b"WEBP" + extended + raw[12:]
        gif = bytearray(encode(Image.new("RGB", (8, 6)), "GIF"))
        gif[6:10] = struct.pack("<HH", 65535, 65535)
        samples = [(raw[:-1], "image/webp"), (mismatch, "image/webp"), (bytes(gif), "image/gif"),
                   (raw, "image/gif"), (b"x" * (MAX_INPUT + 1), "image/webp")]
        with patch.object(Image, "open", side_effect=AssertionError("must reject before codec")):
            for body, mime in samples:
                with self.assertRaises(ValueError):
                    convert(body, mime, None)

    def test_codec_dimension_mismatch_is_rejected_before_load(self):
        raw = encode(Image.new("RGB", (8, 6)), "WEBP", lossless=True)
        fake = unittest.mock.MagicMock()
        fake.__enter__.return_value = fake
        fake.size = (9, 6)
        with patch.object(Image, "open", return_value=fake):
            with self.assertRaises(ValueError):
                convert(raw, "image/webp", None)
        fake.load.assert_not_called()

    def test_result_limit_reports_display_size_and_crop_succeeds(self):
        raw = encode(Image.new("RGB", (4001, 4000), "red"), "WEBP", lossless=True)
        with self.assertRaises(ResultLimit) as result:
            convert(raw, "image/webp", None)
        self.assertEqual((result.exception.width, result.exception.height), (4001, 4000))
        with Image.open(io.BytesIO(convert(raw, "image/webp", [0, 0, 10, 10]))) as result:
            self.assertEqual(result.size, (10, 10))
        with patch("image_conversion.MAX_OUTPUT", 3), LimitedWriter() as writer:
            self.assertEqual(writer.write(b"ab"), 2)
            self.assertEqual(writer.write(b"c"), 1)
            with self.assertRaises(OverflowError):
                writer.write(b"d")

    def test_runtime_survives_profile_namespace_and_reload(self):
        first = load_ctx(self.addCleanup)
        second = load_plugin("fos_second_profile_test", PLUGIN / "__init__.py", self.addCleanup)
        a = importlib.import_module(first.__name__ + ".image_runtime")
        b = importlib.import_module(second.__name__ + ".image_runtime")
        results = []
        threads = [threading.Thread(target=lambda module=m: results.append(module.shared_runtime()))
                   for m in (a, b) * 8]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertTrue(all(result is results[0] for result in results))
        self.assertIs(importlib.reload(a).shared_runtime(), results[0])

    def test_fifo_timeout_and_permission_failure_do_not_leak_slot(self):
        plugin = load_ctx(self.addCleanup)
        module = importlib.import_module(plugin.__name__ + ".image_runtime")
        runtime = module.Runtime()
        order = []
        def queued(number):
            with runtime.slot(time.monotonic() + 3, lambda: None):
                order.append(number)
        with runtime.slot(time.monotonic() + 3, lambda: None):
            workers = []
            for number in range(3):
                worker = threading.Thread(target=queued, args=(number,))
                worker.start()
                workers.append(worker)
                while len(runtime.queue) < number + 1:
                    time.sleep(.001)
            with self.assertRaises(TimeoutError):
                with runtime.slot(time.monotonic() + .01, lambda: None):
                    pass
        for worker in workers:
            worker.join()
        self.assertEqual(order, [0, 1, 2])
        self.assertFalse(runtime.busy)
        with self.assertRaises(ValueError):
            with runtime.slot(time.monotonic() + 1, lambda: (_ for _ in ()).throw(ValueError())):
                pass
        self.assertEqual(len(runtime.queue), 0)

    def test_exact_pixel_input_and_writer_limits(self):
        from image_headers import dimensions
        self.assertEqual(dimensions(60_000_000, 1), (60_000_000, 1))
        with self.assertRaises(ValueError):
            dimensions(60_000_001, 1)
        raw = encode(Image.new("RGB", (1, 1)), "WEBP", lossless=True)
        padding_length = MAX_INPUT - len(raw) - 8
        bounded = b"RIFF" + struct.pack("<I", MAX_INPUT - 8) + raw[8:] + b"XMP " \
            + struct.pack("<I", padding_length) + b"x" * padding_length
        self.assertEqual(preflight(bounded, "image/webp"), (1, 1))
        with self.assertRaises(ValueError):
            preflight(bounded + b"x", "image/webp")
        with LimitedWriter() as writer:
            self.assertEqual(writer.write(b"x" * (10 * 1024 * 1024 - 1)), 10 * 1024 * 1024 - 1)
            self.assertEqual(writer.write(b"x"), 1)
            with self.assertRaises(OverflowError):
                writer.write(b"x")
            with self.assertRaises(TypeError):
                writer.write(123)

    def test_explicit_overview_shows_scene_and_followup_crop_retains_pixels(self):
        # 큰 전체의 실패 뒤 개요로 위치를 고르고 같은 원본 표시 좌표로 확대한다.
        source = Image.new("RGB", (5000, 4000), "white")
        from PIL import ImageDraw
        ImageDraw.Draw(source).rectangle((3500, 2500, 4999, 3999), fill="red")
        raw = encode(source, "WEBP", lossless=True)
        with self.assertRaises(ResultLimit):
            convert(raw, "image/webp", None)
        details = {}
        overview = convert(raw, "image/webp", None, True, details)
        self.assertTrue(details["overview"])
        self.assertEqual((details["display_width"], details["display_height"]), (5000, 4000))
        with Image.open(io.BytesIO(overview)) as result:
            self.assertEqual(result.size, (1600, 1280))
            self.assertEqual(result.getpixel((1550, 1230)), (255, 0, 0, 255))
        with Image.open(io.BytesIO(convert(raw, "image/webp", [4900, 3900, 5000, 4000]))) as result:
            self.assertEqual(result.size, (100, 100))
            self.assertEqual(result.getpixel((10, 10)), (255, 0, 0, 255))

    def test_overview_all_mimes_preserves_original_display_dimensions(self):
        source = Image.new("RGB", (2000, 1000), "red")
        exif = Image.Exif()
        exif[274] = 6
        for mime, format in [("image/jpeg", "JPEG"), ("image/png", "PNG"),
                             ("image/gif", "GIF"), ("image/webp", "WEBP")]:
            options = {"exif": exif} if format != "GIF" else {}
            raw = encode(source, format, **options)
            details = {}
            result = Image.open(io.BytesIO(convert(raw, mime, None, True, details)))
            expected = (1000, 2000) if format != "GIF" else (2000, 1000)
            self.assertEqual((details["display_width"], details["display_height"]), expected)
            self.assertLessEqual(max(result.size), 1600)
            self.assertEqual(result.size, (details["result_width"], details["result_height"]))
