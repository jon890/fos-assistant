import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CareerError, configuredValue, type FetchLike } from "./errors.ts";
import { browserEndpoint, relayBrowser } from "./browser/cdp.ts";
import { GithubProfileRepo } from "./github.ts";
import { channelToken } from "./github-token.ts";
import { CareerTools, toolDefinitions } from "./tools.ts";
import { WantedTools } from "./wanted/tools.ts";

const profileRepoPattern = /^[A-Za-z0-9-]{1,39}\/[A-Za-z0-9._-]{1,100}$/;

export function createServer(
  env: Record<string, string | undefined> = process.env,
  fetchImpl: FetchLike = fetch,
): McpServer {
  const profileRepo = configuredValue(env.CAREER_GITHUB_PROFILE_REPO);
  if (!profileRepo || !profileRepoPattern.test(profileRepo)) throw new CareerError("CAREER_CONFIG");
  // GitHub 연결은 선택이다. 두 값이 모두 없으면 GitHub 도구가 CAREER_GITHUB_NOT_CONFIGURED 로 답한다.
  // 단추(OAuth) 연결은 토큰 통로 주소를 넘기고, 그 주소가 붙여 넣은 토큰보다 앞선다.
  const githubTokenUrl = configuredValue(env.CAREER_GITHUB_TOKEN_URL);
  const githubToken = githubTokenUrl ? channelToken(githubTokenUrl, fetchImpl) : configuredValue(env.CAREER_GITHUB_TOKEN);
  const github = githubToken ? new GithubProfileRepo({ token: githubToken, repo: profileRepo }, fetchImpl) : undefined;
  // 「내 브라우저」 중계 주소가 없으면 원티드 도구는 닿지 않음으로 답한다.
  const endpoint = browserEndpoint(env);
  const wanted = new WantedTools(endpoint ? relayBrowser(endpoint) : undefined);
  const tools = new CareerTools(github, wanted);
  const server = new McpServer({ name: "fos-career", version: "0.14.1" });
  for (const [name, definition] of Object.entries(toolDefinitions)) {
    server.registerTool(
      name,
      {
        description: definition.description,
        inputSchema: definition.schema,
        annotations: {
          readOnlyHint: /^(check|get)_/.test(name),
          destructiveHint: /^update_/.test(name),
          // 읽기는 반복해도 안전하고, 두 갱신 도구는 같은 값으로 덮어쓴다.
          idempotentHint: true,
          openWorldHint: true,
        },
      },
      (args: unknown) => tools.call(name, args),
    );
  }
  return server;
}

if (import.meta.main) {
  try {
    await createServer().connect(new StdioServerTransport());
  } catch (error) {
    // 정해진 진단 코드만 쓴다. 예외와 환경 변수를 출력하지 않는다.
    process.stderr.write(`${error instanceof CareerError ? error.code : "CAREER_MCP_START_FAILED"}\n`);
    process.exitCode = 2;
  }
}
