"""알려진 provider 본문에서 원문을 복사하지 않고 제한된 수치만 센다."""

from dataclasses import dataclass
from itertools import islice
from typing import Literal, TypedDict

Completeness = Literal["complete", "unknown", "invalid", "cycle", "limit"]
MAX_NODES = 2048
MAX_PARTS = 512
MAX_DEPTH = 16
MAX_SCAN_CHARS = 65536
MAX_FIELDS = 64
_BASE64 = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")
_MISSING = object()


class CountData(TypedDict):
    text_parts: int
    image_parts: int
    other_parts: int
    text_chars: int
    text_bytes: int
    image_encoded_chars: int
    image_bytes: int
    completeness: Completeness


@dataclass
class Counts:
    """불완전한 집계의 수치는 관측한 하한이다. 전체 크기로 해석하지 않는다."""

    text_parts: int = 0
    image_parts: int = 0
    other_parts: int = 0
    text_chars: int = 0
    text_bytes: int = 0
    image_encoded_chars: int = 0
    image_bytes: int = 0
    completeness: Completeness = "complete"


class _Counter:
    def __init__(self):
        self.counts = Counts()
        self.nodes = 0
        self.scan_left = MAX_SCAN_CHARS
        self.active = set()

    def issue(self, kind):
        priority = ("complete", "unknown", "invalid", "cycle", "limit")
        if priority.index(kind) > priority.index(self.counts.completeness):
            self.counts.completeness = kind

    def fields(self, value):
        # dict 하위 타입과 임의 key의 hash/equality도 실행하지 않는다.
        if type(value) is not dict:
            self.issue("invalid")
            return {}
        if len(value) > MAX_FIELDS:
            self.issue("limit")
        result = {}
        for key, item in islice(value.items(), MAX_FIELDS):
            if type(key) is str:
                result[key] = item
            else:
                self.issue("invalid")
        return result

    def text(self, value):
        self.counts.text_parts += 1
        if type(value) is not str:
            self.issue("invalid")
            return
        self.counts.text_chars += len(value)
        scan = min(len(value), self.scan_left)
        self.scan_left -= scan
        if scan != len(value):
            self.issue("limit")
        for index in range(scan):
            code = ord(value[index])
            if 0xD800 <= code <= 0xDFFF:
                self.issue("invalid")
            else:
                self.counts.text_bytes += 1 if code < 128 else 2 if code < 2048 else 3 if code < 65536 else 4

    def image(self, value, *, data_url=False):
        self.counts.image_parts += 1
        if type(value) is bytes:
            self.counts.image_bytes += len(value)
            return
        if type(value) is not str:
            self.issue("invalid")
            return
        offset = 0
        if data_url:
            if not value.startswith("data:image/"):
                self.issue("unknown")  # 원격 이미지와 file id의 실제 바이트는 읽지 않는다.
                return
            comma = value.find(",", 0, 128)
            if comma < 0 or value[max(0, comma - 7):comma] != ";base64":
                self.issue("invalid")
                return
            offset = comma + 1
        length = len(value) - offset
        self.counts.image_encoded_chars += length
        if length > self.scan_left:
            self.issue("limit")
            return
        self.scan_left -= length
        padding = 2 if value.endswith("==") else 1 if value.endswith("=") else 0
        if length % 4 or (padding and length < 4):
            self.issue("invalid")
            return
        for index in range(offset, len(value) - padding):
            if value[index] not in _BASE64:
                self.issue("invalid")
                return
        # 마지막 패딩 비트도 검사해 크기를 확정할 수 없는 base64를 거절한다.
        alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        if padding and alphabet.index(value[-padding - 1]) % (16 if padding == 2 else 4):
            self.issue("invalid")
            return
        self.counts.image_bytes += length // 4 * 3 - padding

    def walk(self, value, depth=0):
        if self.nodes >= MAX_NODES or depth > MAX_DEPTH:
            self.issue("limit")
            return
        if self.counts.text_parts + self.counts.image_parts + self.counts.other_parts >= MAX_PARTS:
            self.issue("limit")
            return
        self.nodes += 1
        if type(value) is str:
            self.text(value)
            return
        if type(value) not in (dict, list, tuple):
            self.issue("invalid")
            return
        identity = id(value)
        if identity in self.active:
            self.issue("cycle")
            return
        self.active.add(identity)
        try:
            if type(value) in (list, tuple):
                for item in value:
                    if self.nodes >= MAX_NODES or self.counts.text_parts + self.counts.image_parts + self.counts.other_parts >= MAX_PARTS:
                        self.issue("limit")
                        break
                    self.walk(item, depth + 1)
            else:
                self.part(self.fields(value), depth)
        finally:
            self.active.remove(identity)

    def part(self, fields, depth):
        # 공통 metadata와 이번 분기가 실제 읽는 content를 구분한다.
        known = {"role", "id", "name", "tool_call_id", "call_id", "status", "cache_control"}
        kind = fields.get("type", _MISSING)
        if type(kind) is str and kind in ("text", "input_text", "output_text"):
            known.update(("type", "text"))
            self.text(fields.get("text"))
        elif type(kind) is str and kind in ("image_url", "input_image"):
            known.update(("type", "image_url", "detail"))
            image = fields.get("image_url")
            if type(image) is dict:
                image_fields = self.fields(image)
                self.check_fields(image_fields, {"url", "detail"})
                image = image_fields.get("url")
            self.image(image, data_url=True)
        elif type(kind) is str and kind == "image":
            known.update(("type", "source"))
            source = self.fields(fields.get("source"))
            source_kind = source.get("type")
            if type(source_kind) is str and source_kind == "base64":
                self.check_fields(source, {"type", "media_type", "data"})
                self.image(source.get("data"))
            elif type(source_kind) is str and source_kind == "url":
                self.counts.image_parts += 1
                self.issue("unknown")
            else:
                self.counts.image_parts += 1
                self.issue("invalid")
        elif "inlineData" in fields or "inline_data" in fields:
            key = "inlineData" if "inlineData" in fields else "inline_data"
            known.add(key)
            inline = self.fields(fields[key])
            mime_key = "mimeType" if "mimeType" in inline else "mime_type"
            self.check_fields(inline, {mime_key, "data"})
            mime = inline.get(mime_key)
            if type(mime) is str and mime.startswith("image/"):
                self.image(inline.get("data"))
            else:
                self.other()
        elif "fileData" in fields or "file_data" in fields:
            key = "fileData" if "fileData" in fields else "file_data"
            known.add(key)
            file = self.fields(fields[key])
            mime = file.get("mimeType", file.get("mime_type"))
            if type(mime) is str and mime.startswith("image/"):
                self.counts.image_parts += 1
                self.issue("unknown")
            else:
                self.other()
        elif "text" in fields and kind is _MISSING:
            known.add("text")
            self.text(fields["text"])
        elif (kind is _MISSING or type(kind) is str and kind in ("message", "tool_result", "function_call_output")) and (
                "content" in fields or "parts" in fields or "output" in fields):
            known.add("type")
            for key in ("content", "parts", "output"):
                if key in fields:
                    known.add(key)
                    self.walk(fields[key], depth + 1)
        elif "functionResponse" in fields or "function_response" in fields:
            key = "functionResponse" if "functionResponse" in fields else "function_response"
            known.add(key)
            response = self.fields(fields[key])
            if "parts" in response:
                self.walk(response["parts"], depth + 1)
            self.issue("unknown")  # 임의 response 객체는 탐색하지 않는다.
        else:
            self.other()
        self.check_fields(fields, known)

    def check_fields(self, fields, known):
        if fields.keys() - known:
            self.issue("unknown")

    def other(self):
        self.counts.other_parts += 1
        self.issue("unknown")


def count_request(api_kwargs) -> CountData:
    """Chat/Responses/Anthropic/Gemini의 알려진 content만 집계하고 입력을 보존한다."""
    counter = _Counter()
    root = counter.fields(api_kwargs)
    found = False
    for key in ("messages", "input", "contents", "system", "instructions", "systemInstruction", "system_instruction"):
        if key in root:
            found = True
            counter.walk(root[key])
    if not found:
        counter.issue("unknown")
    counts = counter.counts
    return {
        "text_parts": counts.text_parts, "image_parts": counts.image_parts, "other_parts": counts.other_parts,
        "text_chars": counts.text_chars, "text_bytes": counts.text_bytes,
        "image_encoded_chars": counts.image_encoded_chars, "image_bytes": counts.image_bytes,
        "completeness": counts.completeness,
    }
