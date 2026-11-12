"""조건주문 테스트 생성 원본을 실제 Python loader 와 준비 결과 소비자로 읽는다.

자체 manifest·SHA·검증기를 만들지 않는다. `_connector_manifest` 가 `_load_connector` 와 `_guard_manifest` 를,
`_prepare_result` 가 manifest `kinds` 로 조건 분기를 고른다.
"""

import importlib
import json
from pathlib import Path
import sys
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import test_connector_manifest as base


def main():
    root = Path(sys.argv[1]).resolve()
    base.ConnectorGateCase.setUpClass()
    plugin = base.ConnectorGateCase.plugin
    loader = importlib.import_module(plugin.__name__ + ".connector_manifest")
    entry = {"root": root, "command": sys.argv[2], "env": {}}
    with mock.patch.object(loader, "_connector_roots", return_value={"tossinvest": entry}):
        manifest = loader._connector_manifest("tossinvest")
    assert manifest is not None
    prepared = json.loads((root / "prepared.json").read_text())
    tool = prepared["tool"]
    # 선언이 없으면 일반 주문으로 읽어 조건 summary 를 거절한다. 테스트 원본만 이 선언을 갖는다.
    assert manifest["execution_guard"]["kinds"][tool] == "CONDITIONAL"
    consumer = importlib.import_module(plugin.__name__ + ".connector_guard_validation")
    consumer._prepare_result(
        prepared["prepared"], prepared["model"], manifest, tool,
        {"TOSSINVEST_ACCOUNT_SEQ": prepared["prepared"]["executionArgs"]["account_seq"]},
    )
    print(manifest["execution_guard_manifest_sha256"])


if __name__ == "__main__":
    main()
