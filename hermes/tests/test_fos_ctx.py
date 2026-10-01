"""Hermes 설치 없이 fos-ctx plugin 의 모양과 서명 계약, hook 분기를 검사한다.

서명 기대값은 fos-assistant `docs/hermes/delegation.md` 「`_fos_ctx` 계약」 표의 값을 그대로 옮겼다.
서버 쪽 `McpCallContextTest` 도 같은 값을 쓴다. 한쪽 계약이 바뀌면 두 저장소를 함께 고친다.
자식 session 등록의 서명 기대값은 같은 문서의 「하위 에이전트 session 등록 계약」 확인 값이다.
"""

import http.server
import importlib.util
import json
import logging
import os
import pathlib
import sqlite3
import sys
import tempfile
import threading
import types
import unittest

import yaml


ROOT = pathlib.Path(__file__).resolve().parents[1]
PLUGIN_DIR = ROOT / "plugins/fos-ctx"

# 계약 표의 값이다. 토큰은 가짜 값이다.
FAKE_MCP_CREDENTIAL = "test-mcp-token-0001"
VECTOR_KEY = "41ed73a34f34174ba0b6ded1b16cf4a085b6da45df0f711ccbeaf2a2bbc2a2ac"
VECTOR_ROOT = "fos-00000000-0000-4000-8000-000000000001"
VECTOR_SESSION = "하위-세션-1"
VECTOR_CALL = "call_0001"
VECTOR_SIG = {
    "agent_delegate": "b28a128dbb642aba7a8b4c35dcb237e2feb5a452305ec275909c32a00ae1b25b",
    "agent_status": "62109c6c99e7ed4638e4343f1e5b6b22a3f55dd974560916149986866c253236",
}
# 자식 session 등록 계약의 확인 값이다. 최상위 부모와 하위 에이전트 부모 두 경우다.
VECTOR_TOP_CHILD = "하위-세션-1"
VECTOR_TOP_SUBAGENT_SIG = "ba540b481d830453accd812c8610be8d9467a532d5ff3db891514dcfe3d0726b"
VECTOR_CHILD = "하위-세션-2"
VECTOR_SUBAGENT_SIG = "5479a21f26ddeb337754d4fd86dd3a0e36e0ef1f6ff2cc879c0fd07da5d84485"


def load_plugin():
    spec = importlib.util.spec_from_file_location("fos_ctx_under_test", PLUGIN_DIR / "__init__.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ManifestTest(unittest.TestCase):
    def test_manifest_shape(self):
        """manifest 가 정해진 모양을 갖는다."""
        manifest = yaml.safe_load((PLUGIN_DIR / "plugin.yaml").read_text(encoding="utf-8"))
        self.assertEqual(manifest["name"], PLUGIN_DIR.name)
        self.assertEqual(manifest["hooks"], ["pre_tool_call", "subagent_start"])
        for key in ("version", "description", "author"):
            self.assertIsInstance(manifest.get(key), str)
            self.assertTrue(manifest[key].strip())
        # 환경 변수를 요구하면 토큰이 없는 profile 에서 plugin 이 꺼져 agent_* 호출이 서명 없이 나간다.
        self.assertNotIn("requires_env", manifest)


class SignatureContractTest(unittest.TestCase):
    def setUp(self):
        self.plugin = load_plugin()

    def test_key_is_hex_digest_of_token(self):
        """키는 토큰의 hex digest 다."""
        self.assertEqual(self.plugin.signing_key(FAKE_MCP_CREDENTIAL), VECTOR_KEY)

    def test_signature_matches_contract_vector(self):
        """서명이 계약 문서의 기대값과 같다."""
        for tool, expected in VECTOR_SIG.items():
            with self.subTest(tool=tool):
                actual = self.plugin.sign(VECTOR_KEY, tool, VECTOR_ROOT, VECTOR_SESSION, VECTOR_CALL)
                self.assertEqual(actual, expected)

    def test_subagent_signature_matches_contract_vectors(self):
        """하위 에이전트 session 등록의 서명이 계약 문서의 기대값과 같다."""
        self.assertEqual(self.plugin.sign_subagent(VECTOR_KEY, VECTOR_ROOT, VECTOR_ROOT, VECTOR_TOP_CHILD),
                         VECTOR_TOP_SUBAGENT_SIG)
        self.assertEqual(self.plugin.sign_subagent(VECTOR_KEY, VECTOR_ROOT, VECTOR_SESSION, VECTOR_CHILD),
                         VECTOR_SUBAGENT_SIG)


class PluginFixture(unittest.TestCase):
    """가짜 state.db 와 비밀값 scope 로 plugin 을 읽는다. 검사는 두지 않는다."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = pathlib.Path(self.tmp.name) / "state.db"
        con = sqlite3.connect(self.db)
        con.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, parent_session_id TEXT)")
        con.executemany(
            "INSERT INTO sessions VALUES (?, ?)",
            [
                (VECTOR_ROOT, None),
                ("compressed-1", VECTOR_ROOT),
                (VECTOR_SESSION, "compressed-1"),
                ("loop-a", "loop-b"),
                ("loop-b", "loop-a"),
            ],
        )
        con.commit()
        con.close()

        self.token = FAKE_MCP_CREDENTIAL
        self.secret_error = None
        secret_scope = types.ModuleType("agent.secret_scope")

        def get_secret(name, default=None):
            if self.secret_error is not None:
                raise self.secret_error
            return self.token if name == "MCP_FOS_ASSISTANT_API_KEY" else default

        secret_scope.get_secret = get_secret
        agent = types.ModuleType("agent")
        agent.secret_scope = secret_scope
        constants = types.ModuleType("hermes_constants")
        constants.get_hermes_home = lambda: pathlib.Path(self.tmp.name)
        self.saved = {name: sys.modules.get(name) for name in ("agent", "agent.secret_scope", "hermes_constants")}
        sys.modules.update({"agent": agent, "agent.secret_scope": secret_scope, "hermes_constants": constants})
        self.plugin = load_plugin()

    def tearDown(self):
        for name, module in self.saved.items():
            if module is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = module
        self.tmp.cleanup()

    def call(self, tool, session=VECTOR_SESSION, call_id=VECTOR_CALL, args=None):
        return self.plugin.pre_tool_call(
            tool_name=tool, args=args or {}, session_id=session, tool_call_id=call_id, task_id="t"
        )


class HookTest(PluginFixture):

    def test_other_tools_are_untouched(self):
        """대상이 아닌 도구의 호출은 건드리지 않는다."""
        for tool in ("terminal", "mcp__other__agent_delegate", "mcp__fos_assistant_memory__memory_read"):
            with self.subTest(tool=tool):
                self.assertIsNone(self.call(tool))

    def test_agent_tool_gets_signed_context_over_forged_value(self):
        """agent 도구 호출은 위조된 값 대신 서명된 context 를 받는다."""
        forged = {"_fos_ctx": {"session_id": "forged-by-model"}, "prompt": "x"}
        result = self.call("mcp__fos_assistant__agent_delegate", args=forged)
        self.assertEqual(result["action"], "modify")
        self.assertEqual(
            result["args"],
            {"_fos_ctx": {
                "v": 1,
                "session_id": VECTOR_SESSION,
                "root_session_id": VECTOR_ROOT,
                "tool_call_id": VECTOR_CALL,
                "sig": VECTOR_SIG["agent_delegate"],
            }},
        )
        # Hermes 의 병합은 원래 인자 뒤에 hook 의 키를 얹는다.
        merged = {**forged, **result["args"]}
        self.assertEqual(merged["_fos_ctx"]["session_id"], VECTOR_SESSION)
        self.assertEqual(merged["prompt"], "x")

    def test_top_level_session_is_its_own_root(self):
        """최상위 session 은 스스로 root 가 된다."""
        result = self.call("mcp__fos_assistant__agent_status", session=VECTOR_ROOT)
        self.assertEqual(result["args"]["_fos_ctx"]["root_session_id"], VECTOR_ROOT)

    def test_parent_cycle_stops(self):
        """부모 사슬이 순환하면 거기서 멈춘다."""
        result = self.call("mcp__fos_assistant__agent_list", session="loop-a")
        self.assertIn(result["args"]["_fos_ctx"]["root_session_id"], {"loop-a", "loop-b"})

    def test_agent_tool_blocks_without_material(self):
        """서명 재료가 없으면 agent 도구 호출을 막는다."""
        cases = {
            "no token": dict(token=None),
            "no session": dict(session=""),
            "no tool_call_id": dict(call_id=""),
            "secret scope error": dict(error=RuntimeError("unscoped")),
        }
        for label, case in cases.items():
            with self.subTest(label=label):
                self.token = case.get("token", FAKE_MCP_CREDENTIAL)
                self.secret_error = case.get("error")
                result = self.call(
                    "mcp__fos_assistant__agent_delegate",
                    session=case.get("session", VECTOR_SESSION),
                    call_id=case.get("call_id", VECTOR_CALL),
                )
                self.assertEqual(result["action"], "block")
                self.assertTrue(result["message"])

    def test_memory_and_artifact_tools_block_without_signature(self):
        """서명이 없으면 memory 와 artifact 도구 호출을 막는다."""
        for tool in ("memory_read", "artifact_write"):
            name = "mcp__fos_assistant__" + tool
            with self.subTest(tool=tool, state="signed"):
                self.token, self.secret_error = FAKE_MCP_CREDENTIAL, None
                self.assertEqual(self.call(name)["action"], "modify")
            with self.subTest(tool=tool, state="no token"):
                self.token = None
                self.assertEqual(self.call(name), {"action": "block", "message": self.plugin.BLOCK_MESSAGE})
            with self.subTest(tool=tool, state="secret scope error"):
                self.token, self.secret_error = FAKE_MCP_CREDENTIAL, RuntimeError("unscoped")
                self.assertEqual(self.call(name)["action"], "block")
            self.secret_error = None

    def test_other_control_plane_tool_passes_without_signature(self):
        """그 밖의 control plane 도구는 서명이 없어도 통과한다."""
        self.token = None
        self.assertIsNone(self.call("mcp__fos_assistant__other_tool"))

    def test_skill_manage_is_blocked(self):
        """skill_manage 호출은 막는다."""
        result = self.call("skill_manage", args={"action": "create", "name": "x"})
        self.assertEqual(result["action"], "block")
        self.assertEqual(result["message"],
                         "이 환경에서는 스킬을 대화로 만들거나 고칠 수 없다. 에이전트 관리 화면에서 올린다")
        # MCP 이름에 같은 글자가 들어가도 막지 않는다.
        self.assertIsNone(self.call("mcp__other__skill_manage"))

    def test_missing_state_db_falls_back_to_session(self):
        """state DB 가 없으면 session 값으로 대신한다."""
        self.db.unlink()
        result = self.call("mcp__fos_assistant__agent_status")
        self.assertEqual(result["args"]["_fos_ctx"]["root_session_id"], VECTOR_SESSION)


class SubagentRegistrationTest(PluginFixture):
    """subagent_start 가 Control Plane 가짜 서버에 등록하는 모양과 실패 분기를 본다."""

    def setUp(self):
        super().setUp()
        self.requests = []
        self.statuses = []
        test = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                test.requests.append({"path": self.path, "headers": dict(self.headers),
                                      "body": json.loads(raw)})
                self.send_response(test.statuses.pop(0) if test.statuses else 200)
                self.send_header("Content-Length", "0")
                self.end_headers()

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = "http://127.0.0.1:%d/internal/hermes/session-bindings/subagent" % self.server.server_address[1]
        self.saved_env = os.environ.get("FOS_CTX_SUBAGENT_URL")
        os.environ["FOS_CTX_SUBAGENT_URL"] = self.url
        self.plugin.REGISTER_TIMEOUT = 1.0

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        if self.saved_env is None:
            os.environ.pop("FOS_CTX_SUBAGENT_URL", None)
        else:
            os.environ["FOS_CTX_SUBAGENT_URL"] = self.saved_env
        super().tearDown()

    def start(self, **overrides):
        kwargs = dict(parent_session_id=VECTOR_SESSION, parent_turn_id="turn-1", parent_subagent_id=None,
                      child_session_id=VECTOR_CHILD, child_subagent_id="sa-1", child_role="leaf",
                      child_goal="goal text")
        kwargs.update(overrides)
        with self.assertLogs(self.plugin.logger, level="INFO") as logs:
            self.assertIsNone(self.plugin.subagent_start(**kwargs))
        return "\n".join(logs.output)

    def test_registers_child_with_signed_body(self):
        """자식 session 을 서명된 본문으로 등록한다."""
        log = self.start()
        self.assertEqual(len(self.requests), 1)
        request = self.requests[0]
        self.assertEqual(request["path"], "/internal/hermes/session-bindings/subagent")
        self.assertEqual(request["headers"]["Authorization"], "Bearer " + FAKE_MCP_CREDENTIAL)
        self.assertEqual(request["body"], {
            "v": 1,
            "parent_session_id": VECTOR_SESSION,
            "parent_root_session_id": VECTOR_ROOT,
            "child_session_id": VECTOR_CHILD,
            "child_subagent_id": "sa-1",
            "parent_subagent_id": None,
            "sig": VECTOR_SUBAGENT_SIG,
        })
        # 목표 글과 토큰과 서명은 로그에 남기지 않는다.
        for secret in (FAKE_MCP_CREDENTIAL, VECTOR_SUBAGENT_SIG, "goal text"):
            self.assertNotIn(secret, log)

    def test_retries_once_on_server_error(self):
        """서버 오류면 한 번 다시 시도한다."""
        self.statuses = [503, 201]
        self.assertIn("시도 2", self.start())
        self.assertEqual(len(self.requests), 2)

    def test_existing_binding_counts_as_registered(self):
        """이미 묶인 자식은 등록된 것으로 본다."""
        self.statuses = [200]
        self.assertIn("시도 1", self.start())

    def test_does_not_retry_client_error(self):
        """클라이언트 오류는 다시 시도하지 않는다."""
        for status in (401, 403, 409):
            with self.subTest(status=status):
                self.requests.clear()
                self.statuses = [status]
                self.assertIn("HTTP %d" % status, self.start())
                self.assertEqual(len(self.requests), 1)

    def test_connection_failure_only_logs(self):
        """연결에 실패하면 로그만 남긴다."""
        self.server.shutdown()
        self.server.server_close()
        os.environ["FOS_CTX_SUBAGENT_URL"] = "http://127.0.0.1:1/unreachable"
        self.assertIn("등록하지 못했다", self.start())

    def test_skips_without_url_token_or_child(self):
        """URL, 토큰, 자식 중 하나라도 없으면 등록을 건너뛴다."""
        cases = {
            "no url": lambda: os.environ.pop("FOS_CTX_SUBAGENT_URL", None),
            "no token": lambda: setattr(self, "token", None),
        }
        for label, prepare in cases.items():
            with self.subTest(label=label):
                prepare()
                self.start()
                self.assertEqual(self.requests, [])
                os.environ["FOS_CTX_SUBAGENT_URL"] = self.url
                self.token = FAKE_MCP_CREDENTIAL
        self.start(child_session_id=None)
        self.assertEqual(self.requests, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
