"""테스트 생성 원본을 실제 Python loader와 준비 결과 소비자로 읽는다."""

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
    # _connector_manifest가 _load_connector와 _guard_manifest를 순서대로 호출한다.
    with mock.patch.object(loader, "_connector_roots", return_value={"tossinvest": entry}):
        manifest = loader._connector_manifest("tossinvest")
    assert manifest is not None
    prepared = json.loads((root / "prepared.json").read_text())
    consumer = importlib.import_module(plugin.__name__ + ".connector_guard_validation")
    consumer._prepare_result(
        prepared["prepared"], prepared["model"], manifest, prepared["tool"],
        {"TOSSINVEST_ACCOUNT_SEQ": prepared["prepared"]["executionArgs"]["account_seq"]},
    )
    print(manifest["execution_guard_manifest_sha256"])


if __name__ == "__main__":
    main()
