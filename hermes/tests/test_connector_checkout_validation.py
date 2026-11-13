"""주문서 준비(CHECKOUT) 사례 표를 대시보드의 실제 준비 결과 검증에 넣는다. Control Plane 은 같은 표를 `ConnectorCheckoutSnapshotTest` 로 소비한다."""

import importlib
import json
import pathlib

from test_connector_manifest import ConnectorGateCase

REPO = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = {"fields": [], "execution_guard": {
    "operations": {"fill_checkout": "CREATE"}, "kinds": {"fill_checkout": "CHECKOUT"}, "scope_fields": []}}


class ConnectorCheckoutValidationTest(ConnectorGateCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.validation = importlib.import_module(cls.plugin.__name__ + ".connector_guard_validation")
        cls.cases = json.loads((REPO / "test/resources/checkout-snapshot-v1.json").read_text())["cases"]

    def consume(self, case, manifest=MANIFEST):
        payload = {"v": 1, "executionArgs": case["executionArgs"], "summary": case["summary"]}
        return self.validation._prepare_result(payload, case["modelArgs"], manifest, "fill_checkout", {})

    def test_shared_cases(self):
        for case in self.cases:
            with self.subTest(case=case["name"]):
                if case["valid"]:
                    self.consume(case)
                else:
                    with self.assertRaises(ValueError):
                        self.consume(case)
        self.assertTrue(any(case["valid"] for case in self.cases) and not all(case["valid"] for case in self.cases))

    def test_checkout_summary_needs_checkout_kind_and_empty_scope(self):
        base = self.cases[0]
        order = {"fields": [], "execution_guard": dict(MANIFEST["execution_guard"], kinds={"fill_checkout": "ORDER"})}
        with self.assertRaises(ValueError):
            self.consume(base, order)
        scoped = {"fields": [{"key": "account", "env": "TEST_ACCOUNT"}], "execution_guard": dict(
            MANIFEST["execution_guard"], scope_fields=[{"arg": "account_seq", "field": "account"}])}
        with self.assertRaises(ValueError):
            self.consume(base, scoped)
