"""검사용 원격 MCP 서버다. Streamable HTTP 로 loopback 의 빈 포트에서 돈다.

대시보드 plugin 이 원격 플러그인에 붙는 경로를 검사할 때 `running_remote_server()` 로 띄운다.
서버의 로그가 검사 프로세스의 로그에 섞이지 않게 따로 프로세스로 띄우고, 고른 포트를 첫 줄로 받는다.
"""

import contextlib
import json
import os
import socket
import subprocess
import sys
import urllib.request

# 서버 프로세스가 포트를 알릴 때까지 기다리는 시간이다.
START_SECONDS = 20


def build_server():
    """도구와 경로를 단 MCP 서버다. 모든 도구는 받은 `Authorization` 헤더를 결과에 싣는다."""
    from mcp.server.mcpserver import Context, MCPServer
    from mcp_types import ToolAnnotations
    from starlette.responses import JSONResponse, RedirectResponse

    server = MCPServer("remote")
    # 서버가 받은 요청 수다. 부르지 않아야 하는 도구와 redirect 대상이 요청을 받았는지 검사가 읽는다.
    counts = {"landing": 0, "write_item": 0}

    def authorization(ctx: Context) -> str:
        headers = ctx.headers or {}
        return headers.get("authorization", "")

    @server.tool(annotations=ToolAnnotations(read_only_hint=True))
    async def list_items(ctx: Context) -> dict:
        """확인 도구다."""
        return {"authorization": authorization(ctx)}

    @server.tool(annotations=ToolAnnotations(read_only_hint=False))
    async def write_item(ctx: Context, text: str = "") -> dict:
        """쓰기 도구다. 받은 인자를 그대로 돌려준다."""
        counts["write_item"] += 1
        return {"authorization": authorization(ctx), "text": text}

    @server.tool(annotations=ToolAnnotations(read_only_hint=False))
    async def hang_up(ctx: Context) -> dict:
        """요청을 받은 뒤 답하지 않고 프로세스째 끝난다. 연결이 응답 없이 끊긴다."""
        os._exit(0)

    @server.custom_route("/moved", methods=["GET", "POST", "DELETE"])
    async def moved(request):
        """다른 주소로 보내는 redirect 다."""
        return RedirectResponse("/landing", status_code=307)

    @server.custom_route("/landing", methods=["GET", "POST", "DELETE"])
    async def landing_route(request):
        """redirect 대상이다. 요청을 받은 수만 센다."""
        counts["landing"] += 1
        return JSONResponse({"jsonrpc": "2.0", "id": 1, "result": {}})

    @server.custom_route("/hits", methods=["GET"])
    async def hits(request):
        """지금까지 받은 요청 수다."""
        return JSONResponse(counts)

    return server


def serve():
    """빈 포트에 묶고 그 번호를 표준 출력 첫 줄로 알린 뒤 요청을 받는다."""
    import uvicorn

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1], flush=True)
    app = build_server().streamable_http_app()
    config = uvicorn.Config(app, log_level="critical", access_log=False, lifespan="on")
    uvicorn.Server(config).run(sockets=[sock])


@contextlib.contextmanager
def running_remote_server():
    """서버 프로세스를 띄우고 `http://127.0.0.1:<포트>` 를 내준다. 나갈 때 프로세스를 끝낸다."""
    process = subprocess.Popen([sys.executable, os.path.abspath(__file__)], stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, text=True)
    try:
        line = process.stdout.readline().strip()
        if not line.isdigit():
            raise RuntimeError("검사용 원격 MCP 서버가 포트를 알리지 못했다")
        base = "http://127.0.0.1:%s" % line
        _wait_until_ready(base)
        yield base
    finally:
        process.kill()
        process.wait(timeout=START_SECONDS)
        process.stdout.close()


def _wait_until_ready(base):
    """`/hits` 가 답할 때까지 기다린다. 포트를 묶은 뒤 앱이 뜨기까지 잠깐 걸린다."""
    import time

    deadline = time.monotonic() + START_SECONDS
    while True:
        try:
            received(base)
            return
        except OSError:
            if time.monotonic() > deadline:
                raise
            time.sleep(0.05)


def received(base) -> dict:
    """redirect 대상(`landing`)과 쓰기 도구(`write_item`)가 지금까지 받은 요청 수다."""
    with urllib.request.urlopen(base + "/hits", timeout=5) as response:
        return json.loads(response.read())


if __name__ == "__main__":
    serve()
