"""검사용 커넥터의 stdio MCP 서버다. 토큰 값에 따라 정해 둔 결과를 낸다.

대시보드 plugin 이 자식 프로세스로 띄운다. 값은 인자가 아니라 환경 변수로만 받는다.
"""

import json
import os

import anyio
from mcp.server.mcpserver import MCPServer
from mcp_types import CallToolResult, TextContent, ToolAnnotations

server = MCPServer("demo")

# 시간 제한 검사가 쓴다. 대시보드의 제한보다 훨씬 길어야 한다.
SLOW_SECONDS = 60


def failure(code: str) -> CallToolResult:
    """도구 실패 모양이다. `isError` 와 오류 코드를 담은 첫 텍스트 칸이다."""
    return CallToolResult(
        content=[TextContent(type="text", text=json.dumps({"error": {"code": code}}))],
        is_error=True,
    )


@server.tool(annotations=ToolAnnotations(read_only_hint=True), structured_output=False)
async def list_scopes() -> CallToolResult:
    """토큰으로 볼 수 있는 범위를 낸다."""
    token = os.environ.get("DEMO_TOKEN", "")
    if token == "demo_ok_0123456789":
        body = {"scopes": [{"id": "a", "name": "A"}]}
        return CallToolResult(content=[TextContent(type="text", text=json.dumps(body))])
    if token == "demo_bad_0123456789":
        return failure("DEMO_UNAUTHORIZED")
    if token == "demo_odd_0123456789":
        return failure("DEMO_NOT_IN_TABLE")
    if token == "demo_lost_0123456789":
        # `errors` 표에서 `outcome_unknown` 에 이은 코드다.
        return failure("DEMO_UNKNOWN")
    if token == "demo_slow_0123456789":
        await anyio.sleep(SLOW_SECONDS)
    return failure("DEMO_UNAVAILABLE")


@server.tool(annotations=ToolAnnotations(read_only_hint=True))
async def env_view() -> dict:
    """자식이 받은 환경 변수의 이름과 PATH, 주인의 첨부 디렉터리를 낸다. 칸 값은 내지 않는다.

    `attachments` 는 `owner_attachments_env` 를 `DEMO_ATTACHMENT_DIR` 로 선언한 시험이 받은 값이다. 없으면 null 이다.
    """
    return {"names": sorted(os.environ), "path": os.environ.get("PATH", ""),
            "attachments": os.environ.get("DEMO_ATTACHMENT_DIR")}


@server.tool(annotations=ToolAnnotations(read_only_hint=False))
async def write_note(text: str = "") -> dict:
    """쓰는 도구다. 대시보드의 `call` 이 이 도구를 부르면 안 된다.

    받은 인자와 토큰의 앞 4자를 그대로 돌려준다. 실행 경로의 검사가 인자와 env 가 닿았는지 본다.
    """
    return {"written": True, "text": text, "token": os.environ.get("DEMO_TOKEN", "")[:4]}


@server.tool(annotations=ToolAnnotations(read_only_hint=False), structured_output=False)
async def append_line() -> CallToolResult:
    """쓰는 도구다. 구조화 결과도 JSON 도 아닌 평문만 돌려준다."""
    return CallToolResult(content=[TextContent(type="text", text="appended one line")])


if __name__ == "__main__":
    server.run("stdio")
