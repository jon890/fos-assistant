"""v0.21.6의 실제 SSE transport를 실행해 공유 합성 fixture와 대조한다."""

import ast
import asyncio
from collections import deque
from contextlib import suppress
import json
import logging
import os
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Dict, Optional
import unittest

import hermes_contract as contract


@unittest.skipUnless(os.environ.get("HERMES_SOURCE"), "HERMES_SOURCE가 없어 SSE source 검사를 건너뛴다")
class RunsStreamContractTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        source = Path(os.environ["HERMES_SOURCE"])
        namespace = dict(asyncio=asyncio, deque=deque, suppress=suppress, json=json,
            Any=Any, Dict=Dict, Optional=Optional, logger=logging.getLogger("runs-contract"),
            time=SimpleNamespace(time=lambda: 3.0))
        selections = {
            "gateway/platforms/api_server_runs.py": {
                "_RunStream", "_run_event", "_handle_run_events",
                "_RUN_STREAM_SUBSCRIBER_OVERFLOW", "_RUN_STREAM_WRITE_TIMEOUT"},
            "gateway/platforms/api_server.py": {"_sse_frame"},
        }
        for relative, names in selections.items():
            tree = ast.parse((source / relative).read_bytes())
            nodes = [node for node in tree.body if getattr(node, "name", None) in names
                or isinstance(node, ast.Assign) and any(
                    isinstance(target, ast.Name) and target.id in names for target in node.targets)]
            exec(compile(ast.Module(body=nodes, type_ignores=[]), relative, "exec"), namespace)
        self.namespace = namespace

    async def render(self, samples, *, backlog_limit=None):
        stream = self.namespace["_RunStream"]()
        if backlog_limit is not None:
            stream.backlog = deque(maxlen=backlog_limit)
        for sample in samples:
            stream.put_nowait({key: value for key, value in sample.items() if key != "seq"})
        stream.put_nowait(None)
        frames = []

        class Response:
            def __init__(self, **kwargs):
                pass

            async def prepare(self, request):
                pass

            async def write(self, frame):
                frames.append(frame.decode())

        self.namespace["web"] = SimpleNamespace(StreamResponse=Response)
        owner = SimpleNamespace(_check_auth=lambda request: None,
            _request_owns_run=lambda request, run_id: True,
            _run_streams={"fixture-run": stream}, _sse_headers=lambda request: {},
            _release_run_owner_if_forgotten=lambda run_id: None)
        request = SimpleNamespace(match_info={"run_id": "fixture-run"}, headers={}, query={})
        api = SimpleNamespace(_sse_frame=self.namespace["_sse_frame"])
        await self.namespace["_handle_run_events"](owner, request, _api_server=api)
        self.assertFalse(stream.subscribers)
        return frames

    async def test_actual_transport_assigns_ids_and_sequence_to_fixture_events(self):
        fixture = Path(__file__).resolve().parents[2] / (
            "backend/src/test/resources/hermes/%s/events.sse" % contract.HERMES_VERSION)
        samples = []
        for frame in fixture.read_text().split("\n\n"):
            data = [line for line in frame.splitlines() if line.startswith("data:")]
            self.assertLessEqual(len(data), 1, "각 합성 사건은 빈 줄로 구분해야 한다")
            if data:
                samples.append(json.loads(data[0][5:]))
        frames = await self.render(samples)
        self.assertEqual(": open\n\n", frames[0])
        self.assertEqual(": stream closed\n\n", frames[-1])
        for sequence, (frame, sample) in enumerate(zip(frames[1:-1], samples, strict=True)):
            lines = frame.splitlines()
            self.assertEqual("id: " + str(sequence), lines[0])
            self.assertEqual(sample, json.loads(lines[1][5:]))

    async def test_first_subscription_can_receive_unsequenced_truncation_notice(self):
        samples = [{"event": "message.delta", "run_id": "fixture-run", "timestamp": 1, "delta": "앞"},
                   {"event": "run.completed", "run_id": "fixture-run", "timestamp": 2, "output": "답"}]
        frames = await self.render(samples, backlog_limit=2)
        notice = json.loads(frames[1].splitlines()[0][5:])
        self.assertEqual({"event": "replay.truncated", "run_id": "fixture-run", "timestamp": 3.0,
                          "oldest_retained_seq": 1}, notice)
        self.assertTrue(frames[2].startswith("id: 1\n"))
