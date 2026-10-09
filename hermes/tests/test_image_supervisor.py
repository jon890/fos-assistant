"""실제 자식 프로세스의 timeout·권한 거절과 pipe·슬롯 회수를 확인한다."""
import importlib
import io
import json
import os
import signal
import subprocess
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from PIL import Image
from test_fos_ctx import load_ctx


class ImageSupervisorTest(unittest.TestCase):
    def setUp(self):
        plugin = load_ctx(self.addCleanup)
        self.module = importlib.import_module(plugin.__name__ + ".image_runtime")
        self.created = []
        self.real_popen = subprocess.Popen

    def start_hung(self, args, **kwargs):
        self.assertNotIn("fake-token", " ".join(args))
        process = self.real_popen([sys.executable, "-c",
            "import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(60)"], **kwargs)
        self.created.append(process)
        return process

    def assert_reaped(self):
        self.assertIsNotNone(self.created[0].returncode)
        self.assertTrue(self.created[0].stdin.closed)
        self.assertTrue(self.created[0].stdout.closed)
        self.assertFalse(self.module.shared_runtime().busy)
        self.assertEqual(len(self.module.shared_runtime().queue), 0)

    def test_timeout_kills_waits_and_releases_after_ignored_terminate(self):
        with patch.object(self.module.subprocess, "Popen", side_effect=self.start_hung):
            with self.assertRaises(TimeoutError):
                self.module.supervise("http://example.test", "fake-token", {}, time.monotonic() + .4, lambda: None)
        self.assertEqual(self.created[0].returncode, -signal.SIGKILL)
        self.assert_reaped()

    def test_mid_conversion_revocation_discards_result_and_reaps(self):
        start = time.monotonic()
        def validate():
            if time.monotonic() - start > .3:
                raise PermissionError("revoked")
        with patch.object(self.module.subprocess, "Popen", side_effect=self.start_hung):
            with self.assertRaises(PermissionError):
                self.module.supervise("http://example.test", "fake-token", {"overview": True}, start + 3, validate)
        self.assert_reaped()

    def test_real_http_mime_caps_and_magic_and_first_frame(self):
        png = b"\x89PNG\r\n\x1a\nmore"
        gif = io.BytesIO()
        Image.new("RGB", (8, 6), "red").save(gif, format="GIF")
        class Handler(BaseHTTPRequestHandler):
            mime, body, length = "image/png", png, len(png)
            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                self.send_response(200)
                self.send_header("Content-Type", self.mime)
                self.send_header("Content-Length", str(self.length))
                self.end_headers()
                self.wfile.write(self.body)
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = "http://127.0.0.1:" + str(server.server_port)
            metadata, body = self.module.supervise(url, "fake-token", {}, time.monotonic() + 3, lambda: None)
            self.assertEqual(metadata["mime"], "image/png")
            self.assertEqual(body, png)
            for mime, length, body in [("image/png", len(png) + 1, png),
                                       ("image/png", 10 * 1024 * 1024 + 1, b""),
                                       ("image/gif", 20 * 1024 * 1024 + 1, b""),
                                       ("image/png", 3, b"bad"), ("text/plain", 3, b"bad")]:
                Handler.mime, Handler.length, Handler.body = mime, length, body
                metadata, result = self.module.supervise(url, "fake-token", {}, time.monotonic() + 3, lambda: None)
                self.assertEqual(metadata["code"], "original_unavailable")
                self.assertEqual(result, b"")
            Handler.mime, Handler.body, Handler.length = "image/gif", gif.getvalue(), len(gif.getvalue())
            metadata, result = self.module.supervise(url, "fake-token", {}, time.monotonic() + 3, lambda: None)
            if sys.platform == "linux":
                self.assertTrue(metadata["first_frame"])
                self.assertTrue(result.startswith(b"\x89PNG"))
            else:
                self.assertEqual(metadata["code"], "original_unavailable")
            if sys.platform == "linux":
                for format, mime in [("JPEG", "image/jpeg"), ("PNG", "image/png"),
                                     ("GIF", "image/gif"), ("WEBP", "image/webp")]:
                    original = io.BytesIO()
                    Image.new("RGB", (2000, 1000), "red").save(original, format=format)
                    Handler.mime, Handler.body = mime, original.getvalue()
                    Handler.length = len(Handler.body)
                    metadata, result = self.module.supervise(url, "fake-token", {"overview": True},
                                                             time.monotonic() + 5, lambda: None)
                    self.assertTrue(metadata["overview"])
                    self.assertEqual(metadata["display_width"], 2000)
                    self.assertEqual(metadata["result_width"], 1600)
                    self.assertTrue(result.startswith(b"\x89PNG"))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    @unittest.skipUnless(sys.platform == "linux", "Linux의 실제 주소 공간·부모 종료 계약이다")
    def test_memory_limit_applies_and_parent_death_kills_helper(self):
        helper = importlib.import_module("image_helper")
        child = self.real_popen([sys.executable, "-c",
            "import sys,os; sys.path.insert(0,sys.argv[1]); from image_helper import configure_limits; "
            "configure_limits(os.getppid()); import resource; print(resource.getrlimit(resource.RLIMIT_AS),flush=True); "
            "bytearray(2*1024*1024*1024)", str(self.module.Path(self.module.__file__).parent)],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        output = child.communicate(timeout=5)[0]
        self.assertIn(b"1610612736", output)
        self.assertNotEqual(child.returncode, 0)

    def test_validation_http_is_supervised_and_cannot_hang_handler(self):
        with patch.object(self.module.subprocess, "Popen", side_effect=self.start_hung):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.module.check_status("http://example.test", "fake-token", {}, time.monotonic() + .3)
        self.assert_reaped()

    @unittest.skipUnless(sys.platform == "linux", "Linux의 실제 부모 종료 계약이다")
    def test_parent_death_signal_stops_child_even_after_parent_sigkill(self):
        import pathlib
        plugin_directory = str(pathlib.Path(self.module.__file__).parent)
        child_script = "import sys,os,time; sys.path.insert(0,sys.argv[1]); from image_helper import configure_limits; configure_limits(os.getppid()); print('ready',flush=True); time.sleep(60)"
        parent_script = "import subprocess,sys,time; p=subprocess.Popen([sys.executable,'-c',sys.argv[1],sys.argv[2]]); print(p.pid,flush=True); time.sleep(60)"
        parent = self.real_popen([sys.executable, "-c", parent_script, child_script, plugin_directory], stdout=subprocess.PIPE)
        child_pid = int(parent.stdout.readline())
        try:
            self.assertEqual(parent.stdout.readline().strip(), b"ready")
            parent.kill()
            parent.wait(timeout=3)
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                proc = pathlib.Path("/proc") / str(child_pid) / "stat"
                if not proc.exists() or proc.read_text().split()[2] == "Z":
                    break
                time.sleep(.01)
            else:
                self.fail("helper remained active after parent exit")
        finally:
            if parent.poll() is None:
                parent.kill()
            parent.wait()
            parent.stdout.close()
            try:
                os.kill(child_pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
