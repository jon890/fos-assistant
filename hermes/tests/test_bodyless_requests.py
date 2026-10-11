"""일반 writer의 본문 금지·입력 보존·POSIX 파일 경계를 합성 값으로 검증한다."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stdout, redirect_stderr
import copy
import hashlib
import importlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from runtime import InstallationError, install_bodyless_writer
from runtime import bodyless_install as install
from runtime import bodyless_requests as writer_module
from runtime.request_counts import count_request, MAX_PARTS, MAX_SCAN_CHARS

CANARY = "SYNTHETIC-BODY-URL-TOKEN-CANARY"
IMAGE = "data:image/png;base64,YWJj"


class Bomb:
    def __getattribute__(self, name):
        raise AssertionError("임의 속성을 읽었다")

    def __str__(self):
        raise AssertionError("원문으로 바꿨다")

    __repr__ = __str__


class CountsTest(unittest.TestCase):
    def test_supported_provider_and_native_tool_shapes(self):
        text = "가😀"
        fixtures = (
            {"messages": [{"role": "user", "content": [{"type": "text", "text": text},
                {"type": "image_url", "image_url": {"url": IMAGE}}]}]},
            {"input": [{"type": "message", "content": [{"type": "input_text", "text": text},
                {"type": "input_image", "image_url": IMAGE}]}]},
            {"messages": [{"content": [{"type": "text", "text": text},
                {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "YWJj"}}]}]},
            {"contents": [{"parts": [{"text": text}, {"inlineData": {"mimeType": "image/png", "data": "YWJj"}}]}]},
            {"contents": [{"parts": [{"text": text}, {"inline_data": {"mime_type": "image/png", "data": b"abc"}}]}]},
            {"messages": [{"role": "tool", "content": [{"type": "text", "text": text},
                {"type": "image_url", "image_url": {"url": IMAGE}}]}]},
            {"input": [{"type": "function_call_output", "output": [{"type": "input_text", "text": text},
                {"type": "input_image", "image_url": IMAGE}]}]},
            {"messages": [{"content": [{"type": "tool_result", "content": [{"type": "text", "text": text},
                {"type": "image", "source": {"type": "base64", "data": "YWJj"}}]}]}]},
        )
        for payload in fixtures:
            with self.subTest(shape=fixtures.index(payload)):
                original = copy.deepcopy(payload)
                result = count_request(payload)
                self.assertEqual((1, 1, 2, 7, 3, "complete"), (result["text_parts"], result["image_parts"],
                    result["text_chars"], result["text_bytes"], result["image_bytes"], result["completeness"]))
                self.assertEqual(original, payload)

    def test_system_and_instructions_are_counted(self):
        for key in ("system", "instructions", "systemInstruction", "system_instruction"):
            result = count_request({key: {"parts": [{"text": "ab"}]}, "input": "c"})
            self.assertEqual(3, result["text_bytes"])
            self.assertEqual("complete", result["completeness"])

    def test_unknown_invalid_and_objects_never_run_user_code(self):
        class HostileDict(dict):
            def items(self):
                raise AssertionError("하위 타입을 실행했다")
        for payload, expected in (({}, "unknown"), ({"novel": CANARY}, "unknown"),
                (Bomb(), "invalid"), (HostileDict(messages=[]), "invalid"),
                ({"messages": [{"content": Bomb()}]}, "invalid"),
                ({"messages": [{"content": [{"type": Bomb(), "text": CANARY}]}]}, "unknown"),
                ({"messages": [{"content": [{"type": "audio", "data": CANARY}]}]}, "unknown"),
                ({"messages": [{"content": [{"type": "text", "text": 5}]}]}, "invalid"),
                ({"messages": [{"content": [{"type": "text", "text": "a", "novel": Bomb()}]}]}, "unknown"),
                ({"messages": [{"content": [{"type": "text", "text": "\ud800"}]}]}, "invalid")):
            self.assertEqual(expected, count_request(payload)["completeness"])

    def test_cycle_depth_parts_and_scan_limits(self):
        cycle = []
        cycle.append(cycle)
        self.assertEqual("cycle", count_request({"messages": cycle})["completeness"])
        deep = "a"
        for _ in range(20):
            deep = [deep]
        self.assertEqual("limit", count_request({"messages": deep})["completeness"])
        result = count_request({"input": ["a"] * (MAX_PARTS + 10)})
        self.assertEqual((MAX_PARTS, "limit"), (result["text_parts"], result["completeness"]))
        huge = "😀" * (MAX_SCAN_CHARS + 10)
        result = count_request({"input": huge})
        self.assertEqual(len(huge), result["text_chars"])
        self.assertEqual(MAX_SCAN_CHARS * 4, result["text_bytes"])
        self.assertEqual("limit", result["completeness"])
        result = count_request({"input": [{"type": "input_image", "image_url": "data:image/png;base64," + "A" * 1000000}]})
        self.assertEqual((1000000, 0, "limit"), (result["image_encoded_chars"], result["image_bytes"], result["completeness"]))
        result = count_request({"messages": [], **{str(index): CANARY for index in range(80)}})
        self.assertEqual("limit", result["completeness"])

    def test_remote_invalid_base64_and_gemini_function_response(self):
        for data, expected, size in (("https://example.test/" + CANARY, "unknown", 0),
                ("data:image/png;base64,YQ==", "complete", 1), ("data:image/png;base64,YWI=", "complete", 2),
                ("data:image/png;base64,YR==", "invalid", 0), ("data:image/png;base64,!!!=", "invalid", 0),
                ("data:image/png,abc", "invalid", 0), ("data:image/png;base64,A", "invalid", 0)):
            result = count_request({"input": [{"type": "input_image", "image_url": data}]})
            self.assertEqual((expected, size), (result["completeness"], result["image_bytes"]))
        result = count_request({"contents": [{"parts": [{"functionResponse": {"response": Bomb(),
            "parts": [{"inlineData": {"mimeType": "image/png", "data": "YWJj"}}]}}]}]})
        self.assertEqual((1, 3, "unknown"), (result["image_parts"], result["image_bytes"], result["completeness"]))
        result = count_request({"contents": [{"parts": [{"fileData": {"mimeType": "image/png", "fileUri": CANARY}}]}]})
        self.assertEqual((1, 0, "unknown"), (result["image_parts"], result["image_bytes"], result["completeness"]))


class WriterTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = os.path.realpath(self.temporary.name)
        self.writer = writer_module.make_writer(lambda agent: self.directory)
        self.payload = {"messages": [{"content": [{"type": "text", "text": CANARY},
            {"type": "image_url", "image_url": {"url": IMAGE}}]}], "extra_body": {"token": CANARY}}

    def test_no_raw_in_file_or_output_with_stdout_option_and_canary_error(self):
        output = io.StringIO()
        original = copy.deepcopy(self.payload)
        with redirect_stdout(output), redirect_stderr(output), patch.dict(os.environ, {"HERMES_DUMP_REQUEST_STDOUT": "1"}):
            target = self.writer(Bomb(), self.payload, reason=CANARY, error=ValueError(CANARY))
        content = target.read_text()
        self.assertNotIn(CANARY, content + output.getvalue())
        self.assertNotIn(IMAGE, content + output.getvalue())
        self.assertEqual("", output.getvalue())
        self.assertEqual(0o600, target.stat().st_mode & 0o777)
        self.assertEqual({"reason", "error", "counts"}, set(json.loads(content)))
        self.assertEqual(("unknown", "value"), (json.loads(content)["reason"], json.loads(content)["error"]))
        self.assertEqual(original, self.payload)
        self.assertNotIn(CANARY, target.name)

    def test_reason_error_enums_and_exception_properties(self):
        for reason in ("preflight", "non_retryable_client_error", "max_retries_exhausted"):
            target = self.writer(Bomb(), self.payload, reason=reason, error=Bomb())
            self.assertEqual((reason, "other"), (json.loads(target.read_text())["reason"], json.loads(target.read_text())["error"]))

    def test_missing_resolver_failure_extractor_and_writer_failure_do_not_fallback(self):
        for resolver in (lambda agent: None, lambda agent: "relative", lambda agent: Bomb()):
            self.assertIsNone(writer_module.make_writer(resolver)(Bomb(), self.payload, reason=Bomb(), error=Bomb()))
        def failing(agent):
            raise ValueError(CANARY)
        with redirect_stdout(io.StringIO()) as output, redirect_stderr(output):
            self.assertIsNone(writer_module.make_writer(failing)(Bomb(), self.payload, reason="preflight"))
            for name in ("count_request", "_write"):
                with patch.object(writer_module, name, side_effect=ValueError(CANARY)):
                    self.assertIsNone(self.writer(Bomb(), self.payload, reason="preflight"))
        self.assertEqual("", output.getvalue())
        self.assertEqual([], list(Path(self.directory).iterdir()))

    def test_parent_and_leaf_symlinks_and_collision(self):
        actual = Path(self.directory) / "actual"
        actual.mkdir()
        parent = Path(self.directory) / "linked"
        parent.symlink_to(actual, target_is_directory=True)
        self.assertIsNone(writer_module.make_writer(lambda agent: str(parent))(None, self.payload, reason="preflight"))
        child = actual / "child"
        child.mkdir()
        self.assertIsNone(writer_module.make_writer(lambda agent: str(parent / "child"))(None, self.payload, reason="preflight"))
        target = actual / "untouched"
        target.write_text(CANARY)
        leaf = Path(self.directory) / "request_diagnostic_fixed.json"
        leaf.symlink_to(target)
        with patch.object(writer_module.secrets, "token_hex", return_value="fixed"):
            self.assertIsNone(self.writer(None, self.payload, reason="preflight"))
        self.assertEqual(CANARY, target.read_text())
        leaf.unlink()
        leaf.write_text(CANARY)
        with patch.object(writer_module.secrets, "token_hex", return_value="fixed"):
            self.assertIsNone(self.writer(None, self.payload, reason="preflight"))
        self.assertEqual(CANARY, leaf.read_text())

    def test_read_only_io_cleanup_and_unsupported_platform(self):
        readonly = Path(self.directory) / "readonly"
        readonly.mkdir(mode=0o500)
        self.addCleanup(readonly.chmod, 0o700)
        if os.geteuid() != 0:
            self.assertIsNone(writer_module.make_writer(lambda agent: str(readonly))(None, self.payload, reason="preflight"))
        with patch.object(writer_module.os, "fsync", side_effect=OSError(CANARY)):
            self.assertIsNone(self.writer(None, self.payload, reason="preflight"))
        self.assertEqual([], list(Path(self.directory).glob("*.json")))
        with patch.object(writer_module.os, "name", "nt"):
            self.assertIsNone(self.writer(None, self.payload, reason="preflight"))

    def test_concurrent_files_and_profile_isolation(self):
        directories = {index: Path(self.directory) / str(index) for index in (0, 1)}
        for directory in directories.values():
            directory.mkdir()
        writer = writer_module.make_writer(lambda agent: str(directories[agent]))
        with ThreadPoolExecutor(max_workers=8) as executor:
            outputs = list(executor.map(lambda index: writer(index % 2, {"input": "a" * (index % 2 + 1)}, reason="preflight"), range(64)))
        self.assertEqual(64, len(set(outputs)))
        for index, output in enumerate(outputs):
            self.assertEqual(directories[index % 2], output.parent)
            self.assertEqual(index % 2 + 1, json.loads(output.read_text())["counts"]["text_bytes"])

    def test_concurrent_collision_and_restrictive_umask(self):
        with patch.object(writer_module.secrets, "token_hex", return_value="collision"), ThreadPoolExecutor(max_workers=8) as executor:
            outputs = list(executor.map(lambda _: self.writer(None, self.payload, reason="preflight"), range(32)))
        self.assertEqual(1, sum(output is not None for output in outputs))
        old_mask = os.umask(0o777)
        try:
            target = self.writer(None, self.payload, reason="preflight")
        finally:
            os.umask(old_mask)
        self.assertEqual(0o600, target.stat().st_mode & 0o777)

    def test_open_parent_fd_survives_directory_replacement(self):
        current = Path(self.directory) / "current"
        current.mkdir()
        pinned = Path(self.directory) / "pinned"
        other = Path(self.directory) / "other"
        other.mkdir()
        actual_open = os.open
        def replacing_open(name, flags, *args, **kwargs):
            fd = actual_open(name, flags, *args, **kwargs)
            if name == "current":
                current.rename(pinned)
                current.symlink_to(other, target_is_directory=True)
            return fd
        with patch.object(writer_module.os, "open", side_effect=replacing_open) as intercepted, \
                patch.object(writer_module.os, "supports_dir_fd", os.supports_dir_fd | {intercepted}):
            target = writer_module.make_writer(lambda agent: str(current))(None, self.payload, reason="preflight")
        self.assertIsNotNone(target)
        self.assertEqual(1, len(list(pinned.glob("*.json"))))
        self.assertEqual([], list(other.iterdir()))


class InstallTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(os.path.realpath(temporary.name))
        self.old_installation = install._owner.installation
        install._owner.installation = None
        self.addCleanup(setattr, install._owner, "installation", self.old_installation)
        self.helper = types.ModuleType(install.HELPER_MODULE)
        for relative in install.FINGERPRINT_FILES:
            target = self.root / relative
            target.parent.mkdir(exist_ok=True)
            target.write_text("# 합성 고정 소스\n")
        target = self.root / install.FINGERPRINT_FILES[0]
        target.write_text("def dump_api_request_debug(agent, api_kwargs, *, reason, error=None):\n    agent.append('raw_counter')\n")
        self.helper.__file__ = str(target)
        exec(compile(target.read_bytes(), str(target), "exec"), self.helper.__dict__)
        self.original = self.helper.dump_api_request_debug
        self.fingerprints = {relative: hashlib.sha256((self.root / relative).read_bytes()).hexdigest()
            for relative in install.FINGERPRINT_FILES}
        self.resolver = lambda agent: str(self.root)
        patched = patch.dict(sys.modules, {install.HELPER_MODULE: self.helper})
        patched.start()
        self.addCleanup(patched.stop)

    def test_idempotent_same_config_reload_and_no_cleanup_restore(self):
        first = install_bodyless_writer(resolver=self.resolver, fingerprints=self.fingerprints)
        self.assertIs(first, install_bodyless_writer(resolver=self.resolver, fingerprints=dict(self.fingerprints)))
        reloaded = importlib.reload(install)
        self.assertIs(first, reloaded.install_bodyless_writer(resolver=self.resolver, fingerprints=self.fingerprints))
        self.assertIs(self.helper.dump_api_request_debug, first)
        self.assertIsNotNone(first(None, {"input": "a"}, reason="preflight"))
        for relative, expected in self.fingerprints.items():
            self.assertEqual(expected, hashlib.sha256((self.root / relative).read_bytes()).hexdigest())

    def test_config_signature_source_and_live_identity_mismatch_fail_closed(self):
        invalid = [None, {}, {**self.fingerprints, "extra": "0" * 64},
            {**self.fingerprints, install.FINGERPRINT_FILES[0]: "not-a-hash"},
            {**self.fingerprints, install.FINGERPRINT_FILES[0]: "0" * 64},
            {**self.fingerprints, install.FINGERPRINT_FILES[0]: "A" * 64},
            {**{key: value for key, value in self.fingerprints.items() if key != install.FINGERPRINT_FILES[0]}, "extra": "0" * 64}]
        for configuration in invalid:
            with self.assertRaises(RuntimeError) as failure:
                install_bodyless_writer(resolver=self.resolver, fingerprints=configuration)
            self.assertEqual("bodyless_installation_failed", str(failure.exception))
        self.helper.dump_api_request_debug = lambda agent, api_kwargs, **kwargs: None
        with self.assertRaises(RuntimeError):
            install_bodyless_writer(resolver=self.resolver, fingerprints=self.fingerprints)
        self.helper.dump_api_request_debug = self.original
        installed = install_bodyless_writer(resolver=self.resolver, fingerprints=self.fingerprints)
        with self.assertRaises(RuntimeError):
            install_bodyless_writer(resolver=lambda agent: str(self.root), fingerprints=self.fingerprints)
        self.assertIs(installed, self.helper.dump_api_request_debug)
        self.helper.dump_api_request_debug = self.original
        with self.assertRaises(RuntimeError):
            install_bodyless_writer(resolver=self.resolver, fingerprints=self.fingerprints)

    def test_direct_alias_counterexample_is_explicit(self):
        alias = self.original
        install_bodyless_writer(resolver=self.resolver, fingerprints=self.fingerprints)
        counter = []
        alias(counter, {}, reason="preflight")
        self.assertEqual(["raw_counter"], counter)

    def test_bundle_contains_exact_runtime_sources(self):
        repository = Path(__file__).resolve().parents[2]
        output = self.root / "bundle"
        subprocess.run(["bash", "hermes/bundle.sh", "--out", str(output), "--mcp-url", "http://example.test/mcp"],
            cwd=repository, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        sources = repository / "hermes/runtime"
        self.assertEqual({file.name for file in sources.glob("*.py")}, {file.name for file in (output / "runtime").glob("*.py")})
        for file in sources.glob("*.py"):
            self.assertEqual(file.read_bytes(), (output / "runtime" / file.name).read_bytes())
            compile(file.read_bytes(), str(file), "exec")
