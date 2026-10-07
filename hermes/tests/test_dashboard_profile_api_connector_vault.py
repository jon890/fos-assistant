"""dashboard-profile-api 의 connector_vault 분기를 검사한다."""

import json
import os
import types
from plugin_loading import patch_plugin
from dashboard_profile_api_support import DEMO, DEMO_BASE, DEMO_VALUE, OTHER, OTHER_VALUE, VAULT, VAULT_IMPORT
import dashboard_profile_api_support as support


class ProfileApiConnectorVaultTest(support.ProfileApiRouteTest):
    def test_vault_files_are_written_deleted_and_imported_without_echoing_values(self):
        """보관 파일을 쓰고 지우고 옮긴다. 형식 오류와 필수 칸 누락은 400 이고 응답에 값이 없다."""
        self.bind_fixture()
        directory = self.hermes_root / "connector-vault"
        self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
        stored = directory / "c1.json"
        self.assertEqual(stored.stat().st_mode & 0o777, 0o600)
        self.assertEqual(json.loads(stored.read_text(encoding="utf-8")),
                         {"v": 1, "connector": DEMO, "values": {"token": DEMO_VALUE}})
        # 빈 선택 칸은 넣지 않는다.
        self.assertEqual(self.vault("PUT", vault="c3", connector=DEMO,
                                    values={"token": DEMO_VALUE, "scope": ""}).status_code, 200)
        self.assertEqual(json.loads((directory / "c3.json").read_text())["values"], {"token": DEMO_VALUE})

        written = stored.read_bytes()
        secret = "demo_bad_0123456789"
        for label, body in (
            ("unknown key", {"vault": "c1", "connector": DEMO, "values": {"token": secret, "other": "x"}}),
            ("required missing", {"vault": "c1", "connector": DEMO, "values": {"scope": "a"}}),
            ("required empty", {"vault": "c1", "connector": DEMO, "values": {"token": ""}}),
            ("pattern", {"vault": "c1", "connector": DEMO, "values": {"token": secret + "0"}}),
            ("two lines", {"vault": "c1", "connector": DEMO, "values": {"token": secret, "scope": "a\nb"}}),
            ("not a string", {"vault": "c1", "connector": DEMO, "values": {"token": 1}}),
            ("values not an object", {"vault": "c1", "connector": DEMO, "values": [secret]}),
            ("bad vault name", {"vault": "c0", "connector": DEMO, "values": {"token": secret}}),
            ("path in vault name", {"vault": "../c1", "connector": DEMO, "values": {"token": secret}}),
            ("unknown connector", {"vault": "c1", "connector": "unknown", "values": {"token": secret}}),
            ("extra key", {"vault": "c1", "connector": DEMO, "values": {"token": secret}, "profile": "alice"}),
        ):
            with self.subTest(label):
                response = self.vault("PUT", **body)
                self.assertEqual(response.status_code, 400)
                self.assertNotIn(secret, json.dumps(response.body))
                self.assertEqual(stored.read_bytes(), written)
        # 같은 이름의 보관 파일이 다른 커넥터의 것이면 409 다.
        self.assertEqual(self.vault("PUT", vault="c1", connector=OTHER,
                                    values={"token": OTHER_VALUE}).status_code, 409)
        self.assertEqual(stored.read_bytes(), written)
        self.assertEqual(self.request(VAULT, "PUT", body={"vault": "c1", "connector": DEMO,
                                                          "values": {"token": DEMO_VALUE}}), 401)

        deleted = self.vault("DELETE", vault="c3")
        self.assertEqual((deleted.status_code, deleted.body), (200, {"changed": True}))
        self.assertFalse((directory / "c3.json").exists())
        self.assertEqual(self.vault("DELETE", vault="c3").body, {"changed": False})
        self.assertEqual(self.vault("DELETE", vault="c3", connector=DEMO).status_code, 400)

        # 옛 설치의 관리 profile 에서 칸 값을 옮긴다. 빈 선택 칸은 넣지 않는다.
        self.make_profile("bob")
        self.plugin._apply_template("bob")
        (self.root / "bob/.env").write_text("DEMO_TOKEN=%s\nDEMO_SCOPE=\nOTHER=keep\n" % DEMO_VALUE, encoding="utf-8")
        self.assertEqual(self.connector(profile="bob").status_code, 200)
        imported = self.vault("POST", VAULT_IMPORT, vault="c5", connector=DEMO, profile="bob")
        self.assertEqual((imported.status_code, imported.body), (200, {"ok": True}))
        self.assertEqual(json.loads((directory / "c5.json").read_text()),
                         {"v": 1, "connector": DEMO, "values": {"token": DEMO_VALUE}})
        (self.root / "bob/.env").write_text("DEMO_SCOPE=a\n", encoding="utf-8")
        missing = self.vault("POST", VAULT_IMPORT, vault="c6", connector=DEMO, profile="bob")
        self.assertEqual(missing.status_code, 400)
        self.assertFalse((directory / "c6.json").exists())
        self.assertEqual(self.vault("POST", VAULT_IMPORT, vault="c6", connector=DEMO, profile="owner").status_code, 401)
        self.assertEqual(self.vault("POST", VAULT_IMPORT, vault="c6", connector=OTHER, profile="bob").status_code, 404)
        self.assertEqual(self.vault("POST", VAULT_IMPORT, vault="c6", connector=DEMO, profile="nobody").status_code, 404)

    def test_connector_without_fields_is_bound_with_an_empty_vault(self):
        """칸이 없는 커넥터는 카탈로그에 오르고, 빈 `values` 의 보관 파일로 확인 도구를 부르고 바인딩 설치가 된다."""
        demo, _ = self.bind_fixture()
        declared = json.loads((demo / "connector.json").read_text(encoding="utf-8"))
        declared["fields"] = []
        (demo / "connector.json").write_text(json.dumps(declared), encoding="utf-8")
        mcp = json.loads((demo / ".mcp.json").read_text(encoding="utf-8"))
        mcp["mcpServers"]["demo"]["env"] = {"DEMO_BASE": "${DEMO_BASE}"}
        (demo / ".mcp.json").write_text(json.dumps(mcp), encoding="utf-8")
        entry = next(item for item in self.request("/api/connectors/catalog", "GET", token="valid",
                                                   full_response=True).body if item["id"] == DEMO)
        self.assertEqual((entry["fields"], entry["skills"]), ([], ["demo"]))

        self.assertEqual(self.vault("PUT", vault="c7", connector=DEMO, values={}).status_code, 200)
        seen = []

        async def verify(manifest, tool, env):
            seen.append((tool, env))
            return types.SimpleNamespace(structured_content={"scopes": []}, is_error=False, content=[])

        with patch_plugin(self.plugin, "_mcp_sdk_problem", return_value=None), \
                patch_plugin(self.plugin, "_run_connector_tool", verify):
            called = self.request("/api/connectors/%s/call" % DEMO, "POST", token="valid", full_response=True,
                                  body={"tool": "list_scopes", "vault": "c7"})
        self.assertEqual((called.status_code, called.body), (200, {"ok": True, "result": {"scopes": []}}))
        self.assertEqual(seen, [("list_scopes", {"DEMO_BASE": DEMO_BASE,
                                                 "PATH": os.path.dirname(self.connector_command)})])

        installed = self.bind(vault="c7")
        self.assertEqual(installed.status_code, 200, installed.body)
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"]["env"], {"DEMO_BASE": DEMO_BASE})
        self.assertFalse((self.root / "alice/.env").exists())
        self.assertEqual(self.status_of()["connectors"][0]["configured"], True)
