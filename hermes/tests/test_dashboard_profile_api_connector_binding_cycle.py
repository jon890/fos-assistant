"""바인딩 설치가 그 서버만 껐다 켜는 경로를 검사한다(ADR-20261120 connector-auto-apply)."""

import contextlib
import json
import sys
from unittest import mock

from dashboard_profile_api_support import DEMO, OTHER
import dashboard_profile_api_support as support


class ProfileApiConnectorBindingCycleTest(support.ProfileApiRouteTest):
    @contextlib.contextmanager
    def clock(self, now):
        """끄기 기록의 시계를 패키지의 모든 하위 모듈에서 함께 바꾼다."""
        prefix = self.plugin.__name__ + "."
        with contextlib.ExitStack() as stack:
            for key, module in list(sys.modules.items()):
                if key.startswith(prefix) and hasattr(module, "_cycle_now"):
                    stack.enter_context(mock.patch.object(module, "_cycle_now", return_value=now))
            yield

    def record(self):
        return json.loads((self.root / "alice/.fos-connectors.json").read_text(encoding="utf-8"))

    def marks(self):
        path = self.root / "alice/.fos-connector-cycle.json"
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}

    def bound_and_changed(self):
        """두 플러그인을 붙인 뒤 demo 의 정의를 바꾼다. 다음 설치는 재시작이 필요한 정의 변경이다."""
        connector, _ = self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        self.assertEqual(self.bind(OTHER, "c2").status_code, 200)
        self.declare_owner_output(connector)

    def test_definition_change_answers_cycle_available(self):
        """정의만 바뀐 다시 설치는 재시작이 필요하고 껐다 켜기로 풀 수 있다고 답한다."""
        self.bound_and_changed()

        rebound = self.bind()

        self.assertEqual(rebound.status_code, 200, rebound.body)
        self.assertIs(rebound.body["restart_required"], True)
        self.assertIs(rebound.body["cycle_available"], True)
        self.assertIs(rebound.body["cycle_off"], False)

    def test_reattaching_a_detached_name_answers_cycle_available(self):
        """뗀 기록에 남은 이름을 다시 붙이면 재시작이 필요하고 껐다 켜기로 풀 수 있다고 답한다."""
        self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        self.assertEqual(self.bind(enabled=False).status_code, 200)

        reattached = self.bind()

        self.assertEqual(reattached.status_code, 200, reattached.body)
        self.assertIs(reattached.body["restart_required"], True)
        self.assertIs(reattached.body["cycle_available"], True)

    def test_plugin_file_change_is_not_cycle_available(self):
        """`fos-ctx` 파일이 바뀌면 재시작만 풀 수 있어 cycle_available 이 거짓이다."""
        self.bound_and_changed()
        hook = next((self.root / "alice/plugins/fos-ctx").glob("*.py"))
        hook.write_text(hook.read_text(encoding="utf-8") + "\n# 옛 판\n", encoding="utf-8")

        rebound = self.bind()

        self.assertEqual(rebound.status_code, 200, rebound.body)
        self.assertIs(rebound.body["plugin_updated"], True)
        self.assertIs(rebound.body["cycle_available"], False)

    def test_cycle_off_disables_only_the_config_entry_and_keeps_other_bindings(self):
        """끄기는 설정 항목만 끄고 소유 기록과 다른 바인딩, 다른 profile 은 바이트 그대로 둔다."""
        self.bound_and_changed()
        self.make_profile("bob")
        other_before = self.record()[OTHER]
        other_server = self.alice_config()["mcp_servers"][self.record()[OTHER]["mcp_server"]]
        bob_before = self.tree("bob")

        with self.clock(1000.0):
            off = self.bind(cycle="off")

        self.assertEqual(off.status_code, 200, off.body)
        self.assertEqual({key: off.body[key] for key in ("restart_required", "reload_pending", "cycle_off")},
                         {"restart_required": False, "reload_pending": False, "cycle_off": True})
        server = self.alice_config()["mcp_servers"]["demo"]
        self.assertIs(server["enabled"], False)
        self.assertEqual(self.record()[DEMO]["server"], {**server, "enabled": True})
        self.assertEqual(self.marks(), {DEMO: 1000.0})
        self.assertEqual(self.record()[OTHER], other_before)
        self.assertEqual(self.alice_config()["mcp_servers"][other_before["mcp_server"]], other_server)
        self.assertEqual(self.tree("bob"), bob_before)
        # 같은 profile 의 다른 바인딩은 꺼진 항목이 있어도 다시 설치하고 조회할 수 있다.
        self.assertEqual(self.bind(OTHER, "c2").status_code, 200)
        self.assertEqual(self.status_of()["profile"], "alice")

    def test_install_before_minimum_keeps_off_and_after_minimum_turns_on(self):
        """끈 지 120초 전의 보통 설치는 꺼진 채 두고, 지난 뒤의 보통 설치는 켜고 반영 예정을 답한다."""
        self.bound_and_changed()
        with self.clock(1000.0):
            self.assertEqual(self.bind(cycle="off").status_code, 200)

        with self.clock(1100.0):
            early = self.bind()
        self.assertEqual(early.status_code, 200, early.body)
        self.assertIs(early.body["cycle_off"], True)
        self.assertIs(self.alice_config()["mcp_servers"]["demo"]["enabled"], False)
        self.assertEqual(self.marks(), {DEMO: 1000.0})

        with self.clock(1120.0):
            on = self.bind()
        self.assertEqual(on.status_code, 200, on.body)
        self.assertEqual({key: on.body[key] for key in ("restart_required", "reload_pending", "cycle_off")},
                         {"restart_required": False, "reload_pending": True, "cycle_off": False})
        self.assertEqual(self.alice_config()["mcp_servers"]["demo"], self.record()[DEMO]["server"])
        self.assertEqual(self.marks(), {})

    def test_disabled_entry_without_mark_is_an_operator_change(self):
        """끄기 기록 없이 enabled 만 다르면 운영자가 바꾼 서버로 보고 거절한다."""
        self.bind_fixture()
        self.assertEqual(self.bind().status_code, 200)
        config = self.alice_config()
        config["mcp_servers"]["demo"]["enabled"] = False
        (self.root / "alice/config.yaml").write_text(json.dumps(config), encoding="utf-8")

        self.assertEqual(self.bind().status_code, 409)

    def test_detach_while_off_removes_entry_and_mark(self):
        """꺼진 동안 떼면 200 이고 설정 항목과 끄기 기록에서 그 플러그인이 빠진다."""
        self.bound_and_changed()
        with self.clock(1000.0):
            self.assertEqual(self.bind(cycle="off").status_code, 200)

        detached = self.bind(enabled=False)

        self.assertEqual(detached.status_code, 200, detached.body)
        self.assertNotIn("demo", self.alice_config()["mcp_servers"])
        self.assertEqual(self.marks(), {})

    def test_unknown_cycle_value_is_rejected(self):
        """bind.cycle 은 off 만 받는다."""
        self.bind_fixture()

        self.assertEqual(self.bind(cycle="on").status_code, 400)
