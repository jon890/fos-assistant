import assert from "node:assert/strict";
import test from "node:test";
import { FAKE_DASHBOARD_TOKEN, startFakeHermes } from "../e2e/fake-hermes.ts";

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
