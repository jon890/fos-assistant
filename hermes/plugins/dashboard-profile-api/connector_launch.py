"""로컬(stdio) 플러그인 서버를 실행기로 띄우는 서버 정의를 만들고 알아본다.

정의의 모양과 까닭은 `docs/features/connector/design.md` 의 「실행기 정의(DECISION12)」 가 갖는다. 칸 값은 정의와 profile `.env` 에 두지 않고,
실행기(`connector_launcher.py`)가 띄울 때 보관 파일에서 풀어 자식 env 에만 넣는다.
경로 값은 모두 설치할 때 대시보드 프로세스에서 읽는다. 코드에 경로 상수나 기본값을 두지 않는다.
"""

from __future__ import annotations

import hashlib
import os
import pathlib
import re
import sys

from .common import _env_value

from .connector_vault_format import VAULT_ID_RE

from .connector_vault_key import VAULT_KEY_FILE_ENV


# 실행기 스크립트다. 이 plugin 디렉터리 안에 있다.
LAUNCHER_SCRIPT = pathlib.Path(__file__).resolve().parent / "connector_launcher.py"
# python 을 격리 모드로 연다. `PYTHON` env, 사용자 site, 스크립트 디렉터리를 import 경로에 넣지 않는다.
LAUNCHER_PYTHON_FLAG = "-I"
LAUNCHER_ARGS_SEPARATOR = "--"
# 정의의 env 에 두는 실행기 칸이다. 실행기는 자식에게 넘기기 전에 이 앞머리의 이름을 모두 지운다.
LAUNCHER_ENV_PREFIX = "FOS_ASSISTANT_CONNECTOR_VAULT_"
LAUNCHER_VAULT_DIR_ENV = LAUNCHER_ENV_PREFIX + "DIR"
# 보관 파일의 리비전이다. 토큰을 바꾸면 정의가 달라져 다시 설치가 재시작 대기를 답한다(REQ8).
LAUNCHER_REVISION_ENV = LAUNCHER_ENV_PREFIX + "REVISION"
LAUNCHER_ENV = frozenset({LAUNCHER_VAULT_DIR_ENV, VAULT_KEY_FILE_ENV, LAUNCHER_REVISION_ENV})
REVISION_RE = re.compile(r"^[0-9a-f]{64}\Z")


def _launcher_paths() -> dict:
    """지금 대시보드 프로세스의 실행기 경로 값이다. 정의를 만들 때와 견줄 때 함께 쓴다. python 경로를 모르면 `ValueError` 다."""
    from .connector_vault import _vault_dir

    if not sys.executable or not os.path.isabs(sys.executable):
        raise ValueError("실행기를 열 python 경로를 모른다")
    return {"command": sys.executable, "script": str(LAUNCHER_SCRIPT),
            LAUNCHER_VAULT_DIR_ENV: str(_vault_dir()),
            VAULT_KEY_FILE_ENV: os.environ.get(VAULT_KEY_FILE_ENV, "").strip()}


def _vault_revision(vault: str) -> str:
    """보관 파일의 리비전이다. 파일의 inode, 수정 시각(ns), 크기의 SHA-256 이다.

    파일 내용을 해시하지 않는다. v1 평문 파일에서도 값을 추측할 단서가 정의와 백업에 남지 않게 한다.
    같은 값으로 다시 쓰면 `_write_vault` 가 파일을 바꾸지 않으므로 리비전도 그대로다.
    """
    from .connector_vault import _vault_path

    stat = _vault_path(vault).stat()
    return hashlib.sha256(("%d:%d:%d" % (stat.st_ino, stat.st_mtime_ns, stat.st_size)).encode("ascii")).hexdigest()


def _without_revision(server):
    """리비전을 뺀 정의다. 리비전만 다른 두 정의를 같은 설치로 볼 때 쓴다. 실행기 정의가 아니면 그대로다."""
    if not _is_launcher(server):
        return server
    return {**server, "env": {name: value for name, value in server["env"].items() if name != LAUNCHER_REVISION_ENV}}


def _launcher_server(manifest: dict, server: dict, vault: str, revision: str) -> dict:
    """주인 값까지 채운 옛 모양의 정의(`server`)를 실행기 정의로 바꾼다. 칸 env 는 정의에서 뺀다."""
    if not VAULT_ID_RE.match(vault) or not REVISION_RE.match(revision):
        raise ValueError("실행기 정의의 보관 파일 이름이나 리비전이 올바르지 않다")
    paths = _launcher_paths()
    field_env = {field["env"] for field in manifest["fields"]}
    env = {name: value for name, value in server["env"].items() if name not in field_env}
    env.update({LAUNCHER_VAULT_DIR_ENV: paths[LAUNCHER_VAULT_DIR_ENV], VAULT_KEY_FILE_ENV: paths[VAULT_KEY_FILE_ENV],
                LAUNCHER_REVISION_ENV: revision})
    args = [LAUNCHER_PYTHON_FLAG, paths["script"], vault, manifest["id"],
            *("%s=%s" % (field["key"], field["env"]) for field in manifest["fields"]),
            LAUNCHER_ARGS_SEPARATOR, server["command"], *server["args"]]
    launched = {"command": paths["command"], "args": args, "env": env, "enabled": True}
    if "tools" in server:
        launched["tools"] = {"exclude": list(server["tools"]["exclude"])}
    return launched


def _launcher_parts(server) -> dict | None:
    """실행기 정의의 인자를 나눈다. 실행기 정의가 아니거나 모양이 틀리면 None 이다."""
    args = server.get("args") if isinstance(server, dict) else None
    if (not isinstance(args, list) or any(not isinstance(arg, str) for arg in args)
            or len(args) < 2 or args[0] != LAUNCHER_PYTHON_FLAG
            or pathlib.PurePath(args[1]).name != LAUNCHER_SCRIPT.name):
        return None
    if LAUNCHER_ARGS_SEPARATOR not in args:
        return None
    cut = args.index(LAUNCHER_ARGS_SEPARATOR)
    head, real = args[2:cut], args[cut + 1:]
    if len(head) < 2 or not real or not isinstance(server.get("env"), dict):
        return None
    fields = {}
    for pair in head[2:]:
        key, separator, env_name = pair.partition("=")
        if not separator or key in fields:
            return None
        fields[key] = env_name
    return {"script": args[1], "vault": head[0], "connector": head[1], "fields": fields,
            "command": real[0], "args": real[1:]}


def _is_launcher(server) -> bool:
    """실행기 정의인가. 원격 정의와 옛 정의는 거짓이다."""
    return _launcher_parts(server) is not None


def _launcher_inner(server):
    """실행기 정의를 옛 모양으로 펼친다. 칸 env 는 자기 이름의 `${이름}` 참조다. 실행기 정의가 아니면 그대로 돌려준다.

    펼친 모양을 지금 비교 규칙(`_server_matches`)과 떼기의 env 계산에 그대로 넣으려는 것이다.
    """
    parts = _launcher_parts(server)
    if parts is None:
        return server
    env = {name: value for name, value in server["env"].items() if name not in LAUNCHER_ENV}
    env.update({name: "${%s}" % name for name in parts["fields"].values()})
    inner = {"command": parts["command"], "args": parts["args"], "env": env, "enabled": server.get("enabled")}
    if "tools" in server:
        inner["tools"] = server["tools"]
    return inner


def _launcher_current(manifest: dict, server: dict) -> bool:
    """실행기 정의의 실행기 칸이 지금 대시보드의 경로와 manifest 의 칸 대응과 맞는가.

    경로가 바뀐 정의는 재시작 뒤 서버가 뜨지 않으므로 맞지 않음으로 드러낸다. 리비전은 모양만 본다.
    """
    parts = _launcher_parts(server)
    if parts is None:
        return False
    paths = _launcher_paths()
    env = server["env"]
    revision = env.get(LAUNCHER_REVISION_ENV)
    # 칸 env 는 실행기가 보관 파일에서 넣는다. 정의 env 에 같은 이름이 있으면 이 설치가 쓴 정의가 아니다.
    return (server.get("command") == paths["command"] and parts["script"] == paths["script"]
            and not set(parts["fields"].values()) & set(env)
            and bool(VAULT_ID_RE.match(parts["vault"])) and parts["connector"] == manifest["id"]
            and list(parts["fields"].items()) == [(field["key"], field["env"]) for field in manifest["fields"]]
            and env.get(LAUNCHER_VAULT_DIR_ENV) == paths[LAUNCHER_VAULT_DIR_ENV]
            and env.get(VAULT_KEY_FILE_ENV) == paths[VAULT_KEY_FILE_ENV]
            and isinstance(revision, str) and bool(REVISION_RE.match(revision)))


def _binding_env(profile_dir: pathlib.Path, manifest: dict, entry: dict) -> dict:
    """바인딩의 칸 env 값이다. `{env 이름: 값}` 이고 비운 칸은 빈 문자열이다.

    실행기 정의면 소유 기록의 보관 파일에서, 옛 정의와 원격 정의면 그 profile 의 `.env` 에서 읽는다.
    값의 자리가 정의의 종류를 따라가므로, 이관하지 못해 남긴 옛 바인딩도 지금처럼 돈다.
    보관 파일이 없거나 풀지 못하거나 다른 플러그인의 것이면 `ValueError` 다. 메시지에 값과 경로가 없다.
    """
    fields = manifest["fields"]
    if _is_launcher(entry.get("server")):
        from .connector_vault import _read_vault

        try:
            stored = _read_vault(entry["vault"])
        except Exception:
            raise ValueError("바인딩의 보관 파일을 읽지 못했다") from None
        if stored is None or stored["connector"] != manifest["id"]:
            raise ValueError("바인딩의 보관 파일이 없다")
        return {field["env"]: stored["values"].get(field["key"], "") for field in fields}
    env_path = profile_dir / ".env"
    text = env_path.read_text(encoding="utf-8") if env_path.is_file() else ""
    return {field["env"]: _env_value(text, field["env"]) for field in fields}
