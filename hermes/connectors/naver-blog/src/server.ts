import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { PROXY_ENVIRONMENT_KEYS } from "./cdp.ts";
import { guard } from "./errors.ts";
import { renderDraft, renderShape, type RenderInput } from "./render.ts";
import { sessionStatus, type Env } from "./session.ts";

const MINIMUM_BUN_VERSION = [1, 3, 14] as const;

/** 프록시가 상속된 Bun 은 시작할 때 연결 대상을 캐시하므로 깨끗한 자식으로 다시 시작한다. */
function hasProxyEnvironment(env: Env) {
  return PROXY_ENVIRONMENT_KEYS.some((key) => Boolean(env[key]));
}

function proxyFreeEnvironment(env: Env): Env {
  const cleaned = { ...env };
  for (const key of PROXY_ENVIRONMENT_KEYS) delete cleaned[key];
  return cleaned;
}

/** 최소 Bun 1.3.14 release 이상인지 SemVer 숫자로 비교한다. */
export function isSupportedBunVersion(version: string) {
  const match =
    /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/.exec(
      version,
    );
  if (!match) return false;
  const parts = [Number(match[1]), Number(match[2]), Number(match[3])];
  for (const [index, minimum] of MINIMUM_BUN_VERSION.entries()) {
    if (parts[index] !== minimum) return parts[index]! > minimum;
  }
  return match[4] === undefined;
}

/** 두 읽기 도구를 등록한 서버. 시험은 이 서버를 InMemoryTransport 로 부른다. */
export function createServer(env: Env = process.env) {
  const server = new McpServer({ name: "fos-naver-blog", version: "1.0.0" });
  server.registerTool(
    "session_status",
    {
      description:
        "연결한 Chrome 에 붙는지와 네이버 로그인 쿠키가 있는지 확인합니다. 탭을 열지 않습니다.",
      inputSchema: {},
      annotations: { readOnlyHint: true },
    },
    () => guard(() => sessionStatus(env)),
  );
  server.registerTool(
    "render_draft",
    {
      description:
        "초안을 검사하고 모바일 미리보기나 수동 등록용 HTML 을 돌려줍니다. 아무것도 저장하지 않습니다. problems 가 있으면 html 이 null 입니다.",
      inputSchema: renderShape,
      annotations: { readOnlyHint: true },
    },
    (input) => guard(() => renderDraft(input as RenderInput)),
  );
  return server;
}

/** 프록시가 있으면 같은 Bun 과 인자로 다시 시작한 뒤 표준 입출력을 그대로 잇는다. */
export async function runServer() {
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
  await createServer().connect(new StdioServerTransport());
}

if (import.meta.main) {
  if (!isSupportedBunVersion(Bun.version)) {
    process.stderr.write(
      `NAVER_BLOG_MCP_UNSUPPORTED_BUN_VERSION: expected Bun 1.3.14 or later, got ${Bun.version}\n`,
    );
    process.exitCode = 1;
  } else {
    try {
      await runServer();
    } catch {
      process.stderr.write("NAVER_BLOG_MCP_START_FAILED\n");
      process.exitCode = 2;
    }
  }
}
