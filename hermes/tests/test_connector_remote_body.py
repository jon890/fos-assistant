"""원격 MCP 본문을 SDK 가 모으기 전에 끊고 SSE 는 이벤트마다 세는지 검사한다."""

import asyncio
import gzip
import itertools
import sys
from unittest import mock

import httpx2

from test_connector_manifest import ConnectorGateCase


class RemoteBodyLimitTest(ConnectorGateCase):
    def test_compressed_response_is_closed_before_decoding(self):
        module = sys.modules[self.plugin.__name__ + ".connector_remote_body"]
        compressed = gzip.compress(b"x" * (11 * 1024 * 1024))
        self.assertLess(len(compressed), module.REMOTE_MAX_BODY_BYTES)

        async def run(encoding):
            consumed, closed = [], []

            class Stream(httpx2.AsyncByteStream):
                async def __aiter__(self):
                    consumed.append(True)
                    yield compressed

                async def aclose(self):
                    closed.append(True)

            response = httpx2.Response(200, headers={"content-encoding": encoding}, stream=Stream())
            async with httpx2.AsyncClient(transport=httpx2.MockTransport(lambda request: response),
                                         event_hooks={"response": [module._cap_remote_response]}) as client:
                with self.assertRaisesRegex(httpx2.ReadError, "압축 형식"):
                    await client.get("https://remote.test/mcp")
            self.assertEqual(consumed, [])
            self.assertTrue(closed)

        for encoding in ("gzip", "GZIP", "br", "identity, gzip"):
            with self.subTest(encoding=encoding):
                asyncio.run(run(encoding))

    def test_response_limit_before_buffering_and_stream_cleanup(self):
        module = sys.modules[self.plugin.__name__ + ".connector_remote_body"]
        self.assertEqual(module.REMOTE_MAX_BODY_BYTES, 10 * 1024 * 1024)

        async def run(chunks, headers, rejected):
            closed, consumed = [], []

            class Stream(httpx2.AsyncByteStream):
                async def __aiter__(self):
                    for chunk in chunks:
                        consumed.append(chunk)
                        yield chunk

                async def aclose(self):
                    closed.append(True)

            response = httpx2.Response(200, headers=headers, stream=Stream())
            transport = httpx2.MockTransport(lambda request: response)
            async with httpx2.AsyncClient(transport=transport, event_hooks={
                    "response": [module._cap_remote_response]}) as client:
                async def read():
                    async with client.stream("GET", "https://remote.test/mcp") as result:
                        return await result.aread()

                if rejected:
                    with self.assertRaises(httpx2.ReadError) as error:
                        await read()
                    self.assertNotIn("remote.test", str(error.exception))
                else:
                    self.assertEqual(await read(), b"".join(chunks))
            self.assertTrue(closed)
            return consumed

        with mock.patch.object(module, "REMOTE_MAX_BODY_BYTES", 16):
            for headers in ({}, {"content-length": "invalid"}, {"content-encoding": "identity"}):
                asyncio.run(run([b"x" * 8, b"x" * 8], headers, False))
                asyncio.run(run([b"x" * 8, b"x" * 9], headers, True))
            self.assertEqual(asyncio.run(run([b"x"], {"content-length": "17"}, True)), [])
            sse = {"content-type": "text/event-stream; charset=utf-8"}
            # CR, LF, CRLF 를 섞은 경계와 청크가 갈리는 모든 위치를 검사한다.
            for first, second in itertools.product((b"\r", b"\n", b"\r\n"), repeat=2):
                boundary = first + second
                if boundary == b"\r\n":
                    continue
                event = b"data: x" + boundary
                body = event * 8
                asyncio.run(run([bytes([byte]) for byte in body], sse, False))
                for split in range(1, len(body)):
                    with self.subTest(boundary=boundary, split=split):
                        asyncio.run(run([body[:split], body[split:]], sse, False))
            asyncio.run(run([b"data: " + b"x" * 11, b"\n\n"], sse, True))
            asyncio.run(run([(b"data: " + b"x" * 11 + b"\n\n") * 2], sse, True))
