"""도구 설명의 표시 계약과 실제 카탈로그 합성을 검사한다."""

import importlib
import json
import pathlib

from test_connector_manifest import ConnectorGateCase, DEMO

FIXTURE = pathlib.Path(__file__).resolve().parents[2] / "test/fixtures/connector-tool-descriptions.json"


class ConnectorDescriptionTest(ConnectorGateCase):
    def test_shared_boundary_vectors_and_catalog_preserve_policy(self):
        original = (self.connector_root / "connector.json").read_text()
        baseline = self.plugin._connector_manifest(DEMO)
        for case in json.loads(FIXTURE.read_text()):
            with self.subTest(case=case["name"]):
                declared = json.loads(original)
                declared["schema"] = 2
                declared["tools"] = {"list_scopes": {"risk": "READ", "description": case["value"]}}
                (self.connector_root / "connector.json").write_text(json.dumps(declared))
                manifest = self.plugin._connector_manifest(DEMO)
                self.assertIsNotNone(manifest)
                self.assertEqual(manifest["tool_descriptions"]["list_scopes"], case["expected"])
                self.assertNotIn("description", manifest["tools"]["list_scopes"])
                status, response = self.request("/api/connectors/catalog", "GET")
                self.assertEqual(status, 200)
                self.assertEqual(response[0]["tools"]["list_scopes"].get("description"), case["expected"])
                self.assertEqual(manifest["tools"]["list_scopes"]["risk"], "READ")
                self.assertEqual(manifest["tools"]["list_scopes"]["approval"], "none")
        (self.connector_root / "connector.json").write_text(original)
        self.assertEqual(self.plugin._connector_manifest(DEMO)["tools"], baseline["tools"])

    def test_legacy_missing_and_empty_catalog(self):
        module = importlib.import_module(self.plugin.__name__ + ".connector_description")
        self.assertEqual(module._tool_descriptions({"schema": 1}), {})
        self.assertEqual(module._tool_descriptions({"schema": 2, "tools": {"a": {"risk": "READ"}}}), {"a": None})
