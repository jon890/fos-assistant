"""Java loopback에 현재 Python producer와 실제 TS compiler의 합성 응답을 전달한다."""

import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / "hermes/tests"))
import test_connector_snapshot as fixture
from mcp.types import CallToolResult, TextContent

case = fixture.ConnectorSnapshotTest("test_real_sdk_compile_and_catalog_use_the_same_declaration")
try:
    case.setUpClass()
    case.setUp()
    bun = shutil.which("bun")
    if bun is None:
        raise RuntimeError("Bun이 필요합니다.")
    script = (
        'import {compileContentDraft} from "./src/content-compiler.ts";'
        'const input = await Bun.stdin.json();'
        'process.stdout.write(JSON.stringify(compileContentDraft(input.draft,input.assets)));'
    )
    compiled = subprocess.run(
        [bun, "--no-env-file", "-e", script], cwd=fixture.CONNECTOR,
        input=case.module._json_bytes(case.golden), stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, env={"PATH": os.path.dirname(bun)}, timeout=10, check=True,
    )
    snapshot = case.module._snapshot_tool_result(CallToolResult(
        content=[TextContent(type="text", text=compiled.stdout.decode("utf-8"))],
        structuredContent=json.loads(compiled.stdout),
    ))
    if snapshot != case.golden["snapshot"]:
        raise RuntimeError("현재 compiler golden이 다릅니다.")
    payload = snapshot["payload"]
    payload_tuple = [payload["title"], payload["category"], payload["tags"], payload["body"], payload.get("photo_dir")]
    payload_bytes = case.module._json_bytes(payload_tuple)
    if hashlib.sha256(payload_bytes).hexdigest() != snapshot["payloadFingerprint"]:
        raise RuntimeError("Python payload 해시가 다릅니다.")
    catalog = json.loads(case.plugin._connector_catalog_response().body)
    sys.stdout.buffer.write(case.module._json_bytes({
        "catalog": catalog,
        "answer": {"ok": True, "result": snapshot, "capabilities": fixture.SNAPSHOT["capabilities"]},
        "payloadHex": payload_bytes.hex(),
    }))
finally:
    case.doCleanups()
    case.tearDownClass()
