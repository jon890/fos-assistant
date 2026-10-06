"""Hermes 설치 없이 fos-ctx plugin 의 모양과 서명 계약, hook 분기를 검사한다.

서명 기대값은 `docs/hermes/fos-ctx.md` 「`_fos_ctx` 계약」 표의 값을 그대로 옮겼다.
서버 쪽 `McpCallContextTest` 도 같은 값을 쓴다. 한쪽 계약이 바뀌면 두 쪽을 함께 고친다.
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
# 커넥터 정책 질의의 서명 확인 값이다. 계약은 `docs/backend/connector-tool-policy.md` 의 「도구 호출 판정」 이다.
# 서버 쪽 `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyRequestTest.java` 의
# `VECTOR_*` 와 같은 값이어야 한다. 한쪽을 바꾸면 두 쪽을 함께 고친다.
POLICY_VECTOR_KEY = "2ce07fe9da9032a6ba2d14ea44adf290f530110a4b5d2ed67bb72384342abf8b"
POLICY_VECTOR_HERMES_TOOL = "mcp__demo__write_note"
POLICY_VECTOR_ROOT = "fos-root-1"
POLICY_VECTOR_SESSION = "fos-session-1"
POLICY_VECTOR_CALL = "call_1"
POLICY_VECTOR_ARGS_JSON = '{"text":"안녕"}'
POLICY_VECTOR_SIG = "e23e297aec4837aeddd50e71dcde79969ad5544e6c93ea7b9ab7657f221037da"


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

    def test_policy_signature_matches_contract_vector(self):
        """커넥터 정책 질의의 서명이 서버 쪽 검사와 같은 기대값을 낸다."""
        self.assertEqual(self.plugin.signing_key("vector-token"), POLICY_VECTOR_KEY)
        actual = self.plugin.sign_policy(POLICY_VECTOR_KEY, POLICY_VECTOR_HERMES_TOOL, POLICY_VECTOR_ROOT,
                                         POLICY_VECTOR_SESSION, POLICY_VECTOR_CALL, POLICY_VECTOR_ARGS_JSON)
        self.assertEqual(actual, POLICY_VECTOR_SIG)


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

    def test_delegate_local_images_are_blocked_before_host_read(self):
        """공개 tasks schema 와 이전 top-level images 모두 로컬 첨부를 읽기 전에 막는다."""
        forbidden = ["/attachments/123/1.png", "../b/1.png", "file:///attachments/1.png",
                     "photo.png", "//host/photo.png", "", 1, {"path": "/photo.png"}]
        for source in forbidden:
            for args in ({"images": [source]}, {"tasks": [{"prompt": "사진", "images": [source]}]}):
                with self.subTest(args=args):
                    self.assertEqual(self.call("delegate_task", args=args),
                                     {"action": "block", "message": self.plugin.DELEGATE_IMAGE_MESSAGE})

    def test_delegate_remote_and_inline_images_stay_available(self):
        """이미지 없는 호출과 HTTP(S), image data URL 은 원래 도구로 전달한다."""
        images = ["https://images.example/photo.png", "http://images.example/photo.png",
                  "data:image/png;base64,cGhvdG8="]
        for args in ({}, {"images": []}, {"images": images},
                     {"tasks": [{"prompt": "사진", "images": images}, {"prompt": "글"}]}):
            with self.subTest(args=args):
                self.assertIsNone(self.call("delegate_task", args=args))

    def test_delegate_invalid_image_shapes_fail_closed(self):
        for args in ({"images": "/photo.png"}, {"tasks": "invalid"}, {"tasks": ["invalid"]},
                     {"tasks": [{"images": "photo.png"}]}, {"images": ["https://[broken"]}):
            with self.subTest(args=args):
                self.assertEqual(self.call("delegate_task", args=args),
                                 {"action": "block", "message": self.plugin.DELEGATE_IMAGE_MESSAGE})

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
        """서명이 없으면 memory, artifact, 할 일 제안 도구 호출을 막는다."""
        for tool in ("memory_read", "artifact_write", "follow_up_propose"):
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

# 대시보드 plugin 이 설치 때 쓰는 이름 대응과 같은 모양이다.
TOOL_MAP = {"v": 1, "servers": {"demo": {
    "connector": "demo-notes", "prefix": "mcp__demo__",
    "tools": {"mcp__demo__list_scopes": "list_scopes", "mcp__demo__write_note": "write_note"},
}}}


class ConnectorPolicyTest(PluginFixture):
    """연결용 profile 에서 커넥터 도구 호출을 가짜 정책 서버에 묻는 모양과 막는 분기를 본다."""

    def setUp(self):
        super().setUp()
        self.requests = []
        # 정해 둔 응답이다. (상태 코드, 본문 바이트, 답하기 전에 기다릴 초)
        self.answer = (200, b'{"decision": "allow"}', 0.0)
        test = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                test.requests.append({"path": self.path, "headers": dict(self.headers),
                                      "raw": raw, "body": json.loads(raw)})
                status, payload, delay = test.answer
                if delay:
                    test.release.wait(delay)
                try:
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                except OSError:
                    # 기다리다 끊은 쪽에는 쓸 수 없다. 늦은 답을 보는 검사에서만 온다.
                    pass

        self.release = threading.Event()
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = "http://127.0.0.1:%d/internal/hermes/connector-policy" % self.server.server_address[1]
        self.saved_env = os.environ.get("FOS_CTX_POLICY_URL")
        os.environ["FOS_CTX_POLICY_URL"] = self.url
        self.map_path = pathlib.Path(self.tmp.name) / ".fos-connector-tools.json"
        self.write_map()

    def tearDown(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        if self.saved_env is None:
            os.environ.pop("FOS_CTX_POLICY_URL", None)
        else:
            os.environ["FOS_CTX_POLICY_URL"] = self.saved_env
        super().tearDown()

    def write_map(self, value=TOOL_MAP):
        text = value if isinstance(value, str) else json.dumps(value, sort_keys=True, ensure_ascii=False) + "\n"
        self.map_path.write_text(text, encoding="utf-8")

    def answer_json(self, body, status=200):
        self.answer = (status, json.dumps(body, ensure_ascii=False).encode("utf-8"), 0.0)

    def assertBlocked(self, result, message):
        """막는 결과는 늘 비지 않은 글을 갖는다. Hermes 는 글이 없는 block 을 통과로 읽는다."""
        self.assertIsInstance(result, dict, "막아야 하는데 %r 를 돌려줬다" % (result,))
        self.assertEqual(result.get("action"), "block")
        self.assertIsInstance(result.get("message"), str)
        self.assertTrue(result["message"].strip(), "막는 글이 비었다")
        self.assertEqual(result["message"], message)
        self.assertEqual(set(result), {"action", "message"})

    def test_profile_without_tool_map_is_untouched(self):
        """대응 파일이 없는 profile 은 커넥터 도구를 건드리지 않고 서버를 부르지 않는다."""
        self.map_path.unlink()
        for tool in ("mcp__demo__write_note", "execute_code", "vision_analyze"):
            with self.subTest(tool=tool):
                self.assertIsNone(self.call(tool))
        self.assertEqual(self.requests, [])

    def test_allow_passes_with_signed_request(self):
        """서버가 allow 로 답하면 통과하고, 요청은 계약의 본문과 서명을 갖는다."""
        self.assertIsNone(self.call("mcp__demo__write_note", args={"text": "안녕"}))
        self.assertEqual(len(self.requests), 1)
        request = self.requests[0]
        self.assertEqual(request["path"], "/internal/hermes/connector-policy")
        self.assertEqual(request["headers"]["Authorization"], "Bearer " + FAKE_MCP_CREDENTIAL)
        args_json = '{"text":"안녕"}'
        self.assertEqual(request["body"], {
            "v": 1,
            "root_session_id": VECTOR_ROOT,
            "session_id": VECTOR_SESSION,
            "tool_call_id": VECTOR_CALL,
            "hermes_tool": "mcp__demo__write_note",
            "tool": "write_note",
            "args_json": args_json,
            "sig": self.plugin.sign_policy(VECTOR_KEY, "mcp__demo__write_note", VECTOR_ROOT, VECTOR_SESSION,
                                           VECTOR_CALL, args_json),
        })

    def test_block_with_message_is_passed_on(self):
        """서버가 글이 든 block 으로 답하면 그 글로 막는다."""
        self.answer_json({"decision": "block", "message": "막음"})
        self.assertEqual(self.call("mcp__demo__write_note"), {"action": "block", "message": "막음"})

    def test_unusable_answers_block_with_fixed_message(self):
        """글 없는 block, 모르는 판정, 200 이 아닌 답, JSON 이 아닌 답은 정해 둔 글로 막는다."""
        cases = {
            "block without message": (200, b'{"decision": "block"}'),
            "block with empty message": (200, b'{"decision": "block", "message": ""}'),
            "block with blank message": (200, b'{"decision": "block", "message": "  "}'),
            "block with non-string message": (200, b'{"decision": "block", "message": 1}'),
            "unknown decision": (200, b'{"decision": "maybe", "message": "x"}'),
            "no decision": (200, b'{}'),
            "upper-case allow": (200, b'{"decision": "ALLOW"}'),
            "not an object": (200, b'["allow"]'),
            "not json": (200, b'allow'),
            "empty body": (200, b''),
            "not utf-8": (200, b'\xff\xfe'),
            "allow with 201": (201, b'{"decision": "allow"}'),
            "allow with 403": (403, b'{"decision": "allow"}'),
            "allow with 500": (500, b'{"decision": "allow"}'),
        }
        for label, (status, payload) in cases.items():
            with self.subTest(label=label):
                self.requests.clear()
                self.answer = (status, payload, 0.0)
                self.assertBlocked(self.call("mcp__demo__write_note"), self.plugin.POLICY_BLOCK_MESSAGE)
                # 다시 부르지 않는다.
                self.assertEqual(len(self.requests), 1)

    def test_slow_server_blocks(self):
        """서버가 제한 시간보다 늦으면 막는다."""
        self.plugin.POLICY_TIMEOUT = 0.2
        self.answer = (200, b'{"decision": "allow"}', 5.0)
        self.assertBlocked(self.call("mcp__demo__write_note"), self.plugin.POLICY_BLOCK_MESSAGE)
        self.assertEqual(len(self.requests), 1)

    def test_unreachable_server_blocks(self):
        """서버에 붙지 못하면 막는다."""
        os.environ["FOS_CTX_POLICY_URL"] = "http://127.0.0.1:1/unreachable"
        self.assertBlocked(self.call("mcp__demo__write_note"), self.plugin.POLICY_BLOCK_MESSAGE)

    def test_missing_url_or_token_blocks_without_request(self):
        """주소나 토큰이 없으면 서버를 부르지 않고 막는다."""
        cases = {
            "no url": lambda: os.environ.pop("FOS_CTX_POLICY_URL", None),
            "blank url": lambda: os.environ.__setitem__("FOS_CTX_POLICY_URL", "  "),
            "no token": lambda: setattr(self, "token", None),
            "empty token": lambda: setattr(self, "token", ""),
        }
        for label, prepare in cases.items():
            with self.subTest(label=label):
                prepare()
                self.assertBlocked(self.call("mcp__demo__write_note"), self.plugin.POLICY_BLOCK_MESSAGE)
                self.assertEqual(self.requests, [])
                os.environ["FOS_CTX_POLICY_URL"] = self.url
                self.token = FAKE_MCP_CREDENTIAL

    def test_token_error_blocks_without_raising(self):
        """토큰을 읽다 예외가 나도 밖으로 던지지 않고 막으며, 예외 본문을 글과 로그에 넣지 않는다."""
        self.secret_error = RuntimeError("secret-detail")
        with self.assertLogs(self.plugin.logger, level="WARNING") as logs:
            result = self.call("mcp__demo__write_note")
        self.assertBlocked(result, self.plugin.POLICY_BLOCK_MESSAGE)
        self.assertNotIn("secret-detail", "\n".join(logs.output))
        self.assertEqual(self.requests, [])

    def test_undeclared_tool_is_asked_with_null_tool(self):
        """대응 파일에 없는 도구는 tool 을 null 로 묻고 답대로 한다."""
        self.answer_json({"decision": "block", "message": "선언하지 않은 도구"})
        self.assertBlocked(self.call("mcp__demo__hidden"), "선언하지 않은 도구")
        self.assertEqual(len(self.requests), 1)
        self.assertEqual(self.requests[0]["body"]["hermes_tool"], "mcp__demo__hidden")
        self.assertIn("tool", self.requests[0]["body"])
        self.assertIsNone(self.requests[0]["body"]["tool"])

    def test_server_holding_the_tool_wins_over_shorter_prefix(self):
        """서버 이름이 다른 서버 이름의 앞부분이어도 등록 이름을 가진 서버의 도구로 묻는다."""
        self.write_map({"v": 1, "servers": {
            "a": {"connector": "first", "prefix": "mcp__a__", "tools": {"mcp__a__y": "y"}},
            "a__b": {"connector": "second", "prefix": "mcp__a__b__", "tools": {"mcp__a__b__x": "x"}},
        }})
        self.assertIsNone(self.call("mcp__a__b__x"))
        self.assertIsNone(self.call("mcp__a__y"))
        self.assertEqual([(request["body"]["hermes_tool"], request["body"]["tool"]) for request in self.requests],
                         [("mcp__a__b__x", "x"), ("mcp__a__y", "y")])

    def test_args_over_limit_block_without_request(self):
        """인자 글이 상한과 같으면 묻고, 한 바이트 넘으면 서버를 부르지 않고 막는다."""
        limit = self.plugin.POLICY_ARGS_MAX_BYTES
        self.assertEqual(limit, 60 * 1024)
        # `{"text":""}` 가 11바이트다.
        self.assertIsNone(self.call("mcp__demo__write_note", args={"text": "a" * (limit - 11)}))
        self.assertEqual(len(self.requests[0]["body"]["args_json"].encode("utf-8")), limit)
        self.assertBlocked(self.call("mcp__demo__write_note", args={"text": "a" * (limit - 10)}),
                           "fos-ctx: 인자가 너무 커서 실행하지 않았다. 나눠서 요청한다.")
        # 글자 수가 아니라 바이트로 센다. 한글 한 글자는 3바이트다.
        self.assertBlocked(self.call("mcp__demo__write_note", args={"text": "가" * (limit // 3)}),
                           self.plugin.ARGS_TOO_LARGE_MESSAGE)
        self.assertEqual(len(self.requests), 1)

    def test_unknown_server_blocks_without_request(self):
        """prefix 가 맞는 서버가 없는 MCP 도구는 서버를 부르지 않고 막는다."""
        # 서버 이름이 앞부분만 같은 것도 다른 서버다.
        for tool in ("mcp__other__x", "mcp__demo_x__write_note", "mcp__demo", "mcp__"):
            with self.subTest(tool=tool):
                self.assertBlocked(self.call(tool), self.plugin.UNKNOWN_SERVER_MESSAGE)
        self.assertEqual(self.requests, [])

    def test_missing_context_blocks_without_request(self):
        """session, tool_call_id 가 비었거나 인자가 객체가 아니면 서버를 부르지 않고 막는다."""
        cases = {
            "no session": dict(session_id=""),
            "none session": dict(session_id=None),
            "no tool_call_id": dict(tool_call_id=""),
            "none tool_call_id": dict(tool_call_id=None),
            "list args": dict(args=["x"]),
            "string args": dict(args="{}"),
        }
        for label, override in cases.items():
            with self.subTest(label=label):
                kwargs = dict(tool_name="mcp__demo__write_note", args={}, session_id=VECTOR_SESSION,
                              tool_call_id=VECTOR_CALL)
                kwargs.update(override)
                self.assertBlocked(self.plugin.pre_tool_call(**kwargs), self.plugin.CONTEXT_BLOCK_MESSAGE)
        self.assertEqual(self.requests, [])

    def test_none_args_are_sent_as_empty_object(self):
        """인자가 None 이면 빈 객체로 묻는다."""
        self.assertIsNone(self.plugin.pre_tool_call(
            tool_name="mcp__demo__list_scopes", args=None, session_id=VECTOR_SESSION, tool_call_id=VECTOR_CALL))
        self.assertEqual(self.requests[0]["body"]["args_json"], "{}")

    def test_code_execution_is_blocked(self):
        """연결용 profile 에서 execute_code 는 서버를 부르지 않고 막는다."""
        self.assertBlocked(self.call("execute_code", args={"code": "x"}), self.plugin.CODE_EXECUTION_MESSAGE)
        self.assertEqual(self.requests, [])

    def test_unreadable_tool_map_blocks_mcp_tools_only(self):
        """대응 파일을 읽지 못하면 MCP 도구와 execute_code 만 막는다."""
        broken = {
            "broken json": "{",
            "not an object": "[]",
            "wrong version": json.dumps({"v": 2, "servers": {}}),
            "boolean version": json.dumps({"v": True, "servers": {}}),
            "no servers": json.dumps({"v": 1}),
            "servers not an object": json.dumps({"v": 1, "servers": []}),
            "server not an object": json.dumps({"v": 1, "servers": {"demo": "x"}}),
            "no prefix": json.dumps({"v": 1, "servers": {"demo": {"tools": {}}}}),
            "empty prefix": json.dumps({"v": 1, "servers": {"demo": {"prefix": "", "tools": {}}}}),
            "bare prefix": json.dumps({"v": 1, "servers": {"demo": {"prefix": "mcp__", "tools": {}}}}),
            "no tools": json.dumps({"v": 1, "servers": {"demo": {"prefix": "mcp__demo__"}}}),
            "tool not a string": json.dumps(
                {"v": 1, "servers": {"demo": {"prefix": "mcp__demo__", "tools": {"mcp__demo__x": 1}}}}),
        }
        for label, text in broken.items():
            with self.subTest(label=label):
                self.write_map(text)
                self.assertBlocked(self.call("mcp__demo__x"), self.plugin.POLICY_BLOCK_MESSAGE)
                self.assertBlocked(self.call("execute_code"), self.plugin.POLICY_BLOCK_MESSAGE)
                self.assertIsNone(self.call("vision_analyze"))
        with self.subTest(label="is a directory"):
            self.map_path.unlink()
            self.map_path.mkdir()
            self.assertBlocked(self.call("mcp__demo__x"), self.plugin.POLICY_BLOCK_MESSAGE)
            self.assertIsNone(self.call("vision_analyze"))
        self.assertEqual(self.requests, [])

    def test_other_tools_pass_on_connector_profile(self):
        """연결용 profile 에서도 MCP 가 아닌 도구는 서버를 부르지 않고 통과한다."""
        for tool in ("vision_analyze", "terminal"):
            with self.subTest(tool=tool):
                self.assertIsNone(self.call(tool))
        self.assertEqual(self.requests, [])

    def test_control_plane_tool_keeps_signed_context(self):
        """연결용 profile 에서도 Control Plane MCP 도구는 서명한 _fos_ctx 를 받고 정책 서버를 부르지 않는다."""
        result = self.call("mcp__fos_assistant__agent_list")
        self.assertEqual(result["action"], "modify")
        self.assertEqual(result["args"]["_fos_ctx"]["session_id"], VECTOR_SESSION)
        self.assertEqual(result["args"]["_fos_ctx"]["root_session_id"], VECTOR_ROOT)
        self.assertEqual(self.requests, [])

    def test_tool_map_server_wins_over_the_control_plane_prefix(self):
        """대응 파일의 서버 접두사가 Control Plane MCP 의 것과 같으면 그 도구는 _fos_ctx 를 받지 않고 정책 서버로 간다."""
        self.write_map({"v": 1, "servers": {"fos_assistant": {
            "connector": "impostor", "prefix": "mcp__fos_assistant__",
            "tools": {"mcp__fos_assistant__agent_delegate": "agent_delegate"},
        }}})
        self.answer_json({"decision": "block", "message": "정책이 막았다"})
        declared = self.call("mcp__fos_assistant__agent_delegate", args={"task": "x"})
        self.assertBlocked(declared, "정책이 막았다")
        # 대응 파일의 `tools` 에 없어도 접두사가 맞으면 정책 서버에 `tool` 을 null 로 묻는다.
        self.assertBlocked(self.call("mcp__fos_assistant__memory_read"), "정책이 막았다")
        self.assertEqual([(request["body"]["hermes_tool"], request["body"]["tool"]) for request in self.requests],
                         [("mcp__fos_assistant__agent_delegate", "agent_delegate"),
                          ("mcp__fos_assistant__memory_read", None)])
        # 통과로 답해도 인자를 고치지 않는다. 서명한 run 맥락이 커넥터 서버로 나가지 않는다.
        self.answer_json({"decision": "allow"})
        self.assertIsNone(self.call("mcp__fos_assistant__agent_delegate", args={"task": "x"}))

    def test_unreadable_tool_map_blocks_control_plane_tools_too(self):
        """대응 파일을 읽지 못하면 Control Plane MCP 의 접두사를 가진 도구도 막고 _fos_ctx 를 붙이지 않는다."""
        self.write_map("{")
        for tool in ("mcp__fos_assistant__agent_list", "mcp__fos_assistant__memory_read",
                     "mcp__fos_assistant__memory_search"):
            with self.subTest(tool=tool):
                self.assertBlocked(self.call(tool), self.plugin.POLICY_BLOCK_MESSAGE)
        self.assertEqual(self.requests, [])

    def test_profile_without_tool_map_signs_control_plane_tools(self):
        """대응 파일이 없는 profile 의 Control Plane MCP 도구는 지금처럼 서명한 _fos_ctx 를 받는다."""
        self.map_path.unlink()
        result = self.call("mcp__fos_assistant__agent_list")
        self.assertEqual(result["action"], "modify")
        self.assertEqual(result["args"]["_fos_ctx"]["session_id"], VECTOR_SESSION)
        self.assertEqual(result["args"]["_fos_ctx"]["root_session_id"], VECTOR_ROOT)
        self.assertEqual(self.requests, [])

    def test_skill_manage_stays_blocked(self):
        """연결용 profile 에서도 skill_manage 는 같은 글로 막는다."""
        self.assertBlocked(self.call("skill_manage", args={"action": "create"}), self.plugin.SKILL_MANAGE_MESSAGE)
        self.assertEqual(self.requests, [])

    def test_args_are_serialized_sorted_compact_and_unescaped(self):
        """인자 글은 키를 정렬하고 공백이 없고 한글을 그대로 둔다."""
        args = {"나": {"z": 1, "a": [1, {"다": "글 자"}]}, "가": "안녕", "b": None}
        self.assertIsNone(self.call("mcp__demo__write_note", args=args))
        expected = '{"b":null,"가":"안녕","나":{"a":[1,{"다":"글 자"}],"z":1}}'
        body = self.requests[0]["body"]
        self.assertEqual(body["args_json"], expected)
        self.assertEqual(body["sig"], self.plugin.sign_policy(
            VECTOR_KEY, "mcp__demo__write_note", VECTOR_ROOT, VECTOR_SESSION, VECTOR_CALL, expected))

    def test_unserializable_args_block(self):
        """직렬화할 수 없는 인자는 예외를 던지지 않고 막는다."""
        self.assertBlocked(self.call("mcp__demo__write_note", args={"x": object()}),
                           self.plugin.POLICY_BLOCK_MESSAGE)
        self.assertEqual(self.requests, [])

    def test_missing_state_db_uses_session_as_root(self):
        """state DB 가 없으면 그 session 을 루트로 묻는다."""
        self.db.unlink()
        self.assertIsNone(self.call("mcp__demo__list_scopes"))
        self.assertEqual(self.requests[0]["body"]["root_session_id"], VECTOR_SESSION)

    def test_logs_hide_token_signature_and_args(self):
        """토큰, 서명, 인자, 응답 본문을 로그에 남기지 않는다."""
        self.answer_json({"decision": "block", "message": "응답-본문-글"}, status=500)
        with self.assertLogs(self.plugin.logger, level="INFO") as logs:
            self.call("mcp__demo__write_note", args={"text": "인자-비밀"})
        log = "\n".join(logs.output)
        for secret in (FAKE_MCP_CREDENTIAL, self.requests[0]["body"]["sig"], "인자-비밀", "응답-본문-글"):
            self.assertNotIn(secret, log)


if __name__ == "__main__":
    unittest.main(verbosity=2)
