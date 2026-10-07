import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { Gmail, type GmailOptions } from "./gmail-client.ts";
import {
  hasProxyEnvironment,
  proxyFreeEnvironment,
  isSupportedBunVersion,
} from "./runtime.ts";
import { createToolRegistration } from "./tool-registration.ts";
import { registerReadTools } from "./read-tools.ts";
import { registerDraftTool, registerSendTools } from "./mail-tools.ts";
import { registerModifyLabelsTool, registerLabelTools } from "./label-tools.ts";
import { registerFilterTools } from "./filter-tools.ts";

export { confirmComposedMessage } from "./compose.ts";
export { isSupportedBunVersion } from "./runtime.ts";
export type { GmailOptions } from "./gmail-client.ts";

export function createGmailServer(options: GmailOptions = {}) {
  const gmail = new Gmail(options);
  const server = new McpServer({ name: "fos-gmail", version: "1.0.0" });
  const register = createToolRegistration(server);
  registerReadTools(register, gmail);
  registerDraftTool(register, gmail);
  registerModifyLabelsTool(register, gmail);
  registerSendTools(register, gmail);
  registerLabelTools(register, gmail);
  registerFilterTools(register, gmail);
  return server;
}

/** 프록시가 있으면 같은 Bun과 인자로 다시 시작한 뒤 표준 입출력을 그대로 연결한다. */
export async function runGmailServer(options: GmailOptions = {}) {
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

  await createGmailServer(options).connect(new StdioServerTransport());
}

if (import.meta.main) {
  if (!isSupportedBunVersion(Bun.version)) {
    process.stderr.write(
      `GMAIL_MCP_UNSUPPORTED_BUN_VERSION: expected Bun 1.3.14 or later, got ${Bun.version}\n`,
    );
    process.exitCode = 1;
  } else {
    try {
      await runGmailServer();
    } catch {
      process.stderr.write("GMAIL_MCP_START_FAILED\n");
      process.exitCode = 2;
    }
  }
}
