"""관리 entrypoint가 프로세스마다 한 번 설치하는 내부 helper 교체 계약."""

import hashlib
import importlib
import inspect
from pathlib import Path
import sys
import threading
import types
from typing import Callable, cast

from .bodyless_requests import DestinationResolver, make_writer

HELPER_MODULE = "agent.agent_runtime_helpers"
HELPER_NAME = "dump_api_request_debug"
FINGERPRINT_FILES = (
    "agent/agent_runtime_helpers.py", "agent/lazy_forward.py", "agent/session_persistence.py",
    "agent/turn_api_request.py", "agent/turn_recovery.py",
)
_OWNER_MODULE = "_fos_bodyless_requests_owner_v1"


class _Owner(types.ModuleType):
    """reload 뒤에도 유지하는 프로세스 상태의 실제 속성을 선언한다."""

    lock: threading.Lock
    installation: tuple[types.ModuleType, DestinationResolver, tuple[str, ...], Callable[..., Path | None]] | None


_candidate = _Owner(_OWNER_MODULE)
_candidate.lock = threading.Lock()
_candidate.installation = None
_owner = cast(_Owner, sys.modules.setdefault(_OWNER_MODULE, _candidate))


class InstallationError(RuntimeError):
    """bootstrap은 이 고정 오류에서 main을 시작하지 않아야 한다."""

    def __init__(self):
        super().__init__("bodyless_installation_failed")


def _configuration(fingerprints):
    if type(fingerprints) is not dict or len(fingerprints) != len(FINGERPRINT_FILES):
        raise InstallationError()
    if any(type(key) is not str for key in fingerprints) or set(fingerprints) != set(FINGERPRINT_FILES):
        raise InstallationError()
    values = tuple(fingerprints[key] for key in FINGERPRINT_FILES)
    if any(type(value) is not str or len(value) != 64 or any(char not in "0123456789abcdef" for char in value)
            for value in values):
        raise InstallationError()
    return values


def _verify_source(helper, configuration):
    if type(helper) is not types.ModuleType or type(helper.__dict__.get("__file__")) is not str:
        raise InstallationError()
    filename = helper.__dict__["__file__"]
    root = Path(filename).parent.parent
    helper_source = None
    for relative, expected in zip(FINGERPRINT_FILES, configuration):
        source = (root / relative).read_bytes()
        if hashlib.sha256(source).hexdigest() != expected:
            raise InstallationError()
        if relative == FINGERPRINT_FILES[0]:
            helper_source = source
    return filename, helper_source


def _verify_callable(original, filename, source):
    if type(original) is not types.FunctionType:
        raise InstallationError()
    parameters = tuple(inspect.signature(original, follow_wrapped=False).parameters.values())
    kinds = (inspect.Parameter.POSITIONAL_OR_KEYWORD,) * 2 + (inspect.Parameter.KEYWORD_ONLY,) * 2
    if (tuple(parameter.name for parameter in parameters) != ("agent", "api_kwargs", "reason", "error")
            or tuple(parameter.kind for parameter in parameters) != kinds
            or any(parameter.default is not inspect.Parameter.empty for parameter in parameters[:3])
            or parameters[3].default is not None):
        raise InstallationError()
    # 파일 지문만 맞고 함수 속성은 바뀐 경우도 거절한다. 원문 함수를 실행하지 않는다.
    compiled = compile(source, filename, "exec", dont_inherit=True)
    codes = [code for code in compiled.co_consts if type(code) is types.CodeType and code.co_name == HELPER_NAME]
    if len(codes) != 1 or original.__code__ != codes[0]:
        raise InstallationError()


def install_bodyless_writer(*, resolver: DestinationResolver, fingerprints: dict[str, str]):
    """동일 resolver 객체와 고정 지문만 멱등이다. plugin cleanup에 복원 함수를 주지 않는다."""
    try:
        configuration = _configuration(fingerprints)
        if not callable(resolver):
            raise InstallationError()
        with _owner.lock:
            helper = importlib.import_module(HELPER_MODULE)
            filename, source = _verify_source(helper, configuration)
            installed = _owner.installation
            if installed is not None:
                previous_helper, previous_resolver, previous_configuration, writer = installed
                if (helper is not previous_helper or resolver is not previous_resolver
                        or configuration != previous_configuration or helper.__dict__.get(HELPER_NAME) is not writer):
                    raise InstallationError()
                return writer
            _verify_callable(helper.__dict__.get(HELPER_NAME), filename, source)
            writer = make_writer(resolver)
            helper.__dict__[HELPER_NAME] = writer
            _owner.installation = (helper, resolver, configuration, writer)
            return writer
    except BaseException:
        # import 실패의 민감한 원인·stack을 bootstrap이 출력하지 않도록 고정 오류만 전달한다.
        raise InstallationError() from None
