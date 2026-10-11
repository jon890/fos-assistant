"""왕복 fixture의 urllib 시도 계수를 독립 loopback 수신값과 대조한다."""

import http.server
import importlib.util
import json
import pathlib
import threading
import unittest
import urllib.error
import urllib.request


SOURCE = pathlib.Path(__file__).resolve().parents[2] / "test/e2e/financial/guard_plugin_http.py"
SPEC = importlib.util.spec_from_file_location("guard_plugin_http_observation", SOURCE)
FIXTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE)


class GuardPluginHttpObservationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        FIXTURE.Fixture.setUpClass()
        cls.addClassCleanup(FIXTURE.Fixture.doClassCleanups)
        cls.addClassCleanup(FIXTURE.Fixture.tearDownClass)

    def setUp(self):
        self.received = []
        received = self.received

        class Receiver(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def receive(self):
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                received.append((self.command, self.path))
                self.send_response(200)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"{}")

            do_POST = do_DELETE = receive

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Receiver)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close_server)
        self.base = "http://127.0.0.1:%d" % self.server.server_port
        self.fixture = FIXTURE.Fixture()
        self.fixture.prepare(self.base, "observation-dummy")
        self.addCleanup(self.fixture.tearDown)
        self.addCleanup(self.fixture.doCleanups)

    def close_server(self):
        self.server.shutdown()
        self.thread.join(2)
        self.server.server_close()

    def send(self, endpoint, method="POST", body=None):
        request = urllib.request.Request(self.base + endpoint,
                                         data=json.dumps(body or {}).encode(), method=method)
        with urllib.request.urlopen(request, timeout=2) as response:
            self.assertEqual(response.status, 200)

    def test_counts_one_auth_post_without_financial_post(self):
        self.send("/oauth/token")
        observed = self.fixture.inspect()
        self.assertEqual(self.received, [("POST", "/oauth/token")])
        self.assertEqual(observed["authPosts"], 1)
        self.assertEqual(observed["financialPosts"], 0)
        self.assertEqual(observed["financialDeletes"], 0)

    def test_counts_only_financial_destination_post_and_delete(self):
        self.send("/financial/orders")
        self.send("/financial/orders", "DELETE")
        self.send("/internal/connector-executions/support", body={"nonce": "test-nonce"})
        self.send("/fixture/unrelated")
        observed = self.fixture.inspect()
        self.assertEqual(self.received, [("POST", "/financial/orders"),
                                        ("DELETE", "/financial/orders"),
                                        ("POST", "/internal/connector-executions/support"),
                                        ("POST", "/fixture/unrelated")])
        self.assertEqual(observed["financialPosts"], 1)
        self.assertEqual(observed["financialDeletes"], 1)
        self.assertEqual(observed["authPosts"], 0)
        self.assertEqual(observed["nonces"], ["test-nonce"])
        self.assertEqual(observed["supportCodes"], [200])

    def test_counts_auth_attempt_even_when_connection_fails(self):
        closed = http.server.ThreadingHTTPServer(("127.0.0.1", 0),
                                                http.server.BaseHTTPRequestHandler)
        port = closed.server_port
        closed.server_close()
        request = urllib.request.Request("http://127.0.0.1:%d/oauth/token" % port,
                                         data=b"{}", method="POST")
        with self.assertRaises(urllib.error.URLError):
            urllib.request.urlopen(request, timeout=2)
        self.assertEqual(self.fixture.inspect()["authPosts"], 1)
        self.assertEqual(self.fixture.inspect()["financialPosts"], 0)
        self.assertEqual(self.received, [])


if __name__ == "__main__":
    unittest.main()
