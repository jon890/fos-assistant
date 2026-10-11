"""관리 cron 접점의 source와 worker 분리 실행을 확인한다. 저장·profile 적재는 대역이다."""

import ast
import contextlib
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import types
from typing import Any
import unittest
from unittest.mock import patch

import hermes_contract as contract
from test_bodyless_managed_contract import check_function, definition


def check_job_binding(tree: ast.Module):
    bindings = []

    class JobBinding(ast.NodeVisitor):
        def visit_Name(self, node: ast.Name):
            if node.id == "run_one_job" and isinstance(node.ctx, (ast.Store, ast.Del)):
                bindings.append(node)

        def visit_ImportFrom(self, node: ast.ImportFrom):
            assert all(alias.name != "*" for alias in node.names)
            bindings.extend(alias for alias in node.names if (alias.asname or alias.name) == "run_one_job")

        def visit_Import(self, node: ast.Import):
            bindings.extend(alias for alias in node.names
                if (alias.asname or alias.name.split(".")[0]) == "run_one_job")

        def visit_FunctionDef(self, node: ast.FunctionDef):
            if node.name == "run_one_job":
                bindings.append(node)

        def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef):
            if node.name == "run_one_job":
                bindings.append(node)

        def visit_ClassDef(self, node: ast.ClassDef):
            if node.name == "run_one_job":
                bindings.append(node)

    JobBinding().visit(tree)
    assert len(bindings) == 1 and isinstance(bindings[0], ast.FunctionDef), "cron job의 module binding 변경"


@unittest.skipUnless(os.environ.get("HERMES_SOURCE"), "HERMES_SOURCE가 없어 cron source 검사를 건너뛴다")
class ManagedCronSourceTest(unittest.TestCase):
    def tree(self):
        return ast.parse((Path(os.environ["HERMES_SOURCE"]) / "cron/scheduler.py").read_bytes())

    def test_current_module_job_binding(self):
        check_job_binding(self.tree())

    def test_job_binding_and_worker_counterexamples_are_rejected(self):
        for source in ("run_one_job = replacement", "from other_module import run_one_job",
                "def run_one_job(job): return True", "if True:\n    run_one_job = replacement"):
            tree = self.tree()
            tree.body.extend(ast.parse(source).body)
            with self.subTest(source=source), self.assertRaises(AssertionError):
                check_job_binding(tree)
        for mutation in ("snapshot", "local_alias", "early_job", "false_to_true", "ack"):
            tree = self.tree()
            node = definition(tree, "_run_external_worker_payload")
            assert isinstance(node, ast.FunctionDef)
            if mutation == "snapshot":
                read = next(item for item in ast.walk(node) if isinstance(item, ast.Attribute)
                    and item.attr == "read_text")
                read.value = ast.Call(func=ast.Name(id="Path", ctx=ast.Load()),
                    args=[ast.Name(id="payload_path", ctx=ast.Load())], keywords=[])
            elif mutation == "local_alias":
                node.body.insert(0, ast.parse("from cron.scheduler import run_one_job").body[0])
            elif mutation == "early_job":
                node.body.insert(0, ast.parse("run_one_job({})").body[0])
            elif mutation == "false_to_true":
                result = next(item for item in ast.walk(node) if isinstance(item, ast.Return)
                    and isinstance(item.value, ast.Call) and isinstance(item.value.func, ast.Name)
                    and item.value.func.id == "run_one_job")
                result.value = ast.Constant(value=True)
            else:
                replace = next(item for item in ast.walk(node) if isinstance(item, ast.Attribute)
                    and item.attr == "replace")
                replace.attr = "discard_ack"
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                check_function(tree, "_run_external_worker_payload",
                    contract.BODYLESS_MANAGED_FUNCTIONS["cron/scheduler.py"]["_run_external_worker_payload"])

    def test_job_signature_counterexamples_are_rejected(self):
        for mutation in ("name", "positional", "default", "annotation", "return", "body"):
            tree = self.tree()
            node = definition(tree, "run_one_job")
            assert isinstance(node, ast.FunctionDef)
            if mutation == "name":
                node.args.args[0].arg = "other_job"
            elif mutation == "positional":
                node.args.args.extend(node.args.kwonlyargs)
                node.args.defaults.extend(value for value in node.args.kw_defaults if value is not None)
                node.args.kwonlyargs, node.args.kw_defaults = [], []
            elif mutation == "default":
                node.args.kw_defaults[2] = ast.Constant(value=True)
            elif mutation == "annotation":
                node.args.args[0].annotation = ast.Name(id="OtherJob")
            elif mutation == "return":
                node.returns = ast.Name(id="OtherResult")
            else:
                node.body.append(ast.Pass())
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                check_function(tree, "run_one_job", contract.BODYLESS_MANAGED_FUNCTIONS["cron/scheduler.py"]["run_one_job"])

    def test_actual_worker_snapshot_adoption_atomic_ack_and_failure_result(self):
        for adopted, ack_ok, job_result in ((True, True, True), (True, True, False),
                (False, True, True), (True, False, True)):
            with self.subTest(adopted=adopted, ack_ok=ack_ok, job_result=job_result):
                self.run_worker(adopted=adopted, ack_ok=ack_ok, job_result=job_result)

    def test_worker_execution_counterexamples_fail_behavior_checks(self):
        for mutation in ("snapshot", "before_adoption", "before_ack", "false_to_true"):
            worker = copy.deepcopy(definition(self.tree(), "_run_external_worker_payload"))
            assert isinstance(worker, ast.FunctionDef)
            if mutation == "snapshot":
                read = next(item for item in ast.walk(worker) if isinstance(item, ast.Attribute)
                    and item.attr == "read_text")
                read.value = ast.Call(func=ast.Name(id="Path", ctx=ast.Load()),
                    args=[ast.Name(id="payload_path", ctx=ast.Load())], keywords=[])
            elif mutation == "before_adoption":
                scope = next(item for item in ast.walk(worker) if isinstance(item, ast.With)
                    and ast.unparse(item.items[0].context_expr) == "use_cron_store(profile_home)")
                scope.body.insert(0, ast.parse("run_one_job(job, adapters=None, loop=None, verbose=False)").body[0])
            elif mutation == "before_ack":
                ack_try = next(item for item in ast.walk(worker) if isinstance(item, ast.Try)
                    and ast.unparse(item.body[0]).startswith("ack_path.parent.mkdir"))
                ack_try.body.insert(0, ast.parse("run_one_job(job, adapters=None, loop=None, verbose=False)").body[0])
            else:
                result = next(item for item in ast.walk(worker) if isinstance(item, ast.Return)
                    and isinstance(item.value, ast.Call) and isinstance(item.value.func, ast.Name)
                    and item.value.func.id == "run_one_job")
                result.value = ast.Constant(value=True)
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                self.run_worker(adopted=True, ack_ok=True, job_result=mutation != "false_to_true",
                    worker=ast.fix_missing_locations(worker))

    def run_worker(self, *, adopted: bool, ack_ok: bool, job_result: bool, worker: ast.FunctionDef | None = None):
        events = []
        with tempfile.TemporaryDirectory(prefix="fos-contract-cron-") as folder:
            home, ack = Path(folder) / "profile", Path(folder) / "ready.json"
            text = json.dumps({"profile_home": str(home), "job": {"id": "synthetic-job", "execution_id": "synthetic-execution"}})

            class Snapshot:
                def read_text(self, *, encoding):
                    self_outer.assertEqual("utf-8", encoding)
                    events.append("read")
                    return text

                def unlink(self, *, missing_ok):
                    self_outer.assertTrue(missing_ok)
                    events.append("unlink")

                def __fspath__(self):
                    raise AssertionError("검증한 snapshot을 경로로 다시 열었다")

            self_outer = self
            modules = {}
            for name in ("agent.secret_scope", "cron.executions", "hermes_cli.env_loader", "hermes_constants"):
                modules[name] = types.ModuleType(name)
            secret = modules["agent.secret_scope"]
            secret.is_multiplex_active = lambda: False
            secret.set_multiplex_active = lambda active: events.append(("multiplex", active))
            secret.build_profile_secret_scope = lambda profile: {"SYNTHETIC_SECRET": "synthetic"}
            secret.set_secret_scope = lambda mapping, **kwargs: "secret-token"
            secret.reset_secret_scope = lambda token: events.append(("reset-secret", token))
            constants = modules["hermes_constants"]
            constants.set_hermes_home_override = lambda profile: "home-token"
            constants.reset_hermes_home_override = lambda token: events.append(("reset-home", token))
            modules["hermes_cli.env_loader"].hydrate_profile_secret_sources = lambda profile: None

            def adopt(execution):
                self.assertEqual("synthetic-execution", execution)
                events.append("adopt")
                return {} if adopted else None

            modules["cron.executions"].adopt_claimed_execution = adopt

            def replace(source, destination):
                self.assertEqual("adopt", events[-1])
                self.assertFalse(ack.exists())
                self.assertEqual("synthetic-execution", json.loads(Path(source).read_text())["execution_id"])
                if not ack_ok:
                    raise OSError("synthetic ack failure")
                os.replace(source, destination)
                events.append("ack")

            environment = {"_HERMES_CRON_EXTERNAL_WORKER": "previous"}
            os_proxy = types.SimpleNamespace(environ=environment, replace=replace, open=os.open,
                fdopen=os.fdopen, fsync=os.fsync, getpid=os.getpid,
                O_WRONLY=os.O_WRONLY, O_CREAT=os.O_CREAT, O_EXCL=os.O_EXCL)

            def job(job, **kwargs):
                self.assertEqual({"id": "synthetic-job", "execution_id": "synthetic-execution"}, job)
                self.assertEqual("ack", events[-1])
                self.assertTrue(ack.exists())
                self.assertEqual("synthetic-execution", environment["_HERMES_CRON_EXTERNAL_WORKER"])
                self.assertEqual({"adapters": None, "loop": None, "verbose": False}, kwargs)
                events.append("job")
                return job_result

            namespace: dict[str, Any] = dict(Path=Path, json=json, os=os_proxy, contextlib=contextlib,
                logger=types.SimpleNamespace(error=lambda *args: None, exception=lambda *args: None),
                use_cron_store=lambda profile: contextlib.nullcontext(), run_one_job=lambda *args, **kwargs: self.fail("오래된 job binding"))
            if worker is None:
                selected = definition(self.tree(), "_run_external_worker_payload")
                assert isinstance(selected, ast.FunctionDef)
                worker = copy.deepcopy(selected)
            exec(compile(ast.Module(body=[worker], type_ignores=[]), "<actual-cron-worker>", "exec"), namespace)
            # 함수 정의 뒤 slot을 바꿔 호출 시점에 현재 binding을 읽는지 확인한다.
            namespace["run_one_job"] = job
            with patch.dict(sys.modules, modules):
                result = namespace["_run_external_worker_payload"](Snapshot(), ack)
            self.assertIs(result, adopted and ack_ok and job_result)
            self.assertEqual(["read", "unlink"], events[:2])
            self.assertEqual(1, events.count("read"))
            self.assertEqual(1, events.count("unlink"))
            self.assertEqual(adopted and ack_ok, "job" in events)
            self.assertEqual(adopted and ack_ok, ack.exists())
            self.assertEqual("previous", environment["_HERMES_CRON_EXTERNAL_WORKER"])
            self.assertIn(("reset-secret", "secret-token"), events)
            self.assertIn(("reset-home", "home-token"), events)
            self.assertEqual(("multiplex", False), events[-2])
