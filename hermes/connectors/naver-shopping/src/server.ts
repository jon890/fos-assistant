import manifest from "../assistant.json";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { PROXY_ENVIRONMENT_KEYS } from "./cdp.ts";
import { guard } from "./errors.ts";
import { checkLogin, type Env } from "./session.ts";
import {
  productDetail,
  QUERY_MAX,
  searchProducts,
  type ToolDeps,
} from "./tools.ts";
import { URL_MAX } from "./urls.ts";

/** 도구를 등록한 서버. 테스트는 이 서버를 InMemoryTransport 로 부른다. */
export function createServer(env: Env = process.env, deps?: ToolDeps) {
  const server = new McpServer({
    name: "fos-naver-shopping",
    version: "1.0.0",
  });
  server.registerTool(
    "check_shopping_login",
    {
      description: manifest.tools.check_shopping_login.description,
      inputSchema: {},
      annotations: { readOnlyHint: true },
    },
    () => guard(() => checkLogin(env)),
  );
  server.registerTool(
    "search_products",
    {
      description: manifest.tools.search_products.description,
      inputSchema: {
        query: z
          .string()
          .min(1)
          .max(QUERY_MAX * 2),
      },
      annotations: { readOnlyHint: true },
    },
    ({ query }) => guard(() => searchProducts(env, query, deps)),
  );
  server.registerTool(
    "get_product_detail",
    {
      description: manifest.tools.get_product_detail.description,
      inputSchema: { url: z.string().min(1).max(URL_MAX) },
      annotations: { readOnlyHint: true },
    },
    ({ url }) => guard(() => productDetail(env, url, deps)),
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
    process.stderr.write("NAVER_SHOPPING_MCP_START_FAILED\n");
    process.exitCode = 2;
  }
}
