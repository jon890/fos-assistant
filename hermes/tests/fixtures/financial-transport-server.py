"""로컬 stdio 시험 서버다. 실제 거래는 하지 않고 받은 원문과 폐기 여부만 검증한다."""

import hashlib
import json
import os
import pathlib
import urllib.request

import anyio
from mcp.server.lowlevel import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool, ToolAnnotations, ListToolsResult, CallToolResult

case = json.loads((pathlib.Path(__file__).parent / "prepare-case.json").read_text())


async def list_tools(context, params):
    return ListToolsResult(tools=[Tool(name=name, input_schema={"type": "object", "additionalProperties": True},
                 annotations=ToolAnnotations(read_only_hint=name != "place_order"))
            for name in ("list_scopes", "prepare_order", "place_order")])


async def call_tool(context, params):
    name, arguments = params.name, params.arguments
    if name == "list_scopes":
        value = {"empty": arguments == {}, "executionEnv": any(key.startswith("FOS_APPROVAL_") for key in os.environ)}
    elif name == "prepare_order":
        if set(arguments) != {"v", "tool", "args"} or arguments["v"] != 1 or arguments["tool"] != "place_order":
            raise ValueError("잘못된 준비 호출이다")
        value = {"v": 1, "executionArgs": json.loads(case["executionArgsJson"]), "summary": json.loads(case["summaryJson"])}
    elif name == "place_order":
        raw = os.environ.pop("FOS_APPROVAL_ARGS_JSON")
        ticket = os.environ.pop("FOS_APPROVAL_TICKET")
        claim = os.environ.pop("FOS_APPROVAL_CLAIM_URL")
        digest = hashlib.sha256(raw.encode()).hexdigest()
        body = {"ticket": ticket, "tool": name, "argsSha256": digest, "scope": {"account_seq": arguments["account_seq"]}}
        with urllib.request.urlopen(urllib.request.Request(claim, data=json.dumps(body).encode(),
                                                          headers={"Content-Type": "application/json"}), timeout=1) as response:
            allowed = json.load(response)
        value = {"argsMatched": json.loads(raw) == arguments, "argsSha256": digest,
                 "cleared": not any(key.startswith("FOS_APPROVAL_") for key in os.environ), "claimed": allowed.get("allowed") is True}
        echo_file = pathlib.Path(__file__).parent / "result-echo.json"
        if echo_file.exists():
            echo = json.loads(echo_file.read_text())
            secret = {"raw": raw, "ticket": ticket, "claim": claim}[echo["secret"]]
            leaked = "prefix:\n한글😀\"" + secret + "\":suffix" if echo["nested"] else secret
            value = {"rows": [{leaked: "echo"} if echo["key"] else {"echo": leaked}]}
            if echo["structured"]:
                return CallToolResult(content=[], structured_content=value)
            text = json.dumps(value, ensure_ascii=echo["unicode"])
            if echo["secret"] == "ticket" and echo["unicode"]:
                text = text.replace(ticket, "".join("\\u%04x" % ord(ch) for ch in ticket))
            return CallToolResult(content=[TextContent(type="text", text=text)])
    else:
        raise ValueError("등록하지 않은 도구다")
    return CallToolResult(content=[TextContent(type="text", text=json.dumps(value, ensure_ascii=False))])


async def main():
    server = Server("financial-transport", on_list_tools=list_tools, on_call_tool=call_tool)
    async with stdio_server() as (read, write):
        await server.run(read, write, server.create_initialization_options())


anyio.run(main)
