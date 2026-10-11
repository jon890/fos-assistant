"""지원 제공자의 완료를 가정하지 않고 소비자의 fresh 확인과 금융 숨김을 검증한다."""

import copy
import importlib
import json
import uuid
import yaml
from unittest import mock

from test_connector_guard_transport import ConnectorGuardCase, PROFILE, PREPARE, EXECUTE
import test_connector_manifest as base


class ConnectorGuardBindingTest(ConnectorGuardCase):
    def support(self):
        return self.guard_module._guard_support(PROFILE, self.manifest, self.entry)

    def test_binding_stores_only_context_and_keeps_financial_hidden(self):
        config = {"mcp_servers": {"demo": self.entry["server"]},
                  "platform_toolsets": {"api_server": ["fos-assistant", "demo"]}}
        (self.profile / "config.yaml").write_text(yaml.safe_dump(config))
        directory = self.base / "vault"
        directory.mkdir()
        (directory / "c1.json").write_text(json.dumps({"v": 1, "connector": base.DEMO,
                                                      "values": {"token": "demo_ok_0123456789", "scope": "000007"}}))
        vault = importlib.import_module(self.plugin.__name__ + ".connector_vault")
        with mock.patch.object(vault, "_vault_dir", return_value=directory):
            status, answer = self.request("/api/connectors", "PUT", {
                "profile": PROFILE, "plugin": base.DEMO, "enabled": True,
                "bind": {"vault": "c1", "guard": self.guard}})
        self.assertEqual(status, 200, answer)
        self.assertEqual(answer["execution_guard"]["state"], "pending")
        installed = json.loads((self.profile / self.plugin.CONNECTOR_STATE).read_text())[base.DEMO]
        self.assertEqual(installed["guard"], self.guard)
        self.assertEqual(installed["server"]["tools"]["exclude"], ["place_order"])
        self.assertNotIn("nonce", json.dumps(installed))
        self.assertNotIn("FOS_APPROVAL_", (self.profile / ".env").read_text())
        self.assertEqual(len(self.supports), 1)
        self.assertEqual(self.claims + self.trades, [])

    def test_unsupported_pending_and_verified_need_actual_http_response(self):
        # 이 기대값은 Node의 재귀 키 정렬·UTF-8 SHA-256으로 독립 계산했다.
        self.assertEqual(self.manifest["execution_guard_manifest_sha256"], self.vector["manifestSha256"])
        old = copy.deepcopy(self.entry)
        old.pop("guard")
        self.assertEqual(self.guard_module._guard_support(PROFILE, self.manifest, old)["state"], "unsupported")
        self.assertEqual(self.supports, [])
        self.mode = "missing"
        self.assertEqual(self.support()["state"], "pending")
        self.mode = "ok"
        self.assertEqual(self.support()["state"], "verified")
        self.assertEqual(len(self.supports), 2)
        self.assertEqual(self.supports[-1][1], "Bearer " + self.ticket_vector["token"])
        self.assertEqual(self.claims + self.trades, [])

    def test_nonce_revision_types_redirect_and_replay_never_verify(self):
        for mode in ("nonce", "revision", "boolean", "redirect"):
            with self.subTest(mode=mode):
                self.mode = mode
                self.assertEqual(self.support()["state"], "pending")
        self.mode = "ok"
        self.assertEqual(self.support()["state"], "verified")
        self.mode = "replay"
        self.assertEqual(self.support()["state"], "pending")
        nonces = [body["nonce"] for body, auth in self.supports]
        self.assertEqual(len(nonces), len(set(nonces)))
        for nonce in nonces:
            self.assertEqual(str(uuid.UUID(nonce)), nonce)
        self.assertEqual(self.trades, [])

    def test_restart_and_manifest_only_change_keep_financial_excluded(self):
        self.assertEqual(self.support()["state"], "verified")
        self.guard_module = importlib.reload(self.guard_module)
        self.mode = "missing"
        self.assertEqual(self.support()["state"], "pending")
        self.rewrite("connector.json", lambda value: value.update(title="변경한 선언"))
        changed = self.plugin._connector_manifest(base.DEMO)
        self.assertEqual(self.guard_module._guard_support(PROFILE, changed, self.entry)["state"], "pending")
        self.assertEqual(changed["server"]["tools"]["exclude"], ["place_order"])
        self.assertNotIn("nonce", json.dumps(self.entry))
        self.assertEqual(self.claims + self.trades, [])

    def test_revocation_during_callback_blocks_prepare_and_execution(self):
        for route, body in ((PREPARE, self.body()), (EXECUTE, self.body(execution=True))):
            self.write_state()
            self.mode = "revoke"
            self.assertEqual(self.request(route, "POST", body)[0], 409)
        self.assertEqual(self.claims + self.trades, [])

    def test_missing_guard_reinstallation_and_changed_revision_fail_closed(self):
        self.entry.pop("guard")
        self.write_state()
        self.assertEqual(self.request(PREPARE, "POST", self.body())[0], 409)
        self.entry["guard"] = {**self.guard, "connectionUpdatedAt": "2026-10-10T00:00:01Z"}
        self.write_state()
        self.mode = "revision"
        # 대역은 이전 revision을 응답한다.
        self.entry["guard"]["connectionUpdatedAt"] = "2026-10-10T00:00:02Z"
        self.write_state()
        self.assertEqual(self.request(EXECUTE, "POST", self.body(execution=True))[0], 409)
        self.assertEqual(self.claims + self.trades, [])

    def test_guard_metadata_parser_never_accepts_nonce_or_ticket(self):
        for name in ("nonce", "ticket", "allowed"):
            changed = {**self.guard, name: "not-persisted"}
            with self.assertRaises(ValueError):
                self.guard_module._binding_guard(changed)
        for value in (1, "0", "01", "9223372036854775808"):
            with self.assertRaises(ValueError):
                self.guard_module._binding_guard({**self.guard, "bindingId": value})

    def test_status_uses_fresh_support_and_never_removes_financial_exclude(self):
        config = {"mcp_servers": {"demo": self.manifest["server"]}, "platform_toolsets": {"api_server": ["demo"]}}
        (self.profile / "config.yaml").write_text(yaml.safe_dump(config))
        for mode in ("ok", "missing", "nonce"):
            self.mode = mode
            status, answer = self.request("/api/connectors", "GET")
            self.assertEqual(status, 200, answer)
            self.assertEqual(answer["connectors"][0]["execution_guard"]["state"], "pending")
            self.assertIn("place_order", yaml.safe_load((self.profile / "config.yaml").read_text())["mcp_servers"]["demo"]["tools"]["exclude"])
        self.assertEqual(len(self.supports), 3)
        self.assertEqual(self.claims + self.trades, [])

    def test_old_install_definition_is_hidden_again_without_changing_read_tools(self):
        self.entry["server"]["tools"]["exclude"] = ["list_scopes"]
        self.write_state()
        config = {"mcp_servers": {"demo": self.entry["server"]}, "platform_toolsets": {"api_server": ["demo"]}}
        (self.profile / "config.yaml").write_text(yaml.safe_dump(config))
        self.mode = "missing"
        status, answer = self.request("/api/connectors", "GET")
        self.assertEqual(status, 200, answer)
        self.assertFalse(answer["connectors"][0]["configured"])
        server = yaml.safe_load((self.profile / "config.yaml").read_text())["mcp_servers"]["demo"]
        self.assertEqual(server["tools"]["exclude"], ["list_scopes", "place_order"])
        self.assertEqual(self.claims + self.trades, [])

    def test_manifest_guard_is_strict_and_execution_env_cannot_be_installed(self):
        original = json.loads((self.connector_root / "connector.json").read_text())
        for change in (lambda value: value["execution_guard"].update(extra=True),
                       lambda value: value["execution_guard"].update(prepare_tool="place_order"),
                       lambda value: value["execution_guard"]["scope_fields"][0].update(arg="clientOrderId"),
                       lambda value: value["execution_guard"]["scope_fields"][0].update(field="token")):
            modified = copy.deepcopy(original)
            change(modified)
            (self.connector_root / "connector.json").write_text(json.dumps(modified))
            self.assertIsNone(self.plugin._connector_manifest(base.DEMO))
        (self.connector_root / "connector.json").write_text(json.dumps(original))
        modified = copy.deepcopy(self.manifest)
        modified["server"]["env"]["FOS_APPROVAL_TICKET"] = "${FOS_APPROVAL_TICKET}"
        with self.assertRaises(ValueError):
            self.guard_module._guard_manifest(modified, original)
