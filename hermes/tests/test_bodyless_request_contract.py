"""고정 공식 source의 helper·lazy forward·세 호출부를 네트워크 없이 분리 실행한다."""

import ast
from contextlib import redirect_stdout, redirect_stderr
from dataclasses import dataclass
import hashlib
import importlib.util
import io
import json
import logging
import os
from pathlib import Path
import socket
import sys
import types
import unittest
from unittest.mock import patch

import hermes_contract as contract
import test_bodyless_requests as fixtures
from test_bodyless_requests import CANARY, IMAGE
from runtime import bodyless_install as install
from runtime import bodyless_requests as writer_module


def _tree(source, relative):
    return ast.parse((source / relative).read_bytes(), filename=str(source / relative))


def _function(tree, name):
    return next(node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef) and node.name == name)


def _execute_nodes(source, relative, nodes, environment):
    future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)
    module = ast.Module(body=[future, *nodes], type_ignores=[])
    exec(compile(ast.fix_missing_locations(module), str(source / relative), "exec", dont_inherit=True), environment)


class BodylessDeclarationTest(unittest.TestCase):
    def test_fingerprint_surface_matches_single_contract(self):
        self.assertEqual(contract.BODYLESS_REQUEST_FILES, install.FINGERPRINT_FILES)
        self.assertEqual((contract.BODYLESS_REQUEST_FORWARD[3], contract.BODYLESS_REQUEST_WRITER[1]),
            (install.HELPER_MODULE, install.HELPER_NAME))
        self.assertEqual(contract.BODYLESS_REQUEST_FORWARD[4], install.HELPER_NAME)
        for file in (Path(__file__).resolve().parents[1] / "runtime").glob("*.py"):
            compile(file.read_bytes(), str(file), "exec")


@unittest.skipUnless(os.environ.get("HERMES_SOURCE"), "HERMES_SOURCE가 없어 공식 source 실행 검사를 건너뛴다")
class BodylessSourceTest(unittest.TestCase):
    def setUp(self):
        fixtures.InstallTest.setUp(self)
        self.source = Path(os.environ["HERMES_SOURCE"])
        self.before = {relative: hashlib.sha256((self.source / relative).read_bytes()).hexdigest()
            for relative in contract.BODYLESS_REQUEST_FILES}
        self.addCleanup(self._unchanged)
        helper_file, helper_name, _ = contract.BODYLESS_REQUEST_WRITER
        self.helper.__file__ = str(self.source / helper_file)
        self._load_actual_helper()
        self.fingerprints = dict(self.before)

    def _load_actual_helper(self):
        # 파일 전체를 compile해야 module import에 따른 compiler 최적화도 실제 bytecode와 같다.
        filename = self.helper.__file__
        compiled = compile(Path(filename).read_bytes(), filename, "exec", dont_inherit=True)
        code = next(code for code in compiled.co_consts if type(code) is types.CodeType and code.co_name == install.HELPER_NAME)
        function = types.FunctionType(code, self.helper.__dict__)
        function.__kwdefaults__ = {"error": None}
        self.helper.dump_api_request_debug = function

    def _unchanged(self):
        for relative, expected in self.before.items():
            self.assertEqual(expected, hashlib.sha256((self.source / relative).read_bytes()).hexdigest())

    def test_helper_signature_forward_and_all_known_calls(self):
        relative, name, call = contract.BODYLESS_REQUEST_WRITER
        definition = _function(_tree(self.source, relative), name)
        self.assertTrue(call.exact)
        self.assertEqual(["agent", "api_kwargs"], [arg.arg for arg in definition.args.args])
        self.assertEqual(list(call.keywords), [arg.arg for arg in definition.args.kwonlyargs])
        self.assertEqual([], definition.args.posonlyargs)
        self.assertIsNone(definition.args.vararg)
        self.assertIsNone(definition.args.kwarg)
        self.assertEqual([], definition.args.defaults)
        self.assertEqual([None, None], [ast.literal_eval(value) if value is not None else None for value in definition.args.kw_defaults])
        relative, class_name, attribute, target_module, target_name = contract.BODYLESS_REQUEST_FORWARD
        cls = next(node for node in _tree(self.source, relative).body if isinstance(node, ast.ClassDef) and node.name == class_name)
        assignment = next(node for node in cls.body if isinstance(node, ast.Assign)
            and any(isinstance(target, ast.Name) and target.id == attribute for target in node.targets))
        self.assertEqual("_forward", assignment.value.func.id)
        self.assertEqual([target_module, target_name], [ast.literal_eval(arg) for arg in assignment.value.args])
        found = {}
        aliases = []
        # 승인된 core와 설치 plugin 범위다. 외부 코드의 별도 alias 부재는 주장하지 않는다.
        roots = [self.source / name for name in ("agent", "gateway", "cron", "tools", "hermes_cli", "plugins")]
        roots.append(Path(__file__).resolve().parents[1] / "plugins")
        for root in roots:
            for file in root.rglob("*.py"):
                tree = ast.parse(file.read_bytes())
                for node in ast.walk(tree):
                    if isinstance(node, ast.ImportFrom) and any(alias.name == target_name for alias in node.names):
                        aliases.append(str(file.relative_to(root)))
                for node in ast.walk(tree):
                    if isinstance(node, ast.FunctionDef):
                        for nested in ast.walk(node):
                            if isinstance(nested, ast.Call) and isinstance(nested.func, ast.Attribute) and nested.func.attr == attribute:
                                reason = next(item.value.value for item in nested.keywords if item.arg == "reason")
                                found.setdefault(str(file.relative_to(self.source)), {})[node.name] = reason
        self.assertEqual([], aliases)
        self.assertEqual(contract.BODYLESS_REQUEST_CALLS, found)

    def test_actual_three_calls_preserve_input_provider_and_failure_results(self):
        payload = {"messages": [{"content": [{"type": "text", "text": CANARY},
            {"type": "image_url", "image_url": {"url": IMAGE}}]}]}
        outputs = io.StringIO()
        lazy_file, _ = contract.BODYLESS_REQUEST_LAZY
        lazy = {}
        exec(compile((self.source / lazy_file).read_bytes(), str(self.source / lazy_file), "exec"), lazy)
        relative, class_name, attribute, _, _ = contract.BODYLESS_REQUEST_FORWARD
        cls = next(node for node in _tree(self.source, relative).body if isinstance(node, ast.ClassDef) and node.name == class_name)
        assignment = next(node for node in cls.body if isinstance(node, ast.Assign)
            and any(isinstance(target, ast.Name) and target.id == attribute for target in node.targets))
        mixin = {"_forward": lazy["forward"]}
        _execute_nodes(self.source, relative, [assignment], mixin)
        agent = types.SimpleNamespace(tools=[], api_mode="chat_completions", _empty_content_retries=0,
            session_id="synthetic", platform="api_server", model="synthetic", provider="synthetic",
            base_url="http://example.test", log_prefix="", verbose_logging=False,
            _reset_stream_delivery_tracking=lambda: None, _reapply_reasoning_echo_for_provider=lambda messages: None,
            _is_copilot_url=lambda: False, _build_api_kwargs=lambda messages, **kwargs: payload,
            _flush_status_buffer=lambda: None, _summarize_api_error=lambda error: "synthetic rejection",
            _emit_diagnostic_status=lambda line: outputs.write(line), _vprint=lambda line, **kwargs: outputs.write(line))
        # consumer를 설치보다 먼저 만든다. helper의 실제 함수는 실행하지 않는다.
        agent._dump_api_request_debug = types.MethodType(mixin[attribute], agent)
        persisted = []
        provider_calls = []
        def strict_provider(request):
            self.assertIs(request, payload)
            self.assertIs(request["messages"], payload["messages"])
            self.assertEqual({"messages"}, set(request))
            provider_calls.append(request)
            return {"input_tokens": 17, "output_tokens": 3, "cost_usd": 0.0001}
        agent._persist_session = lambda messages, history: persisted.append((messages, history))
        loop = types.ModuleType("agent.conversation_loop")
        loop._redecorate_prompt_cache_for_provider = lambda agent, messages, **kwargs: (messages, None, [])
        loop._moa_client_consumes_prepared_request = lambda client: False
        for name in ("_billing_failure_result", "_content_policy_blocked_result", "_billing_block_dict",
                "_billing_or_entitlement_message", "_billing_terminal_label", "_print_billing_or_entitlement_guidance"):
            setattr(loop, name, lambda *args, **kwargs: None)
        anonymous = types.ModuleType("hermes_cli.anon_auth")
        anonymous.is_anonymous_agent = lambda agent: False
        classifier = types.ModuleType("_bodyless_actual_error_classifier")
        logging_sink = logging.StreamHandler(outputs)
        logger = logging.getLogger("bodyless-contract")
        logger.addHandler(logging_sink)
        self.addCleanup(logger.removeHandler, logging_sink)
        with patch.dict(sys.modules, {loop.__name__: loop, anonymous.__name__: anonymous, classifier.__name__: classifier}), \
                patch.object(socket.socket, "connect", side_effect=AssertionError("network forbidden")), \
                redirect_stdout(outputs), redirect_stderr(outputs):
            error_file = "agent/error_classifier.py"
            exec(compile((self.source / error_file).read_bytes(), str(self.source / error_file), "exec"), classifier.__dict__)
            error = ValueError("synthetic rejection")
            # 실제 classifier의 HTTP 422 분류와 ClassifiedError를 쓴다. 나머지 provider 분기는 검증 범위 밖이다.
            context = classifier._Ctx(error, 422, {}, "synthetic rejection", "synthetic", "synthetic", 1, 200000, 1, "", False)
            verdict = classifier._by_status(context)
            classified = classifier.ClassifiedError(**verdict)
            environment = {"dataclass": dataclass, "logger": logger,
                "env_var_enabled": lambda name: True, "strip_images_for_rejecting_model": lambda *args: None,
                "sanitize_outbound_kwargs": lambda *args: None, "_fire_pre_api_request_hook": lambda *args, **kwargs: None,
                "FailoverReason": classifier.FailoverReason, "provider_label_for": lambda provider: "synthetic",
                "_NONRETRYABLE_LABELS": {}, "_welcome_tier_guidance": lambda *args, **kwargs: "",
                "_missing_vendor_prefix_suggestion": lambda *args: None, "_vlines": lambda agent, *lines: outputs.write("".join(lines)),
                "display_hermes_home": lambda: "synthetic", "nonretryable_copy": lambda *args, **kwargs: "failure",
                "exhausted_copy": lambda *args, **kwargs: "failure", "_stamp_limit_reset": lambda *args: None,
                "_welcome_outage_copy": lambda *args, **kwargs: None, "is_thinking_timeout": lambda *args: False,
                "_STREAM_DROP_MARKERS": ("connection lost",),
                # v0.21.6의 집계 호출은 요청 writer 경계 밖이다. provider 인자 보존은 아래에서 확인한다.
                "observe_request_tools": lambda agent, tools: None}
            relative = "agent/turn_api_request.py"
            tree = _tree(self.source, relative)
            definition = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "ApiRequestBuild")
            _execute_nodes(self.source, relative, [definition, _function(tree, "build_api_request")], environment)
            relative = "agent/turn_recovery.py"
            tree = _tree(self.source, relative)
            _execute_nodes(self.source, relative, [_function(tree, name) for name in
                ("_failed_turn_result", "_with_delivered_partial", "nonretryable_client_error_result",
                 "max_retries_exhausted_result")], environment)
            build_args = dict(api_messages=payload["messages"], _moa_prepared_request=None, tools_for_api=[],
                system_message="", messages=[], original_user_message=CANARY, approx_tokens=1, total_chars=1,
                retry_count=0, api_call_count=1, api_request_id="synthetic", api_start_time=0, effective_task_id="synthetic", turn_id="synthetic")
            terminal_args = dict(api_kwargs=payload, api_messages=payload["messages"], messages=[], conversation_history=[],
                api_call_count=1, approx_tokens=1, provider="synthetic", base_url="http://example.test", model="synthetic")
            def exercise():
                built = environment["build_api_request"](agent, **build_args)
                self.assertIs(payload, built.api_kwargs)
                # 엄격한 provider 대역은 writer가 준 객체를 그대로 받는다.
                self.assertIs(payload["messages"], built.api_kwargs["messages"])
                usage = strict_provider(built.api_kwargs)
                first = environment["nonretryable_client_error_result"](agent, error, classified, status_code=422, **terminal_args)
                second = environment["max_retries_exhausted_result"](agent, error, classified,
                    attempts=2, is_rate_limited=False, error_msg="synthetic rejection", **terminal_args)
                return first, second, usage
            counters = []
            self.helper.dump_api_request_debug = lambda agent, request, **kwargs: counters.append(kwargs["reason"])
            baseline = exercise()
            self.assertEqual(["preflight", "non_retryable_client_error", "max_retries_exhausted"], counters)
            # 설치 검사는 실제 공식 helper의 code와 5파일 지문을 검증한다.
            self._load_actual_helper()
            install.install_bodyless_writer(resolver=self.resolver, fingerprints=self.fingerprints)
            self.assertEqual(baseline, exercise())
            self.assertEqual(4, len(persisted))
            diagnostics = list(self.root.glob("request_diagnostic_*.json"))
            self.assertEqual(3, len(diagnostics))
            self.assertEqual(set(counters), {json.loads(file.read_text())["reason"] for file in diagnostics})
            with patch.object(writer_module, "_write", side_effect=OSError("synthetic IO failure")):
                self.assertEqual(baseline, exercise())
            self.assertEqual(6, len(persisted))
            self.assertEqual(3, len(provider_calls))
            self.assertEqual(3, len(list(self.root.glob("*.json"))))
        self.assertNotIn(CANARY, outputs.getvalue())
        self.assertNotIn(IMAGE, outputs.getvalue())
        self.assertEqual(CANARY, payload["messages"][0]["content"][0]["text"])
        self.assertEqual(IMAGE, payload["messages"][0]["content"][1]["image_url"]["url"])
        for result in baseline[:2]:
            self.assertEqual("format_error", result["failure_reason"])
            self.assertFalse(result["failure_retryable"])
            self.assertEqual(1, result["api_calls"])
            self.assertTrue(result["failed"])
