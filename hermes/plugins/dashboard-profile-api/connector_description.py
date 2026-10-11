"""도구 설명을 정책과 분리한 평문 표시 메타데이터로 읽는다."""

import re

# 세 언어가 같은 공백을 잘라낸다. 금지 제어문자는 자르기 전에 검사한다.
_TRIM = "\t\n\r \u00a0\u1680" + "".join(chr(n) for n in range(0x2000, 0x200b)) + "\u2028\u2029\u202f\u205f\u3000\ufeff"
_FORBIDDEN = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069\ud800-\udfff]")


def _tool_description(value):
    """읽을 수 없는 설명만 None이다. 원문 manifest와 보호 SHA는 바꾸지 않는다."""
    if not isinstance(value, str) or _FORBIDDEN.search(value):
        return None
    value = value.strip(_TRIM)
    if not 1 <= len(value) <= 2000 or len(value.encode("utf-8")) > 8192:
        return None
    return value


def _tool_descriptions(declared):
    """정책 검증이 끝난 schema 2의 도구 설명만 별도로 정규화한다."""
    if declared["schema"] != 2:
        return {}
    return {name: _tool_description(tool.get("description")) for name, tool in declared["tools"].items()}
