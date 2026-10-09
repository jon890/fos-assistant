"""profile 이름과 reload 밖에 유지하는 프로세스별 FIFO 변환 감독기다."""
import collections
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import selectors
import signal
import struct
import subprocess
import sys
import threading
import time

MAX_OUTPUT = 10 * 1024 * 1024 + 65540


class Runtime:
    def __init__(self):
        self.condition = threading.Condition()
        self.queue = collections.deque()
        self.busy = False

    @contextlib.contextmanager
    def slot(self, deadline, validate):
        ticket = object()
        queue_deadline = min(deadline, time.monotonic() + 15)
        acquired = False
        with self.condition:
            self.queue.append(ticket)
        try:
            while not acquired:
                validate()
                with self.condition:
                    if time.monotonic() >= queue_deadline:
                        raise TimeoutError("conversion queue timeout")
                    if not self.busy and self.queue[0] is ticket:
                        self.busy = acquired = True
                        self.queue.popleft()
                    else:
                        self.condition.wait(min(.5, queue_deadline - time.monotonic()))
            yield
        finally:
            with self.condition:
                if acquired:
                    self.busy = False
                elif ticket in self.queue:
                    self.queue.remove(ticket)
                self.condition.notify_all()


runtime = Runtime()


def shared_runtime():
    # 완성된 객체만 setdefault로 게시한다. CPython의 원자적 dict 삽입을 사용한다.
    name = "_fos_owned_image_runtime_v1"
    existing = sys.modules.get(name)
    if existing is None:
        spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve())
        candidate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(candidate)
        existing = sys.modules.setdefault(name, candidate)
    return existing.runtime


def stop(process):
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=.2)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
    process.wait()


def check_status(url, token, args, deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("image handler deadline")
    request = json.dumps({"url": url + "/validate", "token": token, "args": args,
                          "parent_pid": os.getpid(), "validate": True}).encode()
    if len(request) > 65536:
        raise ValueError("request limit")
    # DNS와 HTTP도 C decode와 같이 강제로 끝낼 수 있는 자식 안에서 수행한다.
    process = subprocess.Popen([sys.executable, str(Path(__file__).with_name("image_helper.py"))],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, start_new_session=True)
    try:
        output, _ = process.communicate(request, timeout=min(1, remaining))
        if process.returncode or len(output) < 4 or len(output) > 65540:
            raise ValueError("validation failed")
        length = struct.unpack("!I", output[:4])[0]
        if length != len(output) - 4 or json.loads(output[4:]) != {"valid": True}:
            raise PermissionError("inspection revoked")
        if time.monotonic() >= deadline:
            raise TimeoutError("image handler deadline")
    finally:
        stop(process)
        for pipe in (process.stdin, process.stdout):
            if not pipe.closed:
                pipe.close()


def supervise(url, token, args, deadline, validate):
    request = json.dumps({"url": url, "token": token, "args": args, "parent_pid": os.getpid()}).encode()
    if len(request) > 65536:
        raise ValueError("request limit")
    with shared_runtime().slot(deadline, validate):
        process = subprocess.Popen([sys.executable, str(Path(__file__).with_name("image_helper.py"))],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, start_new_session=True)
        try:
            output = bytearray()
            offset, next_check = 0, 0
            os.set_blocking(process.stdin.fileno(), False)
            os.set_blocking(process.stdout.fileno(), False)
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdin, selectors.EVENT_WRITE)
                selector.register(process.stdout, selectors.EVENT_READ)
                while selector.get_map():
                    now = time.monotonic()
                    if now >= deadline:
                        raise TimeoutError("image handler timeout")
                    if now >= next_check:
                        validate()
                        next_check = time.monotonic() + .5
                    for key, _ in selector.select(min(.1, max(0, deadline - time.monotonic()))):
                        if key.fileobj is process.stdin:
                            count = os.write(process.stdin.fileno(), request[offset:offset + 4096])
                            offset += count
                            if offset == len(request):
                                selector.unregister(process.stdin)
                                process.stdin.close()
                        else:
                            chunk = os.read(process.stdout.fileno(), 65536)
                            if not chunk:
                                selector.unregister(process.stdout)
                            elif len(output) + len(chunk) > MAX_OUTPUT:
                                raise ValueError("helper output limit")
                            else:
                                output.extend(chunk)
            process.wait(timeout=max(.001, deadline - time.monotonic()))
            validate()
            if time.monotonic() >= deadline:
                raise TimeoutError("image handler deadline")
            if process.returncode or len(output) < 4:
                raise ValueError("helper failed")
            length = struct.unpack("!I", output[:4])[0]
            if length > 65536 or len(output) < 4 + length:
                raise ValueError("helper metadata limit")
            metadata = json.loads(output[4:4 + length])
            body = bytes(output[4 + length:])
            if len(body) > 10 * 1024 * 1024:
                raise ValueError("PNG limit")
            return metadata, body
        finally:
            stop(process)
            for pipe in (process.stdin, process.stdout):
                if not pipe.closed:
                    pipe.close()
