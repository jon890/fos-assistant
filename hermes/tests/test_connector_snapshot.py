"""합성 원고로 실제 Python 2.0.0·TS 1.28.0 SDK와 private HTTP 경계를 검사한다."""

import asyncio
import copy
import hashlib
import importlib.metadata
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
import types
import unittest
from unittest import mock

import httpx2 as httpx
from mcp.types import CallToolResult, TextContent
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route
import test_connector_manifest as base
from test_connector_call import collected_logs

REPO = pathlib.Path(__file__).resolve().parents[2]
CONNECTOR = REPO / "hermes/connectors/naver-blog"
SNAPSHOT = {"protocol": "content-snapshot-v1", "compile_tool": "compile_content_snapshot",
            "start_tool": "start_content_snapshot", "status_tool": "content_snapshot_status",
            "capabilities": {"compile": True, "start": False, "status": False}}
PATH = "/api/connectors/demo-notes/snapshot"


class ConnectorSnapshotTest(base.ConnectorGateCase):
    def setUp(self):
        super().setUp()
        self.module = sys.modules[self.plugin.__name__ + ".connector_snapshot"]
        self.rewrite("connector.json", lambda value: value.update(snapshot=copy.deepcopy(SNAPSHOT)))
        bundle = CONNECTOR / "dist/naver-blog-mcp.js"
        self.assertTrue(bundle.is_file(), "먼저 bun run build를 실행한다")
        shutil.copyfile(bundle, self.connector_root / "snapshot.js")
        self.rewrite(".mcp.json", lambda value: value["mcpServers"]["demo"].update(
            args=["${CLAUDE_PLUGIN_ROOT}/snapshot.js"]))
        self.operator_list({base.DEMO: {"root": str(self.connector_root), "command": shutil.which("bun"),
                                       "env": {"DEMO_BASE": base.DEMO_BASE}}})
        self.profile = self.base / "profiles/alice"
        self.profile.mkdir(parents=True)
        profiles = types.ModuleType("hermes_cli.profiles")
        profiles.get_profile_dir = lambda name: self.profile.parent / name
        profiles.profile_exists = lambda name: (self.profile.parent / name).is_dir()
        patch = mock.patch.dict(sys.modules, {"hermes_cli.profiles": profiles})
        patch.start()
        self.addCleanup(patch.stop)
        (self.profile / self.plugin.MANAGED_MARKER).touch()
        (self.profile / ".env").write_text("DEMO_TOKEN=connector-only\nOTHER_TOKEN=profile-only\n")
        self.install_state()
        self.golden = json.loads((CONNECTOR / "tests/fixtures/content-snapshot-v1.json").read_text())
        self.args = {"canonicalDraft": {**self.golden["draft"], "blocks": []}, "resolvedAssets": []}
        self.body = {"profile": "alice", "operation": "compile", "args": self.args}

    def install_state(self, bind=False):
        manifest = self.plugin._connector_manifest(base.DEMO)
        self.assertIsNotNone(manifest)
        entry = {"server": self.plugin._connector_server(manifest), "allowlist_added": True,
                 "mcp_server": manifest["mcp_server"]}
        if bind:
            entry.update(mode="bind", vault="c1", skills=["demo"])
        (self.profile / self.plugin.CONNECTOR_STATE).write_text(json.dumps({base.DEMO: entry}))

    async def http(self, body=None, *, raw=None, token="valid", cookie=False, method="POST", path=PATH):
        async def handle(request):
            request.token = request.headers.get("authorization", "").removeprefix("Bearer ")

            async def next_handler(current):
                return JSONResponse({"cookie": True}, status_code=200 if cookie else 401)
            return await self.gate(request, next_handler)

        app = Starlette(routes=[Route("/{rest:path}", handle, methods=["GET", "POST", "PUT"])])
        headers = {"Authorization": "Bearer " + token} if token else {}
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://snapshot.test") as client:
            response = await client.request(method, path, headers=headers,
                                            content=raw if raw is not None else self.module._json_bytes(body or self.body))
        return response.status_code, response.json()

    def call(self, body=None, **kwargs):
        return asyncio.run(self.http(body, **kwargs))

    def test_real_sdk_compile_and_catalog_use_the_same_declaration(self):
        self.assertEqual(importlib.metadata.version("mcp"), "2.0.0")
        self.assertEqual(json.loads((CONNECTOR / "package.json").read_text())["dependencies"]
                         ["@modelcontextprotocol/sdk"], "1.28.0")
        status, answer = self.call()
        self.assertEqual(status, 200, answer)
        self.assertEqual(answer["capabilities"], SNAPSHOT["capabilities"])
        result = answer["result"]
        expected = copy.deepcopy(self.golden["snapshot"])
        expected.update(canonicalBlocks=[], assetManifest=[], photoNotes={})
        expected["payload"].update(body="")
        expected["payload"].pop("photo_dir", None)
        payload = expected["payload"]
        sha = lambda value: hashlib.sha256(self.module._json_bytes(value)).hexdigest()
        expected["payloadFingerprint"] = sha([payload["title"], payload["category"], payload["tags"], "", None])
        expected["snapshotHash"] = sha([1, "content-compiler-v1", expected["draftId"], expected["revision"],
                                        [], [], expected["payloadFingerprint"], []])
        self.assertEqual(result, expected)
        catalog_status, catalog_entries = asyncio.run(self.http(path=base.CATALOG, method="GET"))
        self.assertEqual(catalog_status, 200)
        catalog = catalog_entries[0]
        self.assertEqual(catalog["snapshot"], SNAPSHOT)
        self.assertNotIn(SNAPSHOT["compile_tool"], catalog["tools"])
        # 부모·다른 profile의 비밀 env는 실제 stdio 인자로 들어가지 않는다.
        import mcp.client.stdio
        original = mcp.client.stdio.stdio_client
        captured = []

        def capture(params, **kwargs):
            captured.append(params)
            return original(params, **kwargs)
        with mock.patch.object(mcp.client.stdio, "stdio_client", side_effect=capture), mock.patch.dict(
                os.environ, {"FOS_CONTENT_SNAPSHOT_OPERATION": "start", "FOS_CONTENT_SAVE_TICKET": "parent-ticket"}):
            self.assertEqual(self.call()[0], 200)
        self.assertEqual(captured[0].args[-2:], ["--content-snapshot-operation", "compile"])
        self.assertEqual(set(captured[0].env), {"PATH", "DEMO_TOKEN", "DEMO_SCOPE", "DEMO_BASE"})
        self.assertFalse(self.module.SNAPSHOT_ENV & set(captured[0].env))

    def test_auth_install_unsupported_and_invalid_manifest_never_spawn(self):
        with mock.patch.object(self.module, "_run_connector_snapshot", new_callable=mock.AsyncMock) as runner:
            for token, cookie in [(None, False), ("wrong", False), (None, True), ("wrong", True)]:
                self.assertEqual(self.call(token=token, cookie=cookie)[0], 401)
            for operation in ["start", "status", "unknown"]:
                self.assertEqual(self.call({**self.body, "operation": operation})[0], 409)
            self.assertEqual(self.call({**self.body, "profile": "missing"})[0], 404)
            (self.profile / self.plugin.CONNECTOR_STATE).unlink()
            self.assertEqual(self.call()[0], 404)
            self.rewrite("connector.json", lambda value: value["snapshot"].update(extra=True))
            self.assertEqual(self.call()[0], 404)
            runner.assert_not_awaited()

    def test_exact_post_route_keeps_other_cookie_routes(self):
        self.assertEqual(self.call(method="GET", token="valid")[0], 401)
        self.assertEqual(self.call(method="PUT", token="valid")[0], 401)
        self.assertEqual(self.call(method="GET", cookie=True, token=None)[0], 200)
        self.assertEqual(self.call(path=PATH + "/nested", token="valid")[0], 401)

    def test_chunked_envelope_cap_is_checked_before_json_parse(self):
        async def read(size):
            async def stream():
                for index in range(0, size, 8192):
                    yield b" " * min(8192, size - index)
            return await self.module._snapshot_body(types.SimpleNamespace(stream=stream))

        for size in [524287, 524288, 524289]:
            with mock.patch.object(self.module, "_strict_json", return_value={}) as parser:
                if size <= 524288:
                    self.assertEqual(asyncio.run(read(size)), {})
                    parser.assert_called_once()
                else:
                    with self.assertRaises(ValueError):
                        asyncio.run(read(size))
                    parser.assert_not_called()
        with mock.patch.object(self.module, "_run_connector_snapshot", new_callable=mock.AsyncMock) as runner:
            self.assertEqual(self.call(raw=b" " * 524289)[0], 400)
            self.assertEqual(self.call(raw=b'{"profile":"alice","profile":"alice"}')[0], 400)
            runner.assert_not_awaited()

    def test_private_flags_are_rejected_in_profile_and_public_manifest(self):
        for name in self.module.SNAPSHOT_ENV:
            (self.profile / ".env").write_text("export %s=\n" % name)
            with mock.patch.object(self.module, "_run_connector_snapshot", new_callable=mock.AsyncMock) as runner:
                self.assertEqual(self.call()[0], 401)
                runner.assert_not_awaited()
        (self.profile / ".env").write_text("")
        self.rewrite(".mcp.json", lambda value: value["mcpServers"]["demo"]["args"].extend(
            ["--content-snapshot-operation", "compile"]))
        self.assertIsNone(self.plugin._connector_manifest(base.DEMO))

    def test_declared_private_tool_mismatch_is_rejected_by_actual_list_tools(self):
        self.rewrite("connector.json", lambda value: value["snapshot"].update(compile_tool="wrong_compile"))
        self.install_state()
        status, answer = self.call()
        self.assertEqual((status, answer), (503, {"ok": False, "error": "unavailable"}))

    def test_deadline_and_errors_return_no_args_cause_or_stderr(self):
        async def slow(*args):
            await asyncio.sleep(60)
        real_wait = asyncio.wait_for
        deadlines = []

        async def shortened(future, seconds):
            deadlines.append(seconds)
            return await real_wait(future, 0.01)
        with mock.patch.object(self.module, "_run_connector_snapshot", side_effect=slow), mock.patch.object(
                self.module.asyncio, "wait_for", new=shortened):
            status, body = self.call()
            self.assertEqual(status, 504)
            self.assertEqual(deadlines, [5])
        with mock.patch.object(self.module, "_run_connector_snapshot", side_effect=RuntimeError("secret-body-stderr")):
            self.assertEqual(self.call(), (503, {"ok": False, "error": "unavailable"}))
        self.assertNotIn("secret", json.dumps(body))

    def test_unique_assets_path_and_draft_byte_caps_reject_before_child(self):
        with mock.patch.object(self.module, "_run_connector_snapshot", new_callable=mock.AsyncMock) as runner:
            asset = self.golden["assets"][0]
            for assets in [[asset, asset], [{**asset, "assetId": "0"}], [{**asset, "assetId": "9" * 20}],
                           [{**asset, "photoDir": "/" + "a" * 4096}],
                           [{**asset, "fileName": "a" * 252 + ".jpg"}],
                           [{**asset, "assetId": str(index + 1)} for index in range(51)]]:
                self.assertEqual(self.call({**self.body, "args": {**self.args, "resolvedAssets": assets}})[0], 400)
            draft = {**self.args["canonicalDraft"], "blocks": [{"type": "paragraph", "text": "😀" * 17000}]}
            self.assertEqual(self.call({**self.body, "args": {**self.args, "canonicalDraft": draft}})[0], 400)
            runner.assert_not_awaited()

    def test_compiled_response_byte_gate_includes_serialized_duplicates_and_escapes(self):
        # compiler 의미 검사가 끝난 뒤 transport가 다시 검사하는 직렬화 경계다.
        for size in (262143, 262144, 262145):
            result = {"body": "a" * (size - len(self.module._json_bytes({"body": ""})))}
            self.assertEqual(len(self.module._json_bytes(result)), size)
            with mock.patch.object(self.module, "_run_connector_snapshot", return_value=result):
                self.assertEqual(self.call()[0], 200 if size <= 262144 else 503)
        for text in ("😀" * 66000, "\u0001" * 44000):
            with mock.patch.object(self.module, "_run_connector_snapshot", return_value={"body": text}):
                self.assertEqual(self.call()[0], 503)

    def test_mcp_envelope_cap_precedes_text_json_parse_and_rejects_mismatches(self):
        empty = CallToolResult(content=[TextContent(type="text", text="")])
        overhead = len(empty.model_dump_json(by_alias=True).encode("utf-8"))
        for size in (524287, 524288, 524289):
            result = CallToolResult(content=[TextContent(type="text", text=" " * (size - overhead))])
            self.assertEqual(len(result.model_dump_json(by_alias=True).encode("utf-8")), size)
            with mock.patch.object(self.module, "_strict_json", return_value={}) as parser:
                if size <= 524288:
                    self.assertEqual(self.module._snapshot_tool_result(result), {})
                    parser.assert_called_once()
                else:
                    with self.assertRaises(ValueError):
                        self.module._snapshot_tool_result(result)
                    parser.assert_not_called()
        for result in [CallToolResult(content=[TextContent(type="text", text='{"body":"one"}')],
                                      structuredContent={"body": "two"}),
                       CallToolResult(content=[TextContent(type="text", text='{"revision":1}')],
                                      structuredContent={"revision": True}),
                       CallToolResult(content=[TextContent(type="text", text='{"body":1,"body":2}')]),
                       CallToolResult(content=[], isError=True, structuredContent={"error": "secret-stderr"})]:
            with self.assertRaises(ValueError):
                self.module._snapshot_tool_result(result)

    def test_actual_sdk_timeout_discards_stderr_and_reaps_the_synthetic_child(self):
        # protocol에 답하지 않는 합성 자식이다. 실제 provider나 개인 데이터는 사용하지 않는다.
        (self.connector_root / "snapshot.js").write_text(
            'console.error("private-stderr-must-not-leak"); await Bun.sleep(30000);')
        started = time.monotonic()
        with collected_logs() as logs:
            status, answer = self.call()
        self.assertEqual(status, 504, answer)
        self.assertGreaterEqual(time.monotonic() - started, 5)
        self.assertLess(time.monotonic() - started, 8)
        self.assertNotIn("private-stderr-must-not-leak", json.dumps(answer) + "\n".join(logs))
        children = subprocess.run(["pgrep", "-f", str(self.connector_root / "snapshot.js")],
                                  capture_output=True, text=True)
        self.assertEqual(children.stdout.strip(), "", "timeout 뒤 자식이 남았다")


if __name__ == "__main__":
    unittest.main()
