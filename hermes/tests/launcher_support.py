"""실행기 정의의 바인딩을 직접 만들어 대시보드 직접 실행 경로를 검사하는 준비다(docs/features/connector/design.md 의 「PR2: 실행기와 사본 제거(REQ6, REQ7, REQ8)」).

`ConnectorGateCase` 를 쓰는 검사는 소유 기록을 손으로 쓴다. 이 도우미는 그 항목을 실행기 정의로 바꾸고,
값을 보관 파일에만 둔다. profile `.env` 에는 칸 값이 없다.
"""

import json
import sys
import types
from unittest import mock


def use_vault_root(case, root):
    """보관 파일 디렉터리를 `root` 아래로 둔다. `root` 는 만든다. 검사가 끝나면 되돌린다."""
    root.mkdir(parents=True, exist_ok=True)
    constants = types.ModuleType("hermes_constants")
    constants.get_default_hermes_root = lambda: str(root)
    patch = mock.patch.dict(sys.modules, {"hermes_constants": constants})
    patch.start()
    case.addCleanup(patch.stop)


def launcher_entry(case, profile, connector, values, vault="c1", entry=None):
    """그 profile 의 소유 기록 항목을 실행기 정의로 바꾸고 `values` 를 보관 파일에 쓴다. 바뀐 항목을 돌려준다.

    `entry` 를 주면 그 항목에서, 없으면 그 profile 의 소유 기록에서 시작한다. `config.yaml` 의 같은 서버도 바꾼다.
    """
    import yaml

    plugin = case.plugin
    plugin._write_vault(vault, connector, values)
    state_path = profile / plugin.CONNECTOR_STATE
    state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}
    current = dict(entry if entry is not None else state[connector])
    manifest = plugin._connector_manifest(connector)
    launch = sys.modules[plugin.__name__ + ".connector_launch"]
    current["server"] = launch._launcher_server(manifest, current["server"], vault, launch._vault_revision(vault))
    current["vault"] = vault
    state[connector] = current
    state_path.write_text(json.dumps(state), encoding="utf-8")
    config_path = profile / "config.yaml"
    config = yaml.safe_load(config_path.read_text(encoding="utf-8")) if config_path.exists() else {}
    config = config or {}
    config.setdefault("mcp_servers", {})[current["mcp_server"]] = current["server"]
    config_path.write_text(yaml.safe_dump(config), encoding="utf-8")
    return current
