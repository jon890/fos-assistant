"""SDK source 고정과 프로세스 수명, filter의 본문 제거 계약이다."""

import asyncio
import concurrent.futures
import contextlib
import importlib
import json
import logging
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock

import test_connector_manifest as base


@contextlib.contextmanager
def fresh_install(helper):
    """설치 실패 시험만 전역 logger 상태를 격리하고 끝에 원래 상태를 복구한다."""
    loggers = helper._source_loggers()
    anchor = loggers[0]
    previous = anchor.__dict__.pop(helper._STATE_KEY, None)
    filters = [list(logger.filters) for logger in loggers]
    for logger in loggers:
        logger.filters[:] = []
    try:
        yield loggers
    finally:
        anchor.__dict__.pop(helper._STATE_KEY, None)
        if previous is not None:
            anchor.__dict__[helper._STATE_KEY] = previous
        for logger, items in zip(loggers, filters):
            logger.filters[:] = items


class DiagnosticsTest(base.ConnectorGateCase):
    def setUp(self):
        super().setUp()
        self.helper = sys.modules[self.plugin.__name__ + ".connector_diagnostics"]

    def test_installed_full_source_matches_contract(self):
        with fresh_install(self.helper):
            self.helper._validate_source()
            self.assertEqual(len(json.loads(self.helper._MANIFEST.read_bytes())), 122)
            state = self.helper.ensure_snapshot_diagnostics()
            self.assertIs(self.helper.ensure_snapshot_diagnostics(), state)

    def test_filter_does_not_format_sensitive_fields(self):
        class Unprintable:
            def __str__(self):
                raise AssertionError("str 금지")

            def __repr__(self):
                raise AssertionError("repr 금지")
        state = self.helper.ensure_snapshot_diagnostics()
        record = logging.LogRecord("client", logging.ERROR, __file__, 1,
                                   Unprintable(), (Unprintable(),), (ValueError, Unprintable(), None))
        record.exc_text = record.stack_info = record.message = "synthetic-body"
        before = record.__dict__.copy()
        self.assertTrue(state.guard.filter(record))
        self.assertEqual(record.__dict__, before)
        with self.helper.snapshot_diagnostics_scope():
            self.assertTrue(state.guard.filter(record))
        self.assertEqual((record.msg, record.args), (self.helper.MESSAGE, ()))
        self.assertIsNone(record.exc_info)
        self.assertIsNone(record.exc_text)
        self.assertIsNone(record.stack_info)
        self.assertNotIn("message", record.__dict__)
        for key in ("name", "levelno", "levelname", "created", "pathname", "lineno"):
            self.assertEqual(record.__dict__[key], before[key])

    def test_reload_and_concurrent_first_install_share_scope(self):
        with fresh_install(self.helper):
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                states = list(pool.map(lambda _: self.helper.ensure_snapshot_diagnostics(), range(24)))
            self.assertTrue(all(state is states[0] for state in states))
            state = states[0]
            with self.helper.snapshot_diagnostics_scope():
                reloaded = importlib.reload(self.helper)
                self.assertIs(reloaded.ensure_snapshot_diagnostics(), state)
                self.assertTrue(state.active.get())
                with reloaded.snapshot_diagnostics_scope():
                    self.assertTrue(state.active.get())
                self.assertTrue(state.active.get())
            self.assertFalse(state.active.get())
            for logger in state.loggers:
                self.assertEqual(logger.filters, [state.guard])

    def test_nested_scope_exception_task_inheritance_and_reset(self):
        state = self.helper.ensure_snapshot_diagnostics()

        async def child():
            await asyncio.sleep(0)
            return state.active.get()

        async def run():
            outside = asyncio.create_task(child())
            with self.helper.snapshot_diagnostics_scope():
                inside = asyncio.create_task(child())
                with self.assertRaises(ValueError):
                    with self.helper.snapshot_diagnostics_scope():
                        raise ValueError
                self.assertTrue(state.active.get())
            self.assertFalse(state.active.get())
            self.assertEqual(await asyncio.gather(outside, inside), [False, True])
        asyncio.run(run())

    def test_parent_filter_does_not_protect_descendant_record(self):
        state = self.helper.ensure_snapshot_diagnostics()
        records = []

        class Capture(logging.Handler):
            def emit(self, record):
                records.append(record.msg)
        parent = logging.getLogger("mcp")
        handler = Capture()
        parent.addHandler(handler)
        parent.addFilter(state.guard)
        try:
            with self.helper.snapshot_diagnostics_scope():
                logging.getLogger("mcp.synthetic_unlisted").handle(
                    logging.LogRecord("mcp.synthetic_unlisted", logging.ERROR, __file__, 1,
                                      "synthetic-body", (), None))
            self.assertEqual(records, ["synthetic-body"])
        finally:
            parent.removeHandler(handler)
            parent.removeFilter(state.guard)

    def test_filter_attachment_policy_and_logger_identity_fail_closed(self):
        state = self.helper.ensure_snapshot_diagnostics()
        logger = state.loggers[0]
        with fresh_install(self.helper) as loggers:
            loggers[0].addFilter(logging.Filter())
            with self.assertRaisesRegex(RuntimeError, "^snapshot diagnostics unavailable$"):
                self.helper.ensure_snapshot_diagnostics()
        for change in ("removed", "ahead", "policy", "state-policy", "context", "module-logger", "logger-name"):
            with contextlib.ExitStack() as stack:
                original = list(logger.filters)
                stack.callback(lambda: logger.filters.__setitem__(slice(None), original))
                if change == "removed":
                    logger.removeFilter(state.guard)
                elif change == "ahead":
                    logger.filters.insert(0, logging.Filter())
                elif change == "policy":
                    stack.enter_context(mock.patch.object(state.guard, "policy", "other-policy"))
                elif change == "state-policy":
                    stack.enter_context(mock.patch.object(state, "policy", "other-policy"))
                elif change == "context":
                    stack.enter_context(mock.patch.object(state.guard, "active", object()))
                elif change == "logger-name":
                    stack.enter_context(mock.patch.object(logger, "name", "changed-name"))
                else:
                    module = importlib.import_module("mcp.client.stdio")
                    stack.enter_context(mock.patch.object(module, "logger", logging.getLogger("other")))
                with self.assertRaisesRegex(RuntimeError, "^snapshot diagnostics unavailable$"):
                    self.helper.ensure_snapshot_diagnostics()

    def test_version_hash_added_deleted_and_loaded_file_fail_closed(self):
        helper = self.helper
        import mcp
        original_root = Path(mcp.__file__).parent
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "mcp"
            shutil.copytree(original_root, root, ignore=shutil.ignore_patterns("__pycache__"))
            with contextlib.ExitStack() as stack:
                stack.enter_context(mock.patch.object(mcp, "__file__", str(root / "__init__.py")))
                for module_name, _ in helper._MODULES:
                    module = importlib.import_module(module_name)
                    relative = module_name.removeprefix("mcp.").replace(".", "/") + ".py"
                    stack.enter_context(mock.patch.object(module, "__file__", str(root / relative)))
                helper._validate_source()
                for change in ("version", "hash", "added", "deleted", "loaded-file", "unreadable"):
                    with fresh_install(helper), contextlib.ExitStack() as changes:
                        if change == "version":
                            changes.enter_context(mock.patch.object(helper.importlib.metadata, "version", return_value="2.0.1"))
                        elif change == "unreadable":
                            changes.enter_context(mock.patch.object(helper, "_MANIFEST", root / "missing.json"))
                        elif change == "loaded-file":
                            module = importlib.import_module("mcp.client.stdio")
                            changes.enter_context(mock.patch.object(module, "__file__", str(root / "client/session.py")))
                        elif change == "added":
                            file = root / "new_emission.py"
                            file.write_text("import logging\nlogger = logging.getLogger('new')\n")
                            changes.callback(file.unlink)
                        else:
                            file = root / "client/stdio.py"
                            original = file.read_bytes()
                            changes.callback(file.write_bytes, original)
                            if change == "hash":
                                file.write_bytes(original + b"\n# synthetic source change\n")
                            else:
                                file.unlink()
                        with self.assertRaisesRegex(RuntimeError, "^snapshot diagnostics unavailable$"):
                            helper.ensure_snapshot_diagnostics()


if __name__ == "__main__":
    unittest.main()
