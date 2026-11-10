import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { startFakeHermes, FAKE_USAGE } from "../e2e/fake-hermes.ts";
import { eventEnvelope, HERMES_VERSION, runEventWriter, wireEvents, wireFixture } from "../e2e/fake-hermes/wire.ts";

test("기준 버전은 Python 계약에서 읽고 공유 wire의 모든 사건에 봉투를 붙인다", () => {
  const contract = readFileSync(new URL("../../hermes/tests/hermes_contract.py", import.meta.url), "utf8");
  assert.equal(HERMES_VERSION, /^HERMES_VERSION = "([^"]+)"$/m.exec(contract)?.[1]);
  const samples = wireEvents(wireFixture("events"));
  assert.equal(samples.length, 14);
  for (const sample of samples) {
    const event = eventEnvelope({ run_id: "submitted-run", output: "완료 답", usage: FAKE_USAGE }, sample);
    assert.equal(event.run_id, "submitted-run");
    assert.equal(typeof event.timestamp, "number");
    assert.equal(event.event, sample.event);
    if (sample.event === "approval.request") {
      assert.ok(Object.keys(event).indexOf("command") < Object.keys(event).indexOf("event"));
    }
  }
  const completed = eventEnvelope({ run_id: "submitted-run", output: "완료 답", usage: FAKE_USAGE }, { event: "run.completed" });
  assert.equal(completed.output, "완료 답");
  assert.deepEqual(completed.usage, FAKE_USAGE);
  assert.throws(() => eventEnvelope({ run_id: "r", output: "", usage: FAKE_USAGE }, { event: "invented.event" }), /사건 표본이 없다/);
  const run = { run_id: "r", output: "", usage: FAKE_USAGE };
  for (const event of ["message.delta", "message.interim", "reasoning.available"]) {
    assert.throws(() => eventEnvelope(run, { event }), /내용 칸이 없다/);
  }
  for (const event of ["subagent.start", "subagent.complete"]) {
    assert.deepEqual(eventEnvelope(run, { event, timestamp: 1 }), { event, run_id: "r", timestamp: 1, seq: 0 });
  }
  assert.deepEqual(eventEnvelope(run, { event: "tool.completed", tool: "test", timestamp: 1 }), {
    event: "tool.completed", run_id: "r", timestamp: 1, duration: 0, error: false, preview: "",
    tool: "test", seq: 0,
  });
  assert.equal(eventEnvelope(run, { event: "run.steered" }).accepted, true);
});

test("SSE writer는 실행별 id와 seq를 0부터 붙이고 transport 안내에는 붙이지 않는다", () => {
  const frames: string[] = [];
  const response = { write: (frame: string) => frames.push(frame) };
  const run = { run_id: "r", output: "답", usage: FAKE_USAGE };
  const write = runEventWriter(response as Parameters<typeof runEventWriter>[0], run);
  const first = write({ event: "message.delta", delta: "답", seq: 99 });
  const notice = write({ event: "replay.truncated", oldest_retained_seq: 0 });
  const second = write({ event: "run.completed" });
  assert.equal(first.seq, 0);
  assert.equal(second.seq, 1);
  assert.equal(notice.seq, undefined);
  assert.ok(frames[0]!.startsWith("id: 0\ndata: "));
  assert.ok(frames[1]!.startsWith("data: "));
  assert.ok(frames[2]!.startsWith("id: 1\ndata: "));
  assert.equal(runEventWriter(response as Parameters<typeof runEventWriter>[0], run)({ event: "run.completed" }).seq, 0);
});

test("대역 HTTP 사건에는 제출한 실행 번호와 timestamp 및 완료 결과가 실린다", async () => {
  const hermes = await startFakeHermes({ browser: "example-key" });
  const headers = { Authorization: "Bearer example-key" };
  try {
    const submitted = await fetch(`${hermes.baseUrl}/p/browser/v1/runs`, {
      method: "POST", headers, body: JSON.stringify({ input: "봉투 검사" }),
    });
    assert.equal(submitted.status, 200);
    const { run_id } = await submitted.json();
    const response = await fetch(`${hermes.baseUrl}/p/browser/v1/runs/${run_id}/events`, { headers });
    const wire = await response.text();
    const events = wireEvents(wire);
    assert.deepEqual(events.map((event) => event.seq), events.map((_, index) => index));
    assert.deepEqual(wire.split("\n").filter((line) => line.startsWith("id:")), events.map((_, index) => `id: ${index}`));
    assert.ok(events.length > 0);
    for (const event of events) {
      assert.equal(event.run_id, run_id);
      assert.equal(typeof event.timestamp, "number");
      if (event.event === "tool.completed") assert.equal(typeof event.preview, "string");
    }
    const status = await (await fetch(`${hermes.baseUrl}/p/browser/v1/runs/${run_id}`, { headers })).json();
    assert.equal(events.at(-1)?.output, status.output);
    assert.deepEqual(events.at(-1)?.usage, status.usage);
    assert.deepEqual(events.at(-1)?.runtime, status.runtime);
  } finally {
    await hermes.close();
  }
});

test("대본도 공유 표본의 희귀 사건과 중간 안내 식별 시각을 그대로 보낸다", async () => {
  const hermes = await startFakeHermes({ browser: "example-key" });
  const headers = { Authorization: "Bearer example-key" };
  try {
    const samples = wireEvents(wireFixture("events")).filter((sample) => !String(sample.event).startsWith("run."));
    await fetch(`${hermes.baseUrl}/__test/script`, {
      method: "POST", body: JSON.stringify({ input: "희귀 사건 검사", output: "대본 답", events: samples }),
    });
    const { run_id } = await (await fetch(`${hermes.baseUrl}/p/browser/v1/runs`, {
      method: "POST", headers, body: JSON.stringify({ input: "희귀 사건 검사" }),
    })).json();
    const events = wireEvents(await (await fetch(`${hermes.baseUrl}/p/browser/v1/runs/${run_id}/events`, { headers })).text());
    assert.deepEqual(events.slice(0, samples.length), samples.map((sample, index) => ({
      ...sample, run_id, seq: index,
    })));
    assert.equal(events.at(-1)?.output, "대본 답");
    await fetch(`${hermes.baseUrl}/__test/script`, {
      method: "POST", body: JSON.stringify({ input: "실행 중단 검사", events: [{ event: "run.interrupted" }] }),
    });
    const interrupted = await (await fetch(`${hermes.baseUrl}/p/browser/v1/runs`, {
      method: "POST", headers, body: JSON.stringify({ input: "실행 중단 검사" }),
    })).json();
    const terminal = wireEvents(await (await fetch(`${hermes.baseUrl}/p/browser/v1/runs/${interrupted.run_id}/events`, { headers })).text());
    assert.deepEqual(terminal.map((event) => event.event), ["run.interrupted"]);
    const status = await (await fetch(`${hermes.baseUrl}/p/browser/v1/runs/${interrupted.run_id}`, { headers })).json();
    assert.equal(status.status, "interrupted");
    assert.equal(status.error, terminal[0]?.error);
    const failure = "합성 대본 실패 사유";
    await fetch(`${hermes.baseUrl}/__test/script`, {
      method: "POST", body: JSON.stringify({ input: "실행 실패 검사", events: [{ event: "run.failed", error: failure }] }),
    });
    const failed = await (await fetch(`${hermes.baseUrl}/p/browser/v1/runs`, {
      method: "POST", headers, body: JSON.stringify({ input: "실행 실패 검사" }),
    })).json();
    const failedEvents = wireEvents(await (await fetch(`${hermes.baseUrl}/p/browser/v1/runs/${failed.run_id}/events`, { headers })).text());
    assert.deepEqual(failedEvents.map((event) => event.event), ["run.failed"]);
    const failedStatus = await (await fetch(`${hermes.baseUrl}/p/browser/v1/runs/${failed.run_id}`, { headers })).json();
    assert.equal(failedStatus.status, "failed");
    assert.equal(failedStatus.error, failure);
  } finally {
    await hermes.close();
  }
});
