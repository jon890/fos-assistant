"""codec가 버퍼를 만들기 전에 제한된 GIF/WebP 구조와 치수를 확인한다."""
import struct

MAX_PIXELS = 60_000_000
MAX_INPUT = 20 * 1024 * 1024


def dimensions(width, height):
    if width < 1 or height < 1 or width * height > MAX_PIXELS:
        raise ValueError("unsafe dimensions")
    return width, height


def chunks(data):
    offset = 0
    while offset < len(data):
        if offset + 8 > len(data):
            raise ValueError("truncated chunk")
        name, length = data[offset:offset + 4], int.from_bytes(data[offset + 4:offset + 8], "little")
        end = offset + 8 + length
        padded = end + length % 2
        if padded > len(data) or (length % 2 and data[end] != 0):
            raise ValueError("invalid chunk length or padding")
        yield name, memoryview(data)[offset + 8:end]
        offset = padded


def bitstream(name, payload):
    if name == b"VP8L" and len(payload) >= 5 and payload[0] == 0x2f:
        bits = int.from_bytes(payload[1:5], "little")
        if bits >> 29:
            raise ValueError("unsupported lossless version")
        return dimensions((bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1)
    if name == b"VP8 " and len(payload) >= 10 and not payload[0] & 1 \
            and bytes(payload[3:6]) == b"\x9d\x01\x2a":
        return dimensions(int.from_bytes(payload[6:8], "little") & 0x3fff,
                          int.from_bytes(payload[8:10], "little") & 0x3fff)
    raise ValueError("invalid bitstream")


def u24(data):
    return int.from_bytes(data, "little")


def webp_size(raw):
    if len(raw) < 20 or raw[:4] != b"RIFF" or raw[8:12] != b"WEBP" \
            or int.from_bytes(raw[4:8], "little") + 8 != len(raw):
        raise ValueError("invalid RIFF")
    canvas, still, animated, frames, animation_header = None, None, False, 0, False
    seen = set()
    for name, payload in chunks(memoryview(raw)[12:]):
        if name != b"ANMF" and name in seen:
            raise ValueError("duplicate chunk")
        if name == b"VP8X":
            if seen or len(payload) != 10 or payload[0] & 0xc1 or any(payload[1:4]):
                raise ValueError("invalid extended header")
            animated = bool(payload[0] & 2)
            canvas = dimensions(u24(payload[4:7]) + 1, u24(payload[7:10]) + 1)
        elif name in {b"VP8 ", b"VP8L"}:
            if animated or still is not None:
                raise ValueError("invalid static structure")
            still = bitstream(name, payload)
        elif name == b"ANIM":
            if not animated or len(payload) != 6 or frames:
                raise ValueError("invalid animation header")
            animation_header = True
        elif name == b"ANMF":
            if not animated or not animation_header or canvas is None or len(payload) < 24:
                raise ValueError("invalid frame")
            x, y = u24(payload[:3]) * 2, u24(payload[3:6]) * 2
            width, height = dimensions(u24(payload[6:9]) + 1, u24(payload[9:12]) + 1)
            if payload[15] & 0xfc or x + width > canvas[0] or y + height > canvas[1]:
                raise ValueError("frame outside canvas")
            frame_size, alpha = None, False
            for inner, content in chunks(payload[16:]):
                if inner == b"ALPH" and not alpha and frame_size is None:
                    alpha = True
                elif inner in {b"VP8 ", b"VP8L"} and frame_size is None:
                    frame_size = bitstream(inner, content)
                else:
                    raise ValueError("invalid frame chunks")
            if frame_size != (width, height):
                raise ValueError("frame bitstream mismatch")
            frames += 1
        elif name not in {b"ALPH", b"ICCP", b"EXIF", b"XMP "}:
            raise ValueError("unsupported chunk")
        seen.add(name)
    if animated:
        if not frames or still is not None:
            raise ValueError("missing frames")
        return canvas
    if still is None or (canvas is not None and canvas != still):
        raise ValueError("canvas bitstream mismatch")
    return still


def gif_size(raw):
    if len(raw) < 13 or raw[:6] not in {b"GIF87a", b"GIF89a"}:
        raise ValueError("invalid GIF")
    width, height = dimensions(*struct.unpack_from("<HH", raw, 6))
    offset = 13 + (3 * (2 ** ((raw[10] & 7) + 1)) if raw[10] & 0x80 else 0)
    while offset < len(raw):
        marker = raw[offset]
        offset += 1
        if marker == 0x2c:
            if offset + 9 > len(raw):
                raise ValueError("truncated descriptor")
            x, y, fw, fh = struct.unpack_from("<HHHH", raw, offset)
            dimensions(fw, fh)
            if x + fw > width or y + fh > height:
                raise ValueError("frame outside screen")
            offset += 9 + (3 * 2 ** ((raw[offset + 8] & 7) + 1) if raw[offset + 8] & 0x80 else 0)
            if offset >= len(raw) or not 2 <= raw[offset] <= 8:
                raise ValueError("invalid LZW")
            offset += 1
            break
        if marker != 0x21 or offset >= len(raw):
            raise ValueError("missing first frame")
        offset += 1
        offset = subblocks(raw, offset)
    else:
        raise ValueError("missing first frame")
    subblocks(raw, offset)
    return width, height


def subblocks(raw, offset):
    while offset < len(raw):
        length = raw[offset]
        offset += 1
        if not length:
            return offset
        offset += length
        if offset > len(raw):
            break
    raise ValueError("truncated subblocks")


def preflight(raw, mime):
    if not 0 < len(raw) <= MAX_INPUT:
        raise ValueError("input limit")
    if mime == "image/webp":
        return webp_size(raw)
    if mime == "image/gif":
        return gif_size(raw)
    if mime == "image/jpeg":
        return jpeg_size(raw)
    if mime == "image/png" and len(raw) >= 33 and raw[:8] == b"\x89PNG\r\n\x1a\n" \
            and raw[8:16] == b"\x00\x00\x00\rIHDR":
        import zlib
        if zlib.crc32(raw[12:29]) != int.from_bytes(raw[29:33], "big"):
            raise ValueError("invalid PNG header CRC")
        return dimensions(*struct.unpack_from(">II", raw, 16))
    raise ValueError("unsupported MIME")


def jpeg_size(raw):
    if raw[:2] != b"\xff\xd8":
        raise ValueError("invalid JPEG")
    offset, size = 2, None
    while offset < len(raw):
        if raw[offset] != 0xff:
            raise ValueError("invalid JPEG marker")
        while offset < len(raw) and raw[offset] == 0xff:
            offset += 1
        if offset >= len(raw):
            break
        marker = raw[offset]
        offset += 1
        if marker == 0xda:
            if size is not None:
                return size
            break
        if marker in {0, 0xd8, 0xd9} or offset + 2 > len(raw):
            break
        length = int.from_bytes(raw[offset:offset + 2], "big")
        if length < 2 or offset + length > len(raw):
            break
        if marker in {0xc0, 0xc1, 0xc2}:
            if size is not None or length < 8:
                break
            height, width = struct.unpack_from(">HH", raw, offset + 3)
            size = dimensions(width, height)
        offset += length
    raise ValueError("invalid JPEG dimensions")
