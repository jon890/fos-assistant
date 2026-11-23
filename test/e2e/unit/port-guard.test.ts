import assert from "node:assert/strict";
import { createServer, type AddressInfo } from "node:net";
import { once } from "node:events";
import { test } from "node:test";
import { pickAppPort } from "../port-guard.ts";

for (const host of ["127.0.0.1", "::"]) {
  test(`${host} 포트가 사용 중이면 명시한 포트는 실패하고 기본 포트는 경고한 뒤 바꾼다`, async (t) => {
    const originalPort = process.env.E2E_APP_PORT;
    t.after(() => {
      if (originalPort === undefined) delete process.env.E2E_APP_PORT;
      else process.env.E2E_APP_PORT = originalPort;
    });
    const warn = t.mock.method(console, "warn", () => {});
    const server = createServer();
    server.listen(0, host);
    await once(server, "listening");
    const { port } = server.address() as AddressInfo;

    try {
      const conflict = new RegExp(`TCP 포트 ${port} 사용 중\\. PID ${process.pid} \\(.*node.*\\)`);
      process.env.E2E_APP_PORT = String(port);
      await assert.rejects(pickAppPort(port), conflict);
      assert.equal(warn.mock.callCount(), 0);

      delete process.env.E2E_APP_PORT;
      const fallback = await pickAppPort(port);
      assert.notEqual(fallback, port);
      assert.equal(warn.mock.callCount(), 1);
      assert.match(warn.mock.calls[0].arguments[0], conflict);
      assert.match(warn.mock.calls[0].arguments[0], new RegExp(`빈 포트 ${fallback}에서 계속합니다`));
      assert.ok(server.listening);
    } finally {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
    process.env.E2E_APP_PORT = String(port);
    assert.equal(await pickAppPort(port), port);
  });
}
