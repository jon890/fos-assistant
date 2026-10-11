"""실제 stdio 결과의 디코딩된 값·키에서 실행 권한을 차단한다."""

import hashlib
import json

from test_connector_guard_transport import ConnectorGuardCase, EXECUTE, REPO


class ConnectorGuardResultTest(ConnectorGuardCase):
    def test_actual_stdio_echo_is_unknown_without_retry_or_trade(self):
        # 원문과 기대 hash는 Node crypto로 독립 계산한 고정 vector다.
        vector = json.loads((REPO / "test/resources/financial-result-echo-v1.json").read_text())
        self.assertEqual(hashlib.sha256(vector["argsJson"].encode("utf-8")).hexdigest(), vector["argsSha256"])
        self.vector.update(vector)
        cases = (
            ("raw-exact", "raw", False, False, False, False),
            ("raw-substring", "raw", True, False, False, False),
            ("raw-key", "raw", True, True, True, False),
            ("raw-unicode-wire", "raw", True, False, False, True),
            ("ticket-exact", "ticket", False, False, False, False),
            ("ticket-nested", "ticket", True, False, True, False),
            ("ticket-key", "ticket", True, True, True, False),
            ("ticket-unicode-wire", "ticket", True, False, False, True),
            ("claim-exact", "claim", False, False, False, False),
            ("claim-nested", "claim", True, False, True, False),
            ("claim-key", "claim", True, True, False, True),
        )
        body = self.body(execution=True)
        before_files = (self.profile / ".env").read_bytes(), (self.profile / self.plugin.CONNECTOR_STATE).read_bytes()
        for name, secret, nested, key, structured, unicode in cases:
            with self.subTest(case=name):
                (self.connector_root / "result-echo.json").write_text(json.dumps({
                    "secret": secret, "nested": nested, "key": key, "structured": structured, "unicode": unicode}))
                before = len(self.claims), len(self.supports)
                status, answer = self.request(EXECUTE, "POST", body)
                self.assertEqual((len(self.claims), len(self.supports)), (before[0] + 1, before[1] + 1))
                self.assertEqual(self.trades, [])
                self.assertEqual(before_files, ((self.profile / ".env").read_bytes(),
                                               (self.profile / self.plugin.CONNECTOR_STATE).read_bytes()))
                self.assertEqual(status, 504, name)
                self.assertNotIn(body["execution"]["ticket"], json.dumps(answer))
                self.assertNotIn(vector["argsJson"], json.dumps(answer, ensure_ascii=False))


if __name__ == "__main__":
    import unittest
    unittest.main()
