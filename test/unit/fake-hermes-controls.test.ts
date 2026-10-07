import assert from "node:assert/strict";
import test from "node:test";
import { FAKE_DASHBOARD_TOKEN, startFakeHermes } from "../e2e/fake-hermes.ts";

test("대역 서버 둘의 장애, profile, 실행과 대본은 서로 섞이지 않는다", async () => {
  const first = await startFakeHermes({ browser: "first-key" });
  const second = await startFakeHermes({ browser: "second-key" });
  try {
    first.busy();
    first.blockProvider("openai-codex");
    const dashboardHeaders = { Authorization: `Bearer ${FAKE_DASHBOARD_TOKEN}` };
    await fetch(`${first.baseUrl}/api/profiles`, {
      method: "POST", headers: dashboardHeaders, body: JSON.stringify({ name: "first-profile" }),
    });
    await fetch(`${first.baseUrl}/__test/script`, {
      method: "POST", body: JSON.stringify({ input: "서버 격리 검사", output: "첫째 서버의 대본" }),
    });
    const submit = (baseUrl: string, key: string) => fetch(`${baseUrl}/p/browser/v1/runs`, {
      method: "POST", headers: { Authorization: `Bearer ${key}` },
      body: JSON.stringify({ input: "서버 격리 검사", provider: "openai-codex", model: "example-model" }),
    });
    assert.equal((await submit(first.baseUrl, "first-key")).status, 429);
    const secondId = (await (await submit(second.baseUrl, "second-key")).json()).run_id;
    const status = (baseUrl: string, key: string, id: string) => fetch(`${baseUrl}/p/browser/v1/runs/${id}`, {
      headers: { Authorization: `Bearer ${key}` },
    });
    const secondRun = await (await status(second.baseUrl, "second-key", secondId)).json();
    assert.equal(secondRun.status, "completed");
    assert.match(secondRun.output, /서버 격리 검사/);
    assert.notEqual(secondRun.output, "첫째 서버의 대본");
    assert.deepEqual(first.profiles(), ["first-profile"]);
    assert.deepEqual(second.profiles(), []);
    assert.equal((await status(first.baseUrl, "first-key", secondId)).status, 404);
    first.clearBusy();
    first.clearBlockedProviders();
    const firstId = (await (await submit(first.baseUrl, "first-key")).json()).run_id;
    assert.equal((await (await status(first.baseUrl, "first-key", firstId)).json()).output, "첫째 서버의 대본");
    assert.equal((await status(second.baseUrl, "second-key", firstId)).status, 404);
  } finally {
    await Promise.all([first.close(), second.close()]);
  }
});

test("대역 제어 초기화는 장애와 미사용 보류를 걷고 profile 설정은 보존한다", async () => {
  const hermes = await startFakeHermes({ browser: "example-key" });
  const call = (path: string, method = "POST", body?: unknown) =>
    fetch(`${hermes.baseUrl}${path}`, {
      method,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(2000),
    });
  try {
    for (const path of [
      "busy",
      "hold-next-run",
      "hold-next-soul",
      "block-provider/openai-codex",
    ]) {
      assert.equal((await call(`/__test/${path}`)).status, 204);
    }
    await call("/__test/readiness-outage", "POST", { outage: "unavailable" });
    const toolsets = () =>
      fetch(`${hermes.baseUrl}/p/browser/v1/toolsets`, {
        headers: { Authorization: "Bearer example-key" },
      });
    assert.equal((await toolsets()).status, 503);
    assert.equal((await call("/__test/reset-controls")).status, 204);
    assert.equal((await toolsets()).status, 200);
    assert.equal((await call("/__test/wait-held-run", "GET")).status, 409);
    const soul = await fetch(`${hermes.baseUrl}/api/profiles/browser/soul`, {
      headers: { Authorization: `Bearer ${FAKE_DASHBOARD_TOKEN}` },
      signal: AbortSignal.timeout(2000),
    });
    assert.equal(soul.status, 200);
    const submitted = await fetch(`${hermes.baseUrl}/p/browser/v1/runs`, {
      method: "POST",
      headers: { Authorization: "Bearer example-key" },
      body: JSON.stringify({
        input: "초기화 뒤 실행",
        provider: "openai-codex",
        model: "example-model",
      }),
      signal: AbortSignal.timeout(2000),
    });
    assert.equal(submitted.status, 200);
    const { run_id } = await submitted.json();
    const finished = await fetch(
      `${hermes.baseUrl}/p/browser/v1/runs/${run_id}`,
      {
        headers: { Authorization: "Bearer example-key" },
        signal: AbortSignal.timeout(2000),
      },
    );
    assert.equal(finished.status, 200);
    assert.equal((await finished.json()).status, "completed");
    await call("/__test/hold-next-run");
    const held = await fetch(`${hermes.baseUrl}/p/browser/v1/runs`, {
      method: "POST",
      headers: { Authorization: "Bearer example-key" },
      body: JSON.stringify({ input: "정리해야 하는 실행" }),
      signal: AbortSignal.timeout(2000),
    });
    const heldId = (await held.json()).run_id;
    assert.equal((await call("/__test/wait-held-run", "GET")).status, 204);
    assert.equal((await call("/__test/reset-controls")).status, 204);
    const released = await fetch(
      `${hermes.baseUrl}/p/browser/v1/runs/${heldId}`,
      {
        headers: { Authorization: "Bearer example-key" },
        signal: AbortSignal.timeout(2000),
      },
    );
    assert.equal(released.status, 200);
    assert.equal((await released.json()).status, "completed");
    assert.equal((await call("/__test/wait-held-run", "GET")).status, 409);
    assert.equal(
      (await call("/__test/last-submitted-runtime", "GET")).status,
      200,
    );
    // 여러 번 비워도 정상이다. teardown 뒤 다음 setup도 같은 경로를 부른다.
    assert.equal((await call("/__test/reset-controls")).status, 204);
  } finally {
    await hermes.close();
  }
});
