"""TypeScript OAuth 시험이 Python 보조 스크립트를 실제 경계에서 부르는 대역이다."""
import importlib.util
import io
import json
import sys
import threading
import urllib.error
import urllib.request
from http.server import HTTPServer


def load(path):
    spec = importlib.util.spec_from_file_location("gmail_oauth", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def callback(module):
    listener = HTTPServer(("127.0.0.1", 0), module._Callback)
    result = []
    thread = threading.Thread(target=lambda: result.append(module.wait_for_callback(listener, "right")))
    thread.start()
    base = "http://127.0.0.1:%d" % listener.server_address[1]
    for query in ("state=wrong&code=ignored", "state=right&error=access_denied"):
        try:
            urllib.request.urlopen(base + "?" + query)
        except urllib.error.HTTPError:
            pass
    thread.join(2)
    listener.server_close()
    print(json.dumps(result[0]))


def exchange_error(module):
    def reject(*args, **kwargs):
        raise urllib.error.HTTPError("https://token.invalid", 400, "secret-detail", {}, io.BytesIO(b"secret-body"))
    urllib.request.urlopen = reject
    try:
        module.exchange_code("https://token.invalid", "id", "secret", "code", "http://callback", "verifier")
    except module.ExchangeError as error:
        print(str(error))


def timeout(module):
    class Listener:
        callback = None
        timeout = 0

        def handle_request(self):
            pass

    ticks = iter((0, module.WAIT_SECONDS + 1))
    module.time.monotonic = lambda: next(ticks)
    print(module.wait_for_callback(Listener(), "state"))


module = load(sys.argv[2])
{"callback": callback, "exchange-error": exchange_error, "timeout": timeout}[sys.argv[1]](module)
