import manifest from "../assistant.json";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { PROXY_ENVIRONMENT_KEYS } from "./cdp.ts";
import { guard } from "./errors.ts";
import { PASSENGERS_MAX } from "./input.ts";
import { checkLogin, type Env } from "./session.ts";
import { searchTrains, type ToolDeps } from "./tools.ts";

const count = z.number().int().min(0).max(PASSENGERS_MAX);

/** 도구를 등록한 서버. 테스트는 이 서버를 InMemoryTransport 로 부른다. */
export function createServer(env: Env = process.env, deps?: ToolDeps) {
  const server = new McpServer({ name: "fos-korail-ktx", version: "1.0.0" });
  server.registerTool(
    "check_korail_login",
    {
      description: manifest.tools.check_korail_login.description,
      inputSchema: {},
      annotations: { readOnlyHint: true },
    },
    () => guard(() => checkLogin(env)),
  );
  server.registerTool(
    "search_trains",
    {
      description: manifest.tools.search_trains.description,
      inputSchema: {
        departure: z.string().min(1).max(30),
        arrival: z.string().min(1).max(30),
        date: z.string().max(10),
        time_from: z.string().max(5),
        adults: count,
        children: count,
        seniors: count,
        seat_class: z.enum(["general", "first"]),
      },
      annotations: { readOnlyHint: true },
    },
    (args) => guard(() => searchTrains(env, args, deps)),
  );
  return server;
}

/** 프록시가 상속된 Bun 은 시작할 때 연결 대상을 캐시하므로 프록시 env 를 뺀 깨끗한 자식으로 다시 시작한다. */
async function runServer() {
  if (PROXY_ENVIRONMENT_KEYS.some((key) => Boolean(process.env[key]))) {
    const env = { ...process.env };
    for (const key of PROXY_ENVIRONMENT_KEYS) delete env[key];
    const child = Bun.spawn([process.execPath, ...process.argv.slice(1)], {
      cwd: process.cwd(),
      env,
      stdin: "inherit",
      stdout: "inherit",
      stderr: "inherit",
    });
    const forward = (signal: NodeJS.Signals) => () => child.kill(signal);
    process.once("SIGTERM", forward("SIGTERM"));
    process.once("SIGINT", forward("SIGINT"));
    process.exitCode = await child.exited;
    return;
  }
  await createServer().connect(new StdioServerTransport());
}

if (import.meta.main) {
  try {
    await runServer();
  } catch {
    process.stderr.write("KORAIL_MCP_START_FAILED\n");
    process.exitCode = 2;
  }
}
