"""보관 파일 글의 모양과 AAD, 풀기다(ADR-20261103 connector-vault-key).

대시보드 plugin 과 실행기(`connector_launcher.py`)가 함께 쓴다. 실행기는 plugin 패키지의 `__init__.py` 를 실행하지 않고
이 모듈만 읽으므로, 여기서는 표준 라이브러리와 `connector_schema.py`, `connector_vault_key.py` 만 쓴다.
"""

from __future__ import annotations

import json
import re

from .connector_schema import CONNECTOR_ID_RE

from .connector_vault_key import (
    KEY_ID_RE,
    _open,
)


# 보관 파일 이름이다. Control Plane 의 연결 id 앞에 `c` 를 붙인다. 경로 조각으로 쓰기 전에 본다.
VAULT_ID_RE = re.compile(r"^c[1-9][0-9]{0,18}\Z")


def _vault_aad(vault: str, connector: str) -> bytes:
    """암호문을 그 연결 이름과 플러그인에 묶는 글이다. 파일을 다른 연결로 옮기거나 `connector` 를 바꾸면 풀리지 않는다."""
    return ("connector-vault:%s:%s" % (vault, connector)).encode("utf-8")


def _parse_vault(raw: bytes) -> dict:
    """보관 파일 글의 모양을 엄격히 본다. 풀지 않는다. 틀리면 예외다. 값은 예외 메시지에 싣지 않는다.

    v1 은 `{"v": 1, "connector", "values"}` 평문이고, v2 는 `{"v": 2, "connector", "key_id", "sealed"}` 다
    (ADR-20261103 connector-vault-key).
    """
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeError, ValueError):
        raise ValueError("보관 파일의 모양이 올바르지 않다") from None
    if (not isinstance(value, dict) or not isinstance(value.get("connector"), str)
            or not CONNECTOR_ID_RE.match(value["connector"])):
        raise ValueError("보관 파일의 모양이 올바르지 않다")
    version = value.get("v") if type(value.get("v")) is int else None
    if version == 1 and set(value) == {"v", "connector", "values"}:
        values = value["values"]
        if isinstance(values, dict) and all(isinstance(key, str) and isinstance(item, str)
                                            for key, item in values.items()):
            return value
    if (version == 2 and set(value) == {"v", "connector", "key_id", "sealed"}
            and isinstance(value["key_id"], str) and KEY_ID_RE.match(value["key_id"])
            and isinstance(value["sealed"], str)):
        return value
    raise ValueError("보관 파일의 모양이 올바르지 않다")


def _unseal_vault(vault: str, stored: dict, keys) -> dict:
    """v2 보관 파일을 풀어 v1 과 같은 모양으로 돌려준다. 그 key 가 없거나 풀지 못하면 `ValueError` 다."""
    # 활성 id 가 틀려도 파일의 key 로 푼다. 쓰기만 활성 key 를 요구한다.
    key = keys.keys.get(stored["key_id"])
    if key is None:
        raise ValueError("보관 파일을 풀 key 가 없다")
    try:
        values = json.loads(_open(key, stored["sealed"], _vault_aad(vault, stored["connector"])).decode("utf-8"))
    except (UnicodeError, ValueError):
        raise ValueError("보관 파일을 풀지 못했다") from None
    if not isinstance(values, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in values.items()):
        raise ValueError("보관 파일의 모양이 올바르지 않다")
    return {"v": 2, "connector": stored["connector"], "values": values}
