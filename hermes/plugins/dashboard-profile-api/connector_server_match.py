"""소유 기록의 서버 정의가 지금 manifest 의 실행 정의와 같은지 본다(`connector_manifest.py` 에서 옮겼다)."""

from __future__ import annotations

from .connector_launch import _is_launcher, _launcher_current, _launcher_inner
from .connector_remote import _is_remote, _remote_server_matches
from .connector_schema import OWNER_ATTACHMENTS_VALUE_RE, OWNER_BROWSER_VALUE_RE, OWNER_OUTPUT_VALUE_RE


def _server_matches(manifest: dict, server: dict) -> bool:
    """소유 기록의 서버 정의가 지금 manifest 의 실행 정의와 같은지 본다.

    칸의 env 는 자기 이름의 참조이고, 선택 칸은 명시한 빈 값도 된다.
    운영자 env 는 옛 기록의 `${이름}` 참조와 지금 정의의 직접 값을 같다고 본다.
    옛 판이 남긴 기록을 그대로 인정해야 이미 설치한 연결이 끊기지 않는다(ADR-041).
    `tools` 는 견주지 않는다. 옛 기록에는 그 키가 없고, 다시 보낸 설치가 지금 manifest 의 값으로 덮어쓴다.
    주인의 첨부 디렉터리 env 는 설치마다 그 주인의 값이라 manifest 와 견주지 않는다. 빈 값이거나
    `<루트>/users/<64자리 16진수>` 모양인지만 본다. 참조(`${...}`)를 받으면 profile `.env` 가 경로를 정하게 된다(ADR-20261007 connector-owner-attachments).
    플러그인 출력 디렉터리 env 도 같다. `<루트>/users/<64자리 16진수>/<profile>/<id>` 모양인지만 본다(ADR-20261008 connector-output-files).
    브라우저 중계 주소 env 도 같다. 빈 값이거나 `<gateway-base-url>/<접근 표식>` 모양인지만 본다(ADR-20261008 browser-gateway-token).
    실행기 정의는 실행기 칸이 지금 값과 맞는지 본 뒤 옛 모양으로 펼쳐 같은 규칙으로 견준다(`connector_launch.py`).
    """
    expected = manifest["server"]
    if manifest["transport"] == "http" or _is_remote(server):
        # 원격 정의는 칸 값이 profile `.env` 를 거치는 헤더뿐이라 주소와 헤더, redirect 헤더 처리만 비교한다.
        return manifest["transport"] == "http" and _remote_server_matches(expected, server)
    if _is_launcher(server):
        if not _launcher_current(manifest, server):
            return False
        server = _launcher_inner(server)
    if (server["command"] != expected["command"] or server["args"] != expected["args"]
            or set(server["env"]) != set(expected["env"])):
        return False
    for name, value in server["env"].items():
        reference = "${%s}" % name
        if name == manifest["owner_attachments_env"]:
            if value != "" and not OWNER_ATTACHMENTS_VALUE_RE.match(value):
                return False
        elif name == manifest["owner_output_env"]:
            if value != "" and not OWNER_OUTPUT_VALUE_RE.fullmatch(value):
                return False
        elif name == manifest["owner_browser_env"]:
            if value != "" and not (isinstance(value, str) and OWNER_BROWSER_VALUE_RE.fullmatch(value)):
                return False
        elif name in manifest["operator_env"]:
            if value not in (reference, expected["env"][name]):
                return False
        elif name == manifest.get("oauth_env"):
            # 통로 주소 env 는 실행기 정의가 빈 값으로 둔다. 옛 정의와 펼친 정의는 참조도 된다(docs/features/connector-oauth/design.md).
            if value not in ("", reference):
                return False
        elif value != reference and not (value == "" and name in manifest["optional_env"]):
            return False
    return True
