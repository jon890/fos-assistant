"""자기 컴퓨터에서 돌려 Gmail 커넥터에 넣을 refresh token 을 받는다. 표준 라이브러리만 쓴다.

`127.0.0.1` 의 임시 포트를 열어 Google 이 돌려주는 코드를 받고 토큰으로 바꾼다.
refresh token 만 표준 출력에 낸다. 안내하는 글은 표준 오류로 낸다. 값을 파일에 쓰지 않는다.
쓰는 방법은 `docs/connectors/gmail.md` 의 「설정 안내」 가 갖는다.
"""

import base64
import getpass
import hashlib
import http.server
import json
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
SCOPE = " ".join((
    "https://www.googleapis.com/auth/gmail.modify",
    "https://www.googleapis.com/auth/gmail.settings.basic",
))
TIMEOUT_SECONDS = 15
# 브라우저에서 권한을 허용하기를 기다리는 전체 시간이다.
WAIT_SECONDS = 300


class ExchangeError(Exception):
    """코드를 토큰으로 바꾸지 못했다. Google 이 준 글과 자격 증명을 담지 않는다."""


def code_challenge(verifier: str) -> str:
    """PKCE 의 S256 challenge 다. verifier 의 SHA-256 을 패딩 없는 base64url 로 낸다."""
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def authorization_url(client_id: str, redirect_uri: str, state: str, challenge: str) -> str:
    """브라우저로 열 동의 주소다. refresh token 을 받도록 `offline` 과 `consent` 를 넣는다."""
    return AUTH_URL + "?" + urllib.parse.urlencode({
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": SCOPE,
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    })


def exchange_code(token_url: str, client_id: str, client_secret: str, code: str, redirect_uri: str,
                  verifier: str) -> str | None:
    """받은 코드를 토큰으로 바꿔 refresh token 을 돌려준다. 응답에 없으면 None 이다.

    요청이 실패하면 `ExchangeError` 다.
    """
    form = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "code": code,
        "client_id": client_id,
        "client_secret": client_secret,
        "redirect_uri": redirect_uri,
        "code_verifier": verifier,
    }).encode("ascii")
    request = urllib.request.Request(
        token_url, data=form, method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            answer = json.loads(response.read())
    except urllib.error.HTTPError as error:
        error.close()
        raise ExchangeError("토큰 요청이 HTTP %d 로 거절됐다" % error.code) from None
    except (OSError, ValueError):
        raise ExchangeError("토큰 endpoint 에 닿지 못했거나 응답을 읽지 못했다") from None
    token = answer.get("refresh_token") if isinstance(answer, dict) else None
    return token if isinstance(token, str) and token else None


class _Callback(http.server.BaseHTTPRequestHandler):
    """Google 이 브라우저를 돌려보내는 요청을 받는다. `state` 가 맞는 요청만 받아들인다.

    `state` 가 다르거나 없는 요청은 400 으로 답하고 기록하지 않는다. 그 컴퓨터의 다른 프로그램이나
    브라우저가 스스로 보내는 요청(아이콘 등)이 기다리는 것을 끝내지 못한다.
    """

    # 연결만 열고 보내지 않는 상대가 기다림을 붙잡지 못하게 한다.
    timeout = TIMEOUT_SECONDS

    def do_GET(self):
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        accepted = query.get("state", [""])[0] == self.server.state and ("code" in query or "error" in query)
        if accepted:
            self.server.callback = {name: values[0] for name, values in query.items()}
        granted = accepted and "code" in query
        text = "끝났습니다. 이 창을 닫고 터미널로 돌아가세요." if granted else "권한을 받지 못했습니다. 터미널을 확인하세요."
        body = ("<!doctype html><meta charset=\"utf-8\"><p>%s</p>" % text).encode("utf-8")
        self.send_response(200 if granted else 400)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        # 요청 줄에 코드가 들어 있다. 화면에 남기지 않는다.
        pass


def wait_for_callback(listener: http.server.HTTPServer, state: str) -> dict | None:
    """`state` 가 맞는 요청이 올 때까지 기다려 그 query 를 돌려준다. `WAIT_SECONDS` 를 넘기면 None 이다."""
    listener.state = state
    listener.callback = None
    deadline = time.monotonic() + WAIT_SECONDS
    while listener.callback is None:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None
        listener.timeout = remaining
        listener.handle_request()
    return listener.callback


def say(text: str) -> None:
    print(text, file=sys.stderr, flush=True)


def main() -> int:
    say("OAuth 클라이언트 ID: ")
    client_id = input().strip()
    client_secret = getpass.getpass("OAuth 클라이언트 secret(입력이 보이지 않습니다): ").strip()
    if not client_id or not client_secret:
        say("클라이언트 ID 와 secret 이 모두 필요합니다.")
        return 1

    listener = http.server.HTTPServer(("127.0.0.1", 0), _Callback)
    try:
        redirect_uri = "http://127.0.0.1:%d" % listener.server_address[1]
        state = secrets.token_urlsafe(32)
        verifier = secrets.token_urlsafe(64)
        say("")
        say("아래 주소를 브라우저로 열고 연결할 Google 계정으로 로그인하세요.")
        say("")
        say(authorization_url(client_id, redirect_uri, state, code_challenge(verifier)))
        say("")
        say("권한을 허용할 때까지 %d초 동안 기다립니다. 그만두려면 Ctrl-C 를 누르세요." % WAIT_SECONDS)
        callback = wait_for_callback(listener, state)
    finally:
        listener.server_close()

    if callback is None:
        say("%d초 안에 권한을 받지 못했습니다. 스크립트를 다시 돌리세요." % WAIT_SECONDS)
        return 1
    if "code" not in callback:
        say("권한을 받지 못했습니다. 동의 화면에서 허용했는지 확인하세요.")
        return 1
    try:
        refresh_token = exchange_code(TOKEN_URL, client_id, client_secret, callback["code"], redirect_uri, verifier)
    except ExchangeError as error:
        say("토큰을 받지 못했습니다: %s" % error)
        return 1
    if refresh_token is None:
        say("응답에 refresh token 이 없습니다.")
        say("이미 동의한 계정이면 Google 계정의 「서드 파티 앱 및 서비스」 에서 이 앱의 접근을 지우고 다시 하세요.")
        return 1
    say("")
    say("refresh token 입니다. 연결 화면에 붙여 넣은 뒤 터미널 기록에서 지우세요.")
    print(refresh_token)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(1)
