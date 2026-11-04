from __future__ import annotations

import base64
import binascii
import os
import pathlib
import re
import stat
from dataclasses import dataclass, field


# 보관 파일 key 를 읽는 운영 값이다(ADR-20261103 connector-vault-key). 값과 파일의 자리는 운영 설정이 정한다.
# Control Plane 의 KEK 와 모양은 같고 값은 다른 key 다. 기본값을 두지 않는다.
VAULT_KEY_FILE_ENV = "FOS_ASSISTANT_CONNECTOR_VAULT_KEY_FILE"
VAULT_ACTIVE_KEY_ENV = "FOS_ASSISTANT_CONNECTOR_VAULT_ACTIVE_KEY_ID"
# 「key 필수」 다. 켜면 key 를 쓸 수 없을 때 보관 파일을 평문으로 쓰지 않고 거절한다.
VAULT_KEY_REQUIRED_ENV = "FOS_ASSISTANT_CONNECTOR_VAULT_KEY_REQUIRED"
# 끈 것으로 보는 값이다. 그 밖의 값은 켠 것으로 본다. 오타가 평문 쓰기로 이어지지 않게 한다.
REQUIRED_OFF_VALUES = frozenset({"", "false"})

KEY_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,31}$")
KEY_BYTES = 32
IV_BYTES = 12
SEALED_VERSION = "v1"

KEYS_READY = "ready"
KEYS_ABSENT = "absent"
KEYS_INVALID = "invalid"


@dataclass(frozen=True)
class VaultKeys:
    """보관 파일 key 의 상태다. `keys` 는 id 에서 32바이트 key 로 가는 대응이다.

    파일을 읽었으면 활성 id 가 틀려도 `keys` 가 찬다. 읽기는 이것으로 풀고, 쓰기는 `ready` 일 때만 한다.
    """

    state: str
    required: bool
    active_id: str | None = None
    keys: dict = field(default_factory=dict)

    @property
    def ready(self) -> bool:
        return self.state == KEYS_READY


class VaultKeyUnavailable(Exception):
    """「key 필수」 인데 key 를 쓸 수 없어 보관 파일을 쓰지 않는다. 메시지에 값과 경로를 싣지 않는다."""


def _vault_key_required() -> bool:
    return os.environ.get(VAULT_KEY_REQUIRED_ENV, "").strip().lower() not in REQUIRED_OFF_VALUES


def _parse_key_file(text: str) -> dict:
    """key 파일 글을 읽는다. 한 줄에 `<id>:<base64 32바이트>` 이고 빈 줄과 `#` 줄은 건너뛴다.

    모양이 틀리면 `ValueError` 다. 메시지에 key 값을 싣지 않는다.
    """
    parsed = {}
    for number, line in enumerate(text.splitlines(), start=1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key_id, separator, encoded = line.partition(":")
        key_id = key_id.strip()
        if not separator or not KEY_ID_RE.match(key_id):
            raise ValueError("key 파일 %d번째 줄의 모양이 틀렸다" % number)
        try:
            value = base64.b64decode(encoded.strip(), validate=True)
        except (binascii.Error, ValueError):
            raise ValueError("key 파일 %d번째 줄의 값이 base64 가 아니다" % number) from None
        if len(value) != KEY_BYTES:
            raise ValueError("key 파일 %d번째 줄의 값이 32바이트가 아니다" % number)
        if key_id in parsed:
            raise ValueError("key 파일 %d번째 줄의 id 가 겹친다" % number)
        parsed[key_id] = value
    return parsed


def _vault_keys() -> VaultKeys:
    """환경 변수와 key 파일을 읽어 key 상태를 낸다. 부를 때마다 읽는다. 실패해도 예외를 내지 않는다."""
    required = _vault_key_required()
    file_name = os.environ.get(VAULT_KEY_FILE_ENV, "").strip()
    active_id = os.environ.get(VAULT_ACTIVE_KEY_ENV, "").strip()
    if not file_name and not active_id:
        return VaultKeys(KEYS_ABSENT, required)
    if not file_name or not active_id:
        return VaultKeys(KEYS_INVALID, required)
    try:
        keys = _parse_key_file(pathlib.Path(file_name).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError):
        return VaultKeys(KEYS_INVALID, required)
    if active_id not in keys:
        return VaultKeys(KEYS_INVALID, required, None, keys)
    return VaultKeys(KEYS_READY, required, active_id, keys)


def _vault_key_file_shared() -> bool:
    """key 파일을 그룹이나 다른 사용자가 읽거나 쓸 수 있는가. 읽지 못하면 거짓이다."""
    file_name = os.environ.get(VAULT_KEY_FILE_ENV, "").strip()
    if not file_name:
        return False
    try:
        mode = os.stat(file_name).st_mode
    except OSError:
        return False
    return bool(mode & (stat.S_IRGRP | stat.S_IWGRP | stat.S_IROTH | stat.S_IWOTH))


def _b64encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _b64decode(value: str) -> bytes:
    if not value or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("암호문 조각의 모양이 틀렸다")
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _seal(key: bytes, plain: bytes, aad: bytes) -> str:
    """AES-256-GCM 으로 한 번 암호화한다. 모양은 Control Plane 의 `AesGcm` 과 같은 `v1.<IV>.<암호문과 태그>` 다."""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    iv = os.urandom(IV_BYTES)
    return ".".join((SEALED_VERSION, _b64encode(iv), _b64encode(AESGCM(key).encrypt(iv, plain, aad))))


def _open(key: bytes, sealed: str, aad: bytes) -> bytes:
    """`_seal` 의 글을 푼다. 모양이 틀리거나 key, AAD, 태그가 맞지 않으면 `ValueError` 다."""
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    pieces = sealed.split(".") if isinstance(sealed, str) else []
    if len(pieces) != 3 or pieces[0] != SEALED_VERSION:
        raise ValueError("암호문의 모양이 틀렸다")
    iv = _b64decode(pieces[1])
    if len(iv) != IV_BYTES:
        raise ValueError("암호문의 모양이 틀렸다")
    try:
        return AESGCM(key).decrypt(iv, _b64decode(pieces[2]), aad)
    except InvalidTag:
        raise ValueError("보관 파일을 풀지 못했다") from None
