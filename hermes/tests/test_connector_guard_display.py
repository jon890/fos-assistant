"""prepare 표시 필드의 실제 producer UTF-8·UTF-16 길이·ISO control 계약을 대조한다."""

import copy
import json

from test_connector_guard_transport import ConnectorGuardCase, REPO, PREPARE


class ConnectorGuardDisplayTest(ConnectorGuardCase):
    def display_payload(self, field, value):
        case = next(item for item in self.cases["cases"] if item["name"] == "cancel-baseline-zero-filled")
        manifest = copy.deepcopy(self.manifest)
        manifest["execution_guard"]["operations"] = {case["tool"]: case["operation"]}
        payload = {"v": 1, "executionArgs": json.loads(case["executionArgsJson"]), "summary": json.loads(case["summaryJson"])}
        payload["summary"]["original"][field] = value
        if field == "market":
            payload["summary"][field] = value
            payload["executionArgs"][field] = value
        else:
            payload["executionArgs"]["expected_order"][field] = value
        return payload, json.loads(case["modelArgsJson"]), manifest, case["tool"]

    def validate_display(self, field, value):
        payload, original, manifest, tool = self.display_payload(field, value)
        return self.validation._prepare_result(payload, original, manifest, tool, {"DEMO_SCOPE": "000007"})

    def test_semantic_display_vectors(self):
        vector = json.loads((REPO / "test/resources/financial-display-text-v1.json").read_text())
        for field in ("market", "status"):
            for case in vector["cases"]:
                with self.subTest(field=field, case=case["name"]):
                    if case["valid"]:
                        self.validate_display(field, case["value"])
                    else:
                        with self.assertRaises(ValueError):
                            self.validate_display(field, case["value"])

    def test_every_iso_control_codepoint_and_utf16_boundaries(self):
        vector = json.loads((REPO / "test/resources/financial-display-text-v1.json").read_text())
        for field, maximum in (("market", 16), ("status", 32)):
            for low, high in vector["isoControlRanges"]:
                for codepoint in range(low, high + 1):
                    with self.subTest(field=field, codepoint=codepoint):
                        with self.assertRaises(ValueError):
                            self.validate_display(field, "X" + chr(codepoint))
            # Java String.length는 UTF-16 단위다. 표시 칸은 byte 상한으로 바꾸지 않는다.
            self.validate_display(field, "한" * maximum)
            self.validate_display(field, "😀" * (maximum // 2))
            for value in ("X" * (maximum + 1), "😀" * (maximum // 2 + 1)):
                with self.subTest(field=field, oversized=True):
                    with self.assertRaises(ValueError):
                        self.validate_display(field, value)

    def test_actual_prepare_rejects_control_market_without_claim_or_trade(self):
        case = copy.deepcopy(self.case)
        payload = {"v": 1, "executionArgs": json.loads(case["executionArgsJson"]), "summary": json.loads(case["summaryJson"])}
        for value in ("X\n", "X\0", "X\u0080"):
            payload["executionArgs"]["market"] = payload["summary"]["market"] = value
            case["executionArgsJson"], case["summaryJson"] = json.dumps(payload["executionArgs"]), json.dumps(payload["summary"])
            (self.connector_root / "prepare-case.json").write_text(json.dumps(case))
            status, answer = self.request(PREPARE, "POST", self.body())
            self.assertEqual((status, answer), (200, {"ok": False, "error": "unavailable"}))
        self.assertEqual(self.claims + self.trades, [])


if __name__ == "__main__":
    import unittest
    unittest.main()
