"""실제 Python 카탈로그 producer의 합성 계약 자료를 만든다. 외부 도구를 실행하지 않는다."""

import importlib
import json
import pathlib
import sys
import types
from unittest import mock

REPO = pathlib.Path(__file__).resolve().parents[3]
PACKAGE = "argument_catalog_fixture"
package = types.ModuleType(PACKAGE)
package.__path__ = [str(REPO / "hermes/plugins/dashboard-profile-api")]
sys.modules[PACKAGE] = package
manifest = importlib.import_module(PACKAGE + ".connector_manifest")
catalog = {}
for name in ("gmail", "naver-blog"):
    loaded = manifest._load_connector(name, {
        "root": REPO / "hermes/connectors" / name, "command": sys.executable, "env": {}})
    declared = json.loads((REPO / "hermes/connectors" / name / "connector.json").read_text())
    catalog[name] = manifest._guard_manifest(loaded, declared)
with mock.patch.object(manifest, "_connector_catalog", return_value=catalog):
    produced = json.loads(manifest._connector_catalog_response().body)
serialized = json.dumps(produced, ensure_ascii=False, indent=2) + "\n"
target = REPO / "test/resources/connector-argument-catalog.json"
if "--check" in sys.argv:
    if target.read_text() != serialized:
        raise SystemExit("카탈로그 계약 자료가 실제 Python producer와 다르다")
else:
    target.write_text(serialized)
