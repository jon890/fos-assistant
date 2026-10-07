"""Hermes 와 같은 패키지 적재와 plugin 이름 바꿔 끼우기를 시험에서 쓴다."""

import contextlib
import importlib.util
import sys
from unittest import mock


def load_plugin(name, entrypoint, add_cleanup):
    """패키지를 등록하고 하위 모듈까지 시험 종료 때 원래 상태로 돌린다."""
    prefix = name + "."
    previous = {key: value for key, value in sys.modules.items()
                if key == name or key.startswith(prefix)}

    def restore():
        for key in list(sys.modules):
            if key == name or key.startswith(prefix):
                del sys.modules[key]
        sys.modules.update(previous)

    for key in previous:
        del sys.modules[key]
    spec = importlib.util.spec_from_file_location(name, entrypoint)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    try:
        spec.loader.exec_module(module)
    except BaseException:
        restore()
        raise
    add_cleanup(restore)
    return module


def _plugin_modules(plugin, name):
    prefix = plugin.__name__ + "."
    return [module for key, module in list(sys.modules.items())
            if (key == plugin.__name__ or key.startswith(prefix)) and hasattr(module, name)]


@contextlib.contextmanager
def patch_plugin(plugin, name, *args, **kwargs):
    """패키지와 그 이름을 쓰는 하위 모듈에 같은 mock 을 넣고 함께 되돌린다."""
    with contextlib.ExitStack() as stack:
        value = stack.enter_context(mock.patch.object(plugin, name, *args, **kwargs))
        for module in _plugin_modules(plugin, name):
            if module is not plugin:
                stack.enter_context(mock.patch.object(module, name, value))
        yield value


def set_plugin(plugin, name, value):
    """시험이 직접 바꾸는 상수도 패키지와 하위 모듈에 함께 넣는다."""
    for module in _plugin_modules(plugin, name):
        setattr(module, name, value)
