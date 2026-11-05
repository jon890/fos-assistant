"""플러그인 MCP 서버를 띄우는 실행기다(docs/features/connector/design.md 의 「실행기(`connector_launcher.py`)」).

Hermes 가 바인딩의 서버 정의대로 `python -I connector_launcher.py <보관 파일 id> <플러그인 id> <칸 key>=<env>... -- <command> <args>...`
로 연다. 보관 파일을 풀어 칸 값을 자식 env 에만 넣고 `execve` 로 실제 서버가 된다. 칸 값은 profile `.env` 와 서버 정의에 없다.

plugin 패키지의 `__init__.py` 를 실행하지 않는다. 이 디렉터리를 하위 모듈 검색 경로로 둔 빈 패키지를 만들어
`connector_vault_format.py` 와 `connector_vault_key.py` 만 읽는다. 표준 라이브러리와 `cryptography` 만 쓴다.
실패하면 값과 경로 없이 이유 단어 하나를 stderr 에 쓰고 `EXIT_CONFIG` 로 끝난다. 그 MCP 서버만 뜨지 않는다.
"""

from __future__ import annotations

import importlib
import os
import pathlib
import re
import sys
import types

# 실행기가 받는 env 의 앞머리다. 자식에게 넘기기 전에 이 앞머리의 이름을 모두 지운다. 플러그인이 key 위치를 받지 않게 한다.
LAUNCHER_ENV_PREFIX = "FOS_ASSISTANT_CONNECTOR_VAULT_"
VAULT_DIR_ENV = LAUNCHER_ENV_PREFIX + "DIR"
# `connector_vault_key.VAULT_KEY_FILE_ENV` 와 같은 이름이다. 실행기는 그 모듈을 읽기 전에 이 이름이 필요하지 않다.
KEY_FILE_ENV = LAUNCHER_ENV_PREFIX + "KEY_FILE"
ARGS_SEPARATOR = "--"
# sysexits 의 설정 오류 코드다.
EXIT_CONFIG = 78
# 실행기가 쓰는 빈 패키지 이름이다. Hermes 가 읽는 plugin 패키지 이름과 겹치지 않는다.
PACKAGE = "_fos_connector_launcher"


class LaunchError(Exception):
    """실행기가 서버를 띄우지 않는 까닭이다. 메시지는 값과 경로가 없는 이유 단어 하나다."""


def _modules():
    """이 디렉터리의 두 모듈을 plugin 패키지 없이 읽는다."""
    for name in [name for name in sys.modules if name == PACKAGE or name.startswith(PACKAGE + ".")]:
        del sys.modules[name]
    package = types.ModuleType(PACKAGE)
    package.__path__ = [str(pathlib.Path(__file__).resolve().parent)]
    sys.modules[PACKAGE] = package
    vault_format = importlib.import_module(PACKAGE + ".connector_vault_format")
    vault_key = importlib.import_module(PACKAGE + ".connector_vault_key")
    return vault_format, vault_key


def _parse_args(argv: list, schema) -> tuple:
    """`(보관 파일 id, 플러그인 id, {칸 key: env 이름}, [command, args...])` 다. 틀리면 `LaunchError` 다."""
    if ARGS_SEPARATOR not in argv:
        raise LaunchError("arguments")
    cut = argv.index(ARGS_SEPARATOR)
    head, command = argv[:cut], argv[cut + 1:]
    if len(head) < 2 or not command or not command[0]:
        raise LaunchError("arguments")
    vault, connector, pairs = head[0], head[1], head[2:]
    fields = {}
    for pair in pairs:
        key, separator, env_name = pair.partition("=")
        if (not separator or not schema.FIELD_KEY_RE.match(key) or not schema.ENV_NAME_RE.match(env_name)
                or key in fields or env_name in fields.values() or env_name.startswith(LAUNCHER_ENV_PREFIX)):
            raise LaunchError("arguments")
        fields[key] = env_name
    return vault, connector, fields, command


def _read_values(vault: str, connector: str, environ: dict, vault_format, vault_key) -> dict:
    """보관 파일을 읽어 칸 값을 돌려준다. 없거나 풀지 못하거나 다른 플러그인의 파일이면 `LaunchError` 다."""
    if not vault_format.VAULT_ID_RE.match(vault):
        raise LaunchError("arguments")
    directory = environ.get(VAULT_DIR_ENV, "")
    if not directory or not os.path.isabs(directory):
        raise LaunchError("vault_missing")
    path = pathlib.Path(directory) / ("%s.json" % vault)
    if pathlib.Path(directory).is_symlink() or path.is_symlink():
        raise LaunchError("vault_unreadable")
    try:
        raw = path.read_bytes()
    except FileNotFoundError:
        raise LaunchError("vault_missing") from None
    except OSError:
        raise LaunchError("vault_unreadable") from None
    try:
        stored = vault_format._parse_vault(raw)
        if stored["connector"] != connector:
            raise LaunchError("connector_mismatch")
        if stored["v"] == 1:
            return stored["values"]
        keys = {}
        key_file = environ.get(KEY_FILE_ENV, "")
        if key_file:
            try:
                keys = vault_key._parse_key_file(pathlib.Path(key_file).read_text(encoding="utf-8"))
            except (OSError, UnicodeError, ValueError):
                keys = {}
        # 읽기는 활성 key id 를 쓰지 않는다. 파일의 `key_id` 의 key 로 푼다.
        state = vault_key.VaultKeys(vault_key.KEYS_INVALID, False, None, keys)
        return vault_format._unseal_vault(vault, stored, state)["values"]
    except LaunchError:
        raise
    except Exception:
        # 예외 본문은 남기지 않는다. 풀기 실패와 모양 오류 모두 같은 단어다.
        raise LaunchError("vault_unreadable") from None


def _child_env(environ: dict, fields: dict, values: dict, command: str) -> dict:
    """자식 env 다. 실행기 env 를 지우고 칸 값을 넣는다. 보관 파일에 없는 칸은 빈 값이다."""
    env = {name: value for name, value in environ.items() if not name.startswith(LAUNCHER_ENV_PREFIX)}
    for key, env_name in fields.items():
        value = values.get(key, "")
        env[env_name] = value if isinstance(value, str) else ""
    # Hermes 가 정의의 command 에 하는 것처럼 실제 command 의 디렉터리를 PATH 앞에 둔다.
    command_dir = os.path.dirname(command)
    if command_dir:
        rest = [item for item in env.get("PATH", "").split(os.pathsep) if item and item != command_dir]
        env["PATH"] = os.pathsep.join([command_dir, *rest])
    return env


def launch(argv: list, environ: dict, execve=os.execve) -> int:
    """인자와 env 로 실제 서버를 띄운다. `execve` 가 돌아오지 않으면 그 서버가 이 프로세스가 된다."""
    try:
        vault_format, vault_key = _modules()
        schema = sys.modules[PACKAGE + ".connector_schema"]
        vault, connector, fields, command = _parse_args(argv, schema)
        if not schema.CONNECTOR_ID_RE.match(connector):
            raise LaunchError("arguments")
        values = _read_values(vault, connector, environ, vault_format, vault_key)
        env = _child_env(environ, fields, values, command[0])
    except LaunchError as error:
        sys.stderr.write("connector-launcher: %s\n" % error)
        return EXIT_CONFIG
    except Exception as error:
        sys.stderr.write("connector-launcher: %s\n" % re.sub(r"[^A-Za-z_]", "", type(error).__name__))
        return EXIT_CONFIG
    try:
        execve(command[0], command, env)
    except (OSError, ValueError):
        sys.stderr.write("connector-launcher: exec_failed\n")
        return EXIT_CONFIG
    return 0


if __name__ == "__main__":
    sys.exit(launch(sys.argv[1:], dict(os.environ)))
