import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { Tossinvest, type TossinvestOptions } from "./client.ts";
import {
  hasProxyEnvironment,
  proxyFreeEnvironment,
  isSupportedBunVersion,
} from "./runtime.ts";
import { createToolRegistration } from "./tool-registration.ts";
import { registerReadTools } from "./read-tools.ts";
import { registerAccountTools } from "./account-tools.ts";

export { isSupportedBunVersion } from "./runtime.ts";
export type { TossinvestOptions } from "./client.ts";

export function createTossinvestServer(options: TossinvestOptions = {}) {
  const client = new Tossinvest(options);
  const server = new McpServer({ name: "fos-tossinvest", version: "1.0.0" });
  const register = createToolRegistration(server);
  registerReadTools(register, client);
  registerAccountTools(register, client);
  return server;
}

/** 프록시가 있으면 같은 Bun과 인자로 다시 시작한 뒤 표준 입출력을 그대로 연결한다. */
export async function runTossinvestServer(options: TossinvestOptions = {}) {
  if (hasProxyEnvironment(process.env)) {
    const child = Bun.spawn([process.execPath, ...process.argv.slice(1)], {
      cwd: process.cwd(),
      env: proxyFreeEnvironment(process.env),
      stdin: "inherit",
      stdout: "inherit",
      stderr: "inherit",
    });
    const forwardSigterm = () => child.kill("SIGTERM");
    const forwardSigint = () => child.kill("SIGINT");
    process.once("SIGTERM", forwardSigterm);
    process.once("SIGINT", forwardSigint);
    try {
      process.exitCode = await child.exited;
    } finally {
      process.off("SIGTERM", forwardSigterm);
      process.off("SIGINT", forwardSigint);
    }
    return;
  }

  await createTossinvestServer(options).connect(new StdioServerTransport());
}

if (import.meta.main) {
  if (!isSupportedBunVersion(Bun.version)) {
    process.stderr.write(
      `TOSSINVEST_MCP_UNSUPPORTED_BUN_VERSION: expected Bun 1.3.14 or later, got ${Bun.version}\n`,
    );
    process.exitCode = 1;
  } else {
    try {
      await runTossinvestServer();
    } catch {
      process.stderr.write("TOSSINVEST_MCP_START_FAILED\n");
      process.exitCode = 2;
    }
  }
}
