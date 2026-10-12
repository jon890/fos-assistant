"""실제 manifest producer의 표시 이름과 정책 분리를 검사한다."""

import copy
import importlib
import json
import pathlib
import subprocess
import sys

from test_connector_manifest import ConnectorGateCase


class ConnectorArgumentLabelsTest(ConnectorGateCase):
    def setUp(self):
        super().setUp()
        self.rewrite("connector.json", lambda value: value.update(schema=2, tools={
            "list_scopes": {"risk": "READ"}, "verify": {"risk": "READ"}}))

    def test_catalog_labels_do_not_change_normalized_policy(self):
        module = importlib.import_module(self.plugin.__name__ + ".connector_manifest")
        before = module._load_connector("demo-notes", module._connector_roots()["demo-notes"])
        self.rewrite("connector.json", lambda value: value["tools"]["list_scopes"].update(
            arg_labels={"scope": "범위"}))
        after = module._load_connector("demo-notes", module._connector_roots()["demo-notes"])
        self.assertEqual(before["tools"], after["tools"])
        self.assertEqual(before["server"], after["server"])
        self.assertEqual(self.catalog()[0]["tools"]["list_scopes"]["arg_labels"], {"scope": "범위"})

    def test_consumer_fixture_matches_actual_python_producer(self):
        script = pathlib.Path(__file__).parent / "scripts/export_argument_catalog.py"
        subprocess.run([sys.executable, str(script), "--check"], check=True)

    def test_invalid_labels_keep_connector_in_catalog(self):
        invalid = [[], 1, {"scope": 1}, {"scope": {}}, {"__proto__": "이름"},
                   {"constructor": "이름"}, {"prototype": "이름"}, {"scope": " 이름"},
                   {"scope": "이름 "}, {"scope": ""}, {"scope": "가" * 81},
                   {"a": "같음", "b": "같음"}, {"a": "b", "b": "다름"},
                   {"a" * 32: "이름"}, {"받는 사람": "이름"},
                   {"k%d" % n: "이름%d" % n for n in range(65)}]
        invalid += [{"scope": "앞" + chr(n) + "뒤"} for n in
                    [0, 9, 10, 31, 127, 159, 0x61c, 0x200e, 0x2028, 0x2029, 0x202e, 0x2066, 0xd800]]
        for labels in invalid:
            with self.subTest(labels=repr(labels)):
                self.rewrite("connector.json", lambda value: value["tools"]["list_scopes"].update(arg_labels=labels))
                self.assertEqual(self.catalog()[0]["tools"]["list_scopes"]["arg_labels"], {})

    def test_unicode_count_and_exact_utf8_budget(self):
        appearance = importlib.import_module(self.plugin.__name__ + ".connector_appearance")
        read = lambda labels: appearance._tool_arg_labels({"arg_labels": labels})
        self.assertEqual(read({"a": "😀" * 80}), {"a": "😀" * 80})
        labels = {"k%d" % n: "😀" * 60 + str(n) for n in range(64)}
        self.assertEqual(read(labels), labels)
        labels = {"k%d" % n: "😀" * 79 + str(n) for n in range(64)}
        self.assertEqual(read(labels), {})
        # 동일한 compact JSON 바이트 경계를 세 소비자가 비교한다.
        labels = {"k%d" % n: "😀" * 60 + str(n) for n in range(64)}
        remaining = 16384 - len(json.dumps(labels, ensure_ascii=False, separators=(",", ":")).encode())
        for n in range(64):
            added = min(80 - len(labels["k%d" % n]), remaining)
            labels["k%d" % n] += "x" * added
            remaining -= added
        self.assertEqual(remaining, 0)
        self.assertEqual(read(labels), labels)
        too_big = copy.deepcopy(labels)
        too_big["k63"] += "x"
        self.assertEqual(read(too_big), {})
