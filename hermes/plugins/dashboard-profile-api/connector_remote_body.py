"""SDK 가 원격 MCP 응답을 버퍼에 모으기 전에 크기를 제한한다.

Hermes v0.21.6 tools/mcp_tool_errors.py 의 _make_mcp_body_cap_transport 와 같은 상한이다.
프로세스의 proxy 설정도 그대로 쓰도록 transport 교체 대신 응답 hook 으로 스트림을 감싼다.
"""

import contextlib
import re


REMOTE_MAX_BODY_BYTES = 10 * 1024 * 1024
# SSE 는 빈 줄에서 이벤트를 끝낸다. CR, LF, CRLF 와 청크 사이에 갈린 경계를 모두 받는다.
_SSE_BOUNDARY_RE = re.compile(rb"(?:\r\n|\r(?!\n)|\n){2}")


async def _cap_remote_response(response):
    """유한 본문과 SSE 이벤트 하나마다 10 MiB 를 넘으면 주소 없는 ReadError 로 끊는다."""
    import httpx2

    limit = REMOTE_MAX_BODY_BYTES
    declared = response.headers.get("content-length")
    with contextlib.suppress(ValueError):
        if declared is not None and int(declared) > limit:
            await response.aclose()
            raise httpx2.ReadError("원격 MCP 응답의 크기가 상한을 넘는다")
    is_sse = "text/event-stream" in response.headers.get("content-type", "").lower()

    class CappedStream(httpx2.AsyncByteStream):
        def __init__(self, inner):
            self.inner = inner

        async def __aiter__(self):
            counted, tail = 0, b""
            async for chunk in self.inner:
                if is_sse:
                    window, pos = tail + chunk, 0
                    for match in _SSE_BOUNDARY_RE.finditer(window):
                        end = match.end()
                        if end <= len(tail):
                            continue
                        if counted + end - max(pos, len(tail)) > limit:
                            raise httpx2.ReadError("원격 MCP SSE 이벤트의 크기가 상한을 넘는다")
                        counted, pos = 0, end
                    counted += len(window) - max(pos, len(tail))
                    tail = window[-3:]
                else:
                    counted += len(chunk)
                if counted > limit:
                    raise httpx2.ReadError("원격 MCP 응답의 크기가 상한을 넘는다")
                yield chunk

        async def aclose(self):
            await self.inner.aclose()

    response.stream = CappedStream(response.stream)
