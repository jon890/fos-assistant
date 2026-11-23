import assert from "node:assert/strict";
import { createServer, type AddressInfo } from "node:net";
import { once } from "node:events";
import { test } from "node:test";
import { assertPortAvailable } from "../port-guard.ts";

for (const host of ["127.0.0.1", "::"]) {
  test(`${host}에서 듣는 서버의 PID와 명령 줄을 알리고, 닫은 포트는 통과한다`, async () => {
    const server = createServer();
    server.listen(0, host);
    await once(server, "listening");
    const { port } = server.address() as AddressInfo;

    try {
      await assert.rejects(assertPortAvailable(port), (error: Error) => {
        assert.match(error.message, new RegExp(`TCP 포트 ${port} 사용 중\\. PID ${process.pid} \\(.*node.*\\)`));
        assert.match(error.message, /앞 e2e 실행이 남긴 프로세스일 수 있습니다/);
        return true;
      });
    } finally {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
    await assertPortAvailable(port);
  });
}
