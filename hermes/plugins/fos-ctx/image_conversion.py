"""프로세스 안에서만 Pillow 설정을 바꾸고 첫 표시 프레임을 PNG로 만든다."""
import io
import warnings

from image_headers import preflight

MAX_RESULT_PIXELS = 16_000_000
MAX_OUTPUT = 10 * 1024 * 1024


class ResultLimit(ValueError):
    def __init__(self, width, height):
        self.width, self.height = width, height


class LimitedWriter(io.BytesIO):
    def write(self, data):
        if self.tell() + len(data) > MAX_OUTPUT:
            raise OverflowError("PNG limit")
        return super().write(data)


def convert(raw, mime, region, overview=False, details=None):
    if overview and region is not None:
        raise ValueError("overview excludes region")
    expected = preflight(raw, mime)
    from PIL import Image, ImageOps, features
    if mime == "image/webp" and not features.check("webp"):
        raise ValueError("WebP codec unavailable")
    Image.MAX_IMAGE_PIXELS = 60_000_000
    with warnings.catch_warnings():
        warnings.simplefilter("error", Image.DecompressionBombWarning)
        format = {"image/webp": "WEBP", "image/gif": "GIF", "image/png": "PNG", "image/jpeg": "JPEG"}[mime]
        with Image.open(io.BytesIO(raw), formats=[format]) as image:
            if image.size != expected:
                raise ValueError("codec dimensions mismatch")
            orientation = image.getexif().get(274, 1)
            width, height = image.size[::-1] if orientation in {5, 6, 7, 8} else image.size
            if details is not None:
                details.update(display_width=width, display_height=height, orientation_applied=True, overview=overview)
            area = region or [0, 0, width, height]
            x1, y1, x2, y2 = area
            if not (0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height):
                raise ValueError("invalid display region")
            if not overview and (x2 - x1) * (y2 - y1) > MAX_RESULT_PIXELS:
                raise ResultLimit(width, height)
            if overview and mime == "image/jpeg":
                image.draft("RGB", (1600, 1600))
            image.seek(0)
            image.load()
            displayed = ImageOps.exif_transpose(image)
            try:
                if overview:
                    displayed.thumbnail((1600, 1600), Image.Resampling.LANCZOS)
                    area = [0, 0, displayed.width, displayed.height]
                with displayed.crop(tuple(area)) as cropped, cropped.convert("RGBA") as rgba, LimitedWriter() as output:
                    if details is not None:
                        details.update(result_width=rgba.width, result_height=rgba.height)
                    rgba.info.clear()
                    try:
                        rgba.save(output, format="PNG")
                    except OverflowError:
                        raise ResultLimit(width, height) from None
                    return output.getvalue()
            finally:
                if displayed is not image:
                    displayed.close()
