/** 실제 시나리오에서 다른 사용자의 요청이 먼저 실패하는 순서를 만든다. */
import { createServer } from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { userExecutionLimitScenario } from "../e2e/scenarios/user-execution-limit.ts";
import type { Context } from "../e2e/harness.ts";

const work = await mkdtemp(join(process.argv[2]!, "owned-"));
const server = createServer();
await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
const failure = new Error("합성 요청 실패");
let requests = 0;
globalThis.fetch = async (input, options) => {
  const route = new URL(String(input)).pathname;
  let body: unknown = [];
  if (route === "/agents")
    body =
      options?.method === "POST" ? { code: "kid-test" } : [{ code: "aunt" }];
  if (route === "/admin/agents")
    body = [{ code: "aunt", hermesProfile: "aunt" }];
  if (route === "/chat/conversations") body = { items: [] };
  if (route === "/chat/messages") {
    requests++;
    if (
      options?.headers &&
      (options.headers as Record<string, string>).Authorization === "Bearer kid"
    ) {
      await new Promise<void>((resolve) => setImmediate(resolve));
      throw failure;
    }
    // 다른 사용자 요청이 실패할 때도 같은 사용자의 여섯 요청은 아직 기다린다.
    await new Promise<void>((resolve) => setTimeout(resolve, 100));
  }
  return new Response(JSON.stringify(body), {
    status: route === "/agents" && options?.method === "POST" ? 201 : 200,
  });
};
const context = {
  api: "http://fixture.invalid",
  tokens: { aunt: "aunt", kid: "kid", dad: "dad", signin: "signin" },
  hermes: { slowRuns() {}, resetRunConcurrency() {} },
} as unknown as Context;

try {
  await userExecutionLimitScenario.run(context);
  process.exitCode = 2;
} catch (error) {
  // 실패를 성공으로 바꾸지 않는다. 바깥 검사는 실패한 러너의 정리 결과를 확인한다.
  console.log(
    JSON.stringify({ status: "FAILED", caught: error === failure, requests }),
  );
  process.exitCode = 1;
} finally {
  await new Promise<void>((resolve, reject) =>
    server.close((error) => (error ? reject(error) : resolve())),
  );
  await rm(work, { recursive: true, force: true });
  console.log(
    JSON.stringify({ cleanup: "DONE", serverClosed: !server.listening }),
  );
}
