import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { startFakeHermes, FAKE_USAGE } from "../e2e/fake-hermes.ts";
import { eventEnvelope, HERMES_VERSION, wireEvents, wireFixture } from "../e2e/fake-hermes/wire.ts";

test("기준 판은 Python 계약에서 읽고 공유 wire의 모든 사건에 봉투를 붙인다", () => {
  const contract = readFileSync(new URL("../../hermes/tests/hermes_contract.py", import.meta.url), "utf8");
  assert.equal(HERMES_VERSION, /^HERMES_VERSION = "([^"]+)"$/m.exec(contract)?.[1]);
  const samples = wireEvents(wireFixture("events"));
  assert.equal(samples.length, 13);
  for (const sample of samples) {
    const event = eventEnvelope({ run_id: "submitted-run", output: "완료 답", usage: FAKE_USAGE }, { event: sample.event });
    assert.equal(event.run_id, "submitted-run");
    assert.equal(typeof event.timestamp, "number");
    assert.equal(event.event, sample.event);
  }
  const completed = eventEnvelope({ run_id: "submitted-run", output: "완료 답", usage: FAKE_USAGE }, { event: "run.completed" });
  assert.equal(completed.output, "완료 답");
  assert.deepEqual(completed.usage, FAKE_USAGE);
  assert.throws(() => eventEnvelope({ run_id: "r", output: "", usage: FAKE_USAGE }, { event: "invented.event" }), /사건 표본이 없다/);
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
    const events = wireEvents(await response.text());
    assert.ok(events.length > 0);
    for (const event of events) {
      assert.equal(event.run_id, run_id);
      assert.equal(typeof event.timestamp, "number");
      if (event.event === "tool.completed") assert.equal(typeof event.preview, "string");
    }
    const status = await (await fetch(`${hermes.baseUrl}/p/browser/v1/runs/${run_id}`, { headers })).json();
    assert.equal(events.at(-1)?.output, status.output);
    assert.deepEqual(events.at(-1)?.usage, status.usage);
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
    assert.deepEqual(events.slice(0, samples.length), samples.map((sample) => ({ ...sample, run_id })));
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
  } finally {
    await hermes.close();
  }
});
