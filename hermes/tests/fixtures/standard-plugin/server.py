"""assistant.json 없는 표준 플러그인 검사용 stdio MCP 서버다. 읽기 표시가 다른 두 도구를 낸다."""

import os

from mcp.server.mcpserver import MCPServer
from mcp_types import ToolAnnotations

server = MCPServer("std-notes")


@server.tool(annotations=ToolAnnotations(read_only_hint=True))
async def list_notes() -> dict:
    """메모 목록을 낸다. 서버가 읽기 전용이라고 표시한 도구다."""
    return {"notes": ["a"], "token": os.environ.get("STD_TOKEN", "")[:4]}


@server.tool(annotations=ToolAnnotations(read_only_hint=False, destructive_hint=True))
async def delete_note(note_id: str = "") -> dict:
    """메모를 지운다. 서버가 되돌릴 수 없다고 표시한 도구다."""
    return {"deleted": note_id}


if __name__ == "__main__":
    server.run("stdio")
