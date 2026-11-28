import assert from "node:assert/strict";
import test from "node:test";
import {
  agentControlSwitch,
  setAdminAgentControl,
  setAgentControl,
} from "../../src/lib/browser-api.ts";

test("관리자가 허용하면 사용자가 켠 값 그대로 보이고 잠그지 않는다", () => {
  assert.deepEqual(agentControlSwitch({ enabled: true, adminAllowed: true }), {
    checked: true,
    locked: false,
    note: null,
  });
  assert.deepEqual(agentControlSwitch({ enabled: false, adminAllowed: true }), {
    checked: false,
    locked: false,
    note: null,
  });
});

test("관리자가 끄면 꺼진 모양으로 안내를 보이고, 켜 둔 사용자는 끄기만 할 수 있다", () => {
  const off = agentControlSwitch({ enabled: false, adminAllowed: false });
  assert.equal(off.checked, false);
  assert.equal(off.locked, true);
  assert.match(off.note ?? "", /관리자가/);
  const kept = agentControlSwitch({ enabled: true, adminAllowed: false });
  assert.equal(kept.checked, false);
  assert.equal(kept.locked, false);
});

test("스위치 요청은 서버 라우트에 PUT 으로 enabled 만 보낸다", async () => {
  const calls: { url: string; init?: RequestInit }[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    calls.push({ url, init });
    return new Response("{}", { status: 200 });
  }) as typeof fetch;
  try {
    await setAgentControl(true);
    await setAdminAgentControl(false);
  } finally {
    globalThis.fetch = original;
  }
  assert.deepEqual(
    calls.map((call) => [call.url, call.init?.method, call.init?.body]),
    [
      ["/api/browser/agent-control", "PUT", '{"enabled":true}'],
      ["/api/admin/browsers/agent-control", "PUT", '{"enabled":false}'],
    ],
  );
});
