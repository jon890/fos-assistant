"""워크벤치 저장소의 플러그인(`valuation-ontology`) 모양을 원격 MCP 플러그인으로 받는지 검사한다.

`fixtures/valuation-ontology/` 는 그 저장소 `plugin/` 의 모양을 옮긴 사본이다. 근거는 ADR-20261026 remote-mcp-plugin 이다.
Claude Code manifest 에는 `skills` 칸이 없고, Codex manifest 는 스킬 경로를 늘어놓고, `.mcp.json` 에는 헤더가 없다.
토큰을 보낼 헤더와 도구 정책은 `assistant.json` 만 선언한다. 워크벤치의 `assistant.json` 이 바뀌면 이 사본도 고친다.
"""

import json
import pathlib
import shutil
import unittest

import test_connector_manifest as base

FIXTURE = pathlib.Path(__file__).resolve().parent / "fixtures/valuation-ontology"
NAME = "valuation-ontology"
# 운영자가 믿은 주소다. 플러그인 파일의 주소는 무시한다.
OPERATOR_URL = "https://operator.test/mcp"
WRITES = {"record_trades", "submit_proposals", "propose_ontology_change"}


class WorkbenchPluginTest(base.ConnectorGateCase):
    def setUp(self):
        super().setUp()
        self.root = self.base / NAME
        shutil.copytree(FIXTURE, self.root)
        self.operator_list({NAME: {"root": str(self.root), "url": OPERATOR_URL}})

    def entry(self):
        entries = {item["id"]: item for item in self.catalog()}
        self.assertIn(NAME, entries, "워크벤치 플러그인이 카탈로그에 없다")
        return entries[NAME]

    def test_catalog_has_one_secret_token_field_and_the_connection_tool(self):
        """`schema: 2` 로 오르고, 가리는 필수 칸 하나와 확인 도구 `get_connection` 을 갖는다. 운영 값은 없다."""
        entry = self.entry()
        self.assertEqual((entry["schema"], entry["title"], entry["verify"]),
                         (2, "가치투자 온톨로지", {"tool": "get_connection"}))
        self.assertEqual([(field["key"], field["env"], field["secret"], field.get("required", True))
                          for field in entry["fields"]], [("token", "VALUATION_ONTOLOGY_TOKEN", True, True)])
        self.assertEqual(sorted(entry["skills"]),
                         ["ontology-change", "ontology-explore", "ontology-record", "ontology-start"])
        self.assertNotIn("workbench-plugin-file.invalid", json.dumps(entry))

    def test_installed_server_uses_the_operator_url_and_one_bearer_header(self):
        """설치한 서버 정의는 운영 목록의 주소로 붙고 토큰은 `Authorization` 헤더로만 간다. 모델에게서 빼는 도구는 없다."""
        manifest = self.plugin._connector_manifest(NAME)
        self.assertEqual(manifest["mcp_server"], NAME)
        server = manifest["server"]
        self.assertEqual({key: server[key] for key in ("url", "headers", "strict_redirect_headers", "enabled")},
                         {"url": OPERATOR_URL, "headers": {"Authorization": "Bearer ${VALUATION_ONTOLOGY_TOKEN}"},
                          "strict_redirect_headers": True, "enabled": True})
        self.assertFalse((server.get("tools") or {}).get("exclude"))

    def test_reads_need_no_approval_and_writes_need_approval_with_a_conversation_grant(self):
        """조회 도구는 승인이 없고 쓰기 셋은 `WRITE`, `required`, 대화 허락이다. 로컬 커넥터 때의 정책과 같다."""
        tools = self.entry()["tools"]
        self.assertEqual(len(tools), 14)
        for name, tool in tools.items():
            with self.subTest(name):
                if name in WRITES:
                    self.assertEqual((tool["risk"], tool["approval"], tool["conversation_grant"], tool["outbound"]),
                                     ("WRITE", "required", True, False))
                else:
                    self.assertEqual((tool["risk"], tool["approval"]), ("READ", "none"))


if __name__ == "__main__":
    unittest.main()
