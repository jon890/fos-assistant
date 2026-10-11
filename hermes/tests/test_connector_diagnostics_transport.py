"""실제 SDK task와 ASGI compile의 본문 없는 진단을 검사한다."""

import asyncio
import contextlib
import copy
import importlib
import json
import logging
import os
import shutil
import subprocess
import sys
import unittest
from unittest import mock

import test_connector_snapshot as snapshot_base
from test_connector_diagnostics import fresh_install

MARK = "SYNTHETIC_BODY_79X"
NAMES = ("mcp.client.stdio", "client", "mcp.shared.jsonrpc_dispatcher")


class Capture(logging.Handler):
    def __init__(self):
        super().__init__()
        self.lines = []
        self.records = []

    def emit(self, record):
        self.lines.append(self.format(record))
        self.records.append(copy.copy(record))


class DiagnosticsTransportTest(snapshot_base.ConnectorSnapshotTest):
    def setUp(self):
        super().setUp()
        self.diagnostics = sys.modules[self.plugin.__name__ + ".connector_diagnostics"]
        self.previous_disable = logging.root.manager.disable
        self.previous_level = logging.getLogger().level
        logging.disable(logging.NOTSET)
        logging.getLogger().setLevel(logging.DEBUG)
        self.handlers = []
        for name in ("", "", "mcp", "mcp.client.stdio", "client", "mcp.shared"):
            handler = Capture()
            logging.getLogger(name).addHandler(handler)
            self.handlers.append((name, handler))
        self.addCleanup(self.restore_logging)

    def restore_logging(self):
        for name, handler in self.handlers:
            logging.getLogger(name).removeHandler(handler)
        logging.getLogger().setLevel(self.previous_level)
        logging.disable(self.previous_disable)

    def reset_logs(self):
        for _, handler in self.handlers:
            handler.lines.clear()
            handler.records.clear()

    def exposed(self):
        return any(MARK in line for _, handler in self.handlers for line in handler.lines)

    def child(self, mode):
        program = """import readline from 'node:readline';
const mode=MODE, marker=MARKER;
const rl=readline.createInterface({input:process.stdin});
rl.on('line',line=>{const q=JSON.parse(line); if(!('id' in q))return;
if(mode==='parse'){console.log(marker);return;}
if(mode==='validation'){console.log(JSON.stringify({jsonrpc:'2.0',id:{body:marker},result:{}}));return;}
let result;
if(q.method==='initialize')result={protocolVersion:q.params.protocolVersion,capabilities:{tools:{}},serverInfo:{name:'synthetic',version:'1'}};
else if(q.method==='tools/list')result={tools:[{name:'compile_content_snapshot',inputSchema:{type:'object'},annotations:{readOnlyHint:true}}]};
else if(q.method==='tools/call'){
 if(mode==='parallel' && q.params.arguments.canonicalDraft.title==='fail'){console.log(marker);return;}
 if(mode==='notification')console.log(JSON.stringify({jsonrpc:'2.0',method:'notifications/progress',params:{progressToken:marker,progress:{body:marker}}}));
 if(mode==='rpcerror'){console.log(JSON.stringify({jsonrpc:'2.0',id:q.id,error:{code:-32603,message:marker,data:{body:marker}}}));return;}
 result=mode==='toolerror'?{content:[{type:'text',text:marker}],isError:true}:{content:[{type:'text',text:JSON.stringify({body:'safe'})}],structuredContent:{body:'safe'}};
}else result={};
console.log(JSON.stringify({jsonrpc:'2.0',id:q.id,result}));});"""
        (self.connector_root / "snapshot.js").write_text(
            program.replace("MODE", json.dumps(mode)).replace("MARKER", json.dumps(MARK)))

    def reaped(self):
        child = subprocess.run(["pgrep", "-f", str(self.connector_root / "snapshot.js")],
                               capture_output=True, text=True)
        self.assertEqual(child.stdout.strip(), "")

    def test_notification_body_is_omitted(self):
        self.child("notification")
        self.assertEqual(self.call()[0], 200)
        self.assertFalse(self.exposed())
        self.reaped()

    def test_actual_sdk_before_and_after_matrix(self):
        normal = []
        for mode in ("parse", "validation", "notification", "rpcerror", "toolerror", "normal"):
            for enabled in (False, True):
                self.child(mode)
                self.reset_logs()
                scope = self.diagnostics.snapshot_diagnostics_scope if enabled else contextlib.nullcontext
                with mock.patch.object(self.module, "snapshot_diagnostics_scope", scope):
                    status, answer = self.call()
                self.assertNotIn(MARK, json.dumps(answer))
                self.reaped()
                self.assertEqual(self.exposed(), not enabled and mode in ("parse", "validation", "notification"))
                if mode in ("parse", "validation"):
                    self.assertEqual((status, answer), (504, {"ok": False, "error": "snapshot 시간이 초과됐다"}))
                elif mode in ("rpcerror", "toolerror"):
                    self.assertEqual((status, answer), (503, {"ok": False, "error": "unavailable"}))
                else:
                    self.assertEqual(status, 200)
                if mode == "normal":
                    normal.append(self.module._json_bytes(answer))
                records = [record for _, handler in self.handlers for record in handler.records
                           if record.name in NAMES]
                if mode in ("parse", "validation", "notification"):
                    level = logging.WARNING if mode == "notification" else logging.ERROR
                    self.assertTrue(any(record.levelno == level for record in records))
                # 동일 record가 모든 handler에 도달하며 수와 등급은 유지된다.
                if not enabled:
                    before = [(name, [(r.name, r.levelno) for r in h.records if r.name in NAMES])
                              for name, h in self.handlers]
                else:
                    self.assertEqual(before, [(name, [(r.name, r.levelno) for r in h.records if r.name in NAMES])
                                              for name, h in self.handlers])
        self.assertEqual(normal[0], normal[1])

    def test_parallel_and_outside_logger(self):
        self.child("parse")

        async def two():
            return await asyncio.gather(self.http(), self.http())
        self.assertEqual([value[0] for value in asyncio.run(two())], [504, 504])
        self.assertFalse(self.exposed())
        self.reaped()
        self.child("parallel")
        bad = copy.deepcopy(self.body)
        bad["args"]["canonicalDraft"]["title"] = "fail"
        for bodies in ((bad, self.body), (self.body, bad)):
            self.reset_logs()

            async def mixed():
                return await asyncio.gather(*(self.http(body) for body in bodies))
            self.assertEqual([value[0] for value in asyncio.run(mixed())],
                             [504 if body is bad else 200 for body in bodies])
            self.assertFalse(self.exposed())
            self.reaped()
        self.reset_logs()
        logging.getLogger("client").warning(MARK)
        self.assertTrue(self.exposed())
        self.reset_logs()
        with self.diagnostics.snapshot_diagnostics_scope():
            logging.getLogger("unrelated").error(MARK)
        self.assertTrue(self.exposed())
        self.child("notification")
        self.reset_logs()

        async def outside_during_compile():
            import mcp.client.stdio
            original = mcp.client.stdio.stdio_client
            ready, proceed = asyncio.Event(), asyncio.Event()

            @contextlib.asynccontextmanager
            async def transport(*args, **kwargs):
                async with original(*args, **kwargs) as streams:
                    self.assertTrue(self.diagnostics.ensure_snapshot_diagnostics().active.get())
                    ready.set()
                    await proceed.wait()
                    yield streams
            with mock.patch.object(mcp.client.stdio, "stdio_client", transport):
                task = asyncio.create_task(self.http())
                await ready.wait()
                logging.getLogger("client").warning(MARK)
                self.assertFalse(self.diagnostics.ensure_snapshot_diagnostics().active.get())
                proceed.set()
                return await task
        self.assertEqual(asyncio.run(outside_during_compile())[0], 200)
        client = next(h for name, h in self.handlers if name == "client")
        self.assertEqual(sum(MARK in line for line in client.lines), 1)

    def test_actual_session_callback_exception(self):
        from mcp import ClientSession, StdioServerParameters
        from mcp.client.stdio import stdio_client
        self.child("parse")

        async def run(enabled):
            async def handler(message):
                raise ValueError(MARK)
            params = StdioServerParameters(command=shutil.which("bun"),
                                           args=[str(self.connector_root / "snapshot.js")],
                                           env={"PATH": os.path.dirname(shutil.which("bun"))})
            scope = self.diagnostics.snapshot_diagnostics_scope if enabled else contextlib.nullcontext
            with scope(), open(os.devnull, "w") as sink:
                async with stdio_client(params, errlog=sink) as (read, write):
                    async with ClientSession(read, write, message_handler=handler) as session:
                        with self.assertRaises(asyncio.TimeoutError):
                            await asyncio.wait_for(session.initialize(), .3)
        for enabled in (False, True):
            self.reset_logs()
            asyncio.run(run(enabled))
            self.reaped()
            self.assertEqual(self.exposed(), not enabled)
            self.assertTrue(any(r.name == "client" and r.levelno == logging.ERROR
                                for _, h in self.handlers for r in h.records))

    def test_cancel_cleanup_scope_reset_and_reuse(self):
        self.child("parse")
        import mcp.client.stdio
        original = mcp.client.stdio.stdio_client
        ready = asyncio.Event()
        states = []

        @contextlib.asynccontextmanager
        async def transport(*args, **kwargs):
            try:
                async with original(*args, **kwargs) as streams:
                    state = self.diagnostics.ensure_snapshot_diagnostics()
                    importlib.reload(self.diagnostics)
                    self.assertIs(self.diagnostics.ensure_snapshot_diagnostics(), state)
                    ready.set()
                    yield streams
            finally:
                state = self.diagnostics.ensure_snapshot_diagnostics()
                states.append(state.active.get())
                logging.getLogger("client").error(MARK)

        async def run():
            task = asyncio.create_task(self.http())
            await ready.wait()
            await asyncio.sleep(.05)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            self.assertFalse(self.diagnostics.ensure_snapshot_diagnostics().active.get())
        with mock.patch.object(mcp.client.stdio, "stdio_client", transport):
            asyncio.run(run())
        self.assertEqual(states, [True])
        self.assertFalse(self.exposed())
        self.reaped()
        self.child("normal")
        self.assertEqual(self.call()[0], 200)
        logging.getLogger("client").warning(MARK)
        self.assertTrue(self.exposed())

    def test_contract_failure_is_fixed_503_without_child_spawn(self):
        helper = self.diagnostics
        import mcp.client.stdio
        state = helper.ensure_snapshot_diagnostics()
        for change in ("version", "hash", "added", "deleted", "unknown-filter", "policy", "logger-name"):
            with contextlib.ExitStack() as stack:
                if change in ("version", "hash", "added", "deleted", "unknown-filter"):
                    loggers = stack.enter_context(fresh_install(helper))
                    if change == "version":
                        stack.enter_context(mock.patch.object(helper.importlib.metadata, "version", return_value="2.0.1"))
                    elif change == "unknown-filter":
                        loggers[0].addFilter(logging.Filter())
                    else:
                        manifest = json.loads(helper._MANIFEST.read_bytes())
                        if change == "hash":
                            manifest["client/stdio.py"] = "0" * 64
                        elif change == "added":
                            manifest["new_emission.py"] = "0" * 64
                        else:
                            del manifest["client/stdio.py"]
                        file = self.base / "changed-source.json"
                        file.write_text(json.dumps(manifest))
                        stack.enter_context(mock.patch.object(helper, "_MANIFEST", file))
                elif change == "policy":
                    stack.enter_context(mock.patch.object(state.guard, "policy", "other-policy"))
                else:
                    stack.enter_context(mock.patch.object(state.loggers[0], "name", "other"))
                spawn = stack.enter_context(mock.patch.object(mcp.client.stdio, "stdio_client"))
                self.assertEqual(self.call(), (503, {"ok": False, "error": "unavailable"}))
                spawn.assert_not_called()


def load_tests(loader, tests, pattern):
    # 기존 snapshot 시험은 원래 모듈에서 실행한다. 여기서는 fixture만 상속한다.
    return unittest.TestSuite(DiagnosticsTransportTest(name) for name in sorted(
        DiagnosticsTransportTest.__dict__) if name.startswith("test_"))


if __name__ == "__main__":
    unittest.main()
