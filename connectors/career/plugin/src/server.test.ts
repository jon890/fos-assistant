import { expect, test } from "bun:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { copyFileSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { CareerError } from "./errors.ts";
import { createServer } from "./server.ts";
import manifest from "../assistant.json";

function expectManifestDescriptions(tools: Array<{ name: string; description?: string }>) {
  expect(tools.map(({ name }) => name).sort()).toEqual(Object.keys(manifest.tools).sort());
  expect(Object.fromEntries(tools.map(({ name, description }) => [name, description]))).toEqual(
    Object.fromEntries(
      Object.entries(manifest.tools).map(([name, policy]) => [name, policy.description]),
    ),
  );
}

const env = {
  CAREER_GITHUB_PROFILE_REPO: "example-user/example-user",
};
const neverFetch = async () => {
  throw new Error("요청을 보내면 안 됩니다.");
};

function expectConfigError(build: () => unknown) {
  try {
    build();
  } catch (error) {
    expect(error).toBeInstanceOf(CareerError);
    expect((error as CareerError).code).toBe("CAREER_CONFIG");
    return;
  }
  throw new Error("expected CAREER_CONFIG");
}

test("MCP 서버가 알리는 version 이 package.json 의 version 과 같다", async () => {
  const { version } = JSON.parse(
    readFileSync(join(import.meta.dir, "..", "package.json"), "utf8"),
  ) as { version: string };
  const server = createServer(env, neverFetch);
  const client = new Client({ name: "version-test", version: "1.0.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    expect(client.getServerVersion()?.version).toBe(version);
  } finally {
    await client.close();
    await server.close();
  }
});

test("MCP 로 manifest 에 선언한 도구를 모두 탐색하고 확인 도구가 GitHub 연결 없이 요청을 보내지 않고 성공한다", async () => {
  const server = createServer(env, neverFetch);
  const client = new Client({ name: "career-test", version: "1.0.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    expectManifestDescriptions((await client.listTools()).tools);
    const result = await client.callTool({ name: "check_connection", arguments: {} });
    expect(result.isError).not.toBe(true);
    expect(result.structuredContent).toEqual({ github: "not_configured" });
    expect(JSON.parse((result.content as Array<{ text: string }>)[0]!.text)).toEqual({ github: "not_configured" });
  } finally {
    await client.close();
    await server.close();
  }
});

test("커리어 Backend 주소와 token 없이 시작하고, 받아도 읽지 않는다", async () => {
  const urls: string[] = [];
  const server = createServer(
    { ...env, CAREER_BACKEND_URL: "https://career.example.com/", CAREER_BACKEND_TOKEN: "x".repeat(40) },
    async (input) => {
      urls.push(String(input));
      return new Response("{}");
    },
  );
  const client = new Client({ name: "no-backend-test", version: "1.0.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    await client.callTool({ name: "check_connection", arguments: {} });
    expect(urls).toEqual([]);
  } finally {
    await client.close();
    await server.close();
  }
  const source = ["server.ts", "tools.ts", "errors.ts", "github.ts", "github-token.ts"]
    .map((file) => readFileSync(join(import.meta.dir, file), "utf8"))
    .join("\n");
  expect(source).not.toContain("CAREER_BACKEND");
});

test("프로필 저장소 이름이 없거나 치환되지 않았거나 모양이 틀리면 CAREER_CONFIG 로 시작하지 않는다", () => {
  expectConfigError(() => createServer({}));
  expectConfigError(() => createServer({ CAREER_GITHUB_PROFILE_REPO: "${UNSET}" }));
  expectConfigError(() => createServer({ CAREER_GITHUB_PROFILE_REPO: "example-user" }));
});

test("GitHub token 이 치환되지 않은 변수 참조여도 서버가 만들어진다", () => {
  expect(createServer({ ...env, CAREER_GITHUB_TOKEN: "${CAREER_GITHUB_TOKEN}" })).toBeDefined();
});

test("plugin 실행 파일만 복사해도 의존성 설치 없이 stdio 로 시작한다", async () => {
  const root = mkdtempSync(join(tmpdir(), "career-standalone-"));
  const client = new Client({ name: "bundle-test", version: "1.0.0" });
  const entry = join(root, "career-mcp.js");
  copyFileSync(join(import.meta.dir, "../dist/career-mcp.js"), entry);
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [entry],
    cwd: root,
    env,
    stderr: "pipe",
  });
  let stderr = "";
  transport.stderr?.on("data", (chunk) => {
    stderr += String(chunk);
  });
  try {
    await client.connect(transport);
    expectManifestDescriptions((await client.listTools()).tools);
    expect(stderr).toBe("");
  } finally {
    await client.close();
    rmSync(root, { recursive: true, force: true });
  }
});

test("읽기 도구는 readOnlyHint, 갱신 도구는 destructiveHint 이고 모두 idempotentHint 다", async () => {
  const server = createServer(env, neverFetch);
  const client = new Client({ name: "annotation-test", version: "1.0.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    const { tools } = await client.listTools();
    for (const tool of tools) {
      const update = tool.name.startsWith("update_");
      expect({ name: tool.name, ...tool.annotations }).toMatchObject({
        name: tool.name,
        readOnlyHint: !update,
        destructiveHint: update,
        idempotentHint: true,
      });
    }
  } finally {
    await client.close();
    await server.close();
  }
});

test("번들에 파일을 읽는 코드가 없다", () => {
  const bundle = readFileSync(join(import.meta.dir, "../dist/career-mcp.js"), "utf8");
  expect(bundle).not.toContain("readFileSync");
  expect(bundle).not.toContain("statSync");
});

test("「내 브라우저」 주소 없이 서버가 뜨고 원티드 도구는 닿지 않음으로 답한다", async () => {
  const server = createServer(env, neverFetch);
  const client = new Client({ name: "wanted-test", version: "1.0.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    const listed = await client.listTools();
    const wanted = listed.tools.filter(({ name }) => name.endsWith("_wanted_resume"));
    expect(wanted.map(({ name, annotations }) => [name, annotations?.readOnlyHint])).toEqual([
      ["get_wanted_resume", true],
      ["update_wanted_resume", false],
    ]);
    const result = await client.callTool({ name: "get_wanted_resume", arguments: {} });
    expect(result.isError).toBe(true);
    const text = (result.content as Array<{ text: string }>)[0]!.text;
    expect(JSON.parse(text).error.code).toBe("CAREER_BROWSER_UNREACHABLE");
  } finally {
    await client.close();
    await server.close();
  }
});

// 가짜 값만 쓴다. 통로 주소와 두 토큰은 실제 호스트나 토큰이 아니다.
const channelUrl = "http://channel.example.test/internal/connector-token/k1.example-marker";
const channelGithubToken = "c".repeat(40);
const pastedGithubToken = "p".repeat(40);

type Seen = { url: string; authorization: string | null };

async function callGithubTool(
  serverEnv: Record<string, string | undefined>,
  channel: () => Response | Promise<Response>,
  tool = "check_connection",
) {
  const seen: Seen[] = [];
  const server = createServer(serverEnv, async (input, init) => {
    const url = String(input);
    seen.push({ url, authorization: new Headers(init?.headers).get("Authorization") });
    if (url === channelUrl) return channel();
    if (url.startsWith("https://api.github.com/")) return new Response(JSON.stringify({ default_branch: "main" }));
    return new Response(JSON.stringify({ documents: [] }));
  });
  const client = new Client({ name: "github-token-test", version: "1.0.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    const result = await client.callTool({ name: tool, arguments: {} });
    return { result, text: (result.content as Array<{ text: string }>)[0]!.text, seen };
  } finally {
    await client.close();
    await server.close();
  }
}

const tokenJson = () =>
  new Response(JSON.stringify({ access_token: channelGithubToken, expires_at: "2026-11-23T10:00:00Z" }));

test("통로 주소가 있으면 통로에서 받은 token 으로 GitHub 을 부르고 붙여 넣은 token 은 쓰지 않는다", async () => {
  const { result, text, seen } = await callGithubTool(
    { ...env, CAREER_GITHUB_TOKEN: pastedGithubToken, CAREER_GITHUB_TOKEN_URL: channelUrl },
    tokenJson,
  );
  expect(result.structuredContent).toEqual({ github: "ok" });
  const github = seen.filter(({ url }) => url.startsWith("https://api.github.com/"));
  expect(github.length).toBeGreaterThan(0);
  for (const call of github) expect(call.authorization).toBe(`Bearer ${channelGithubToken}`);
  // 통로에는 자격 증명 없이 묻는다. 주소 안의 표식이 자격 증명이다.
  expect(seen.find(({ url }) => url === channelUrl)?.authorization).toBeNull();
  expect(text).not.toContain(channelGithubToken);
  expect(text).not.toContain(channelUrl);
});

test("도구를 부를 때마다 통로에서 token 을 다시 받는다", async () => {
  let issued = 0;
  const server = createServer({ ...env, CAREER_GITHUB_TOKEN_URL: channelUrl }, async (input, init) => {
    const url = String(input);
    if (url === channelUrl) {
      issued += 1;
      return new Response(JSON.stringify({ access_token: `${issued}`.repeat(40) }));
    }
    if (url.startsWith("https://api.github.com/")) {
      expect(new Headers(init?.headers).get("Authorization")).toBe(`Bearer ${`${issued}`.repeat(40)}`);
      return new Response(JSON.stringify({ default_branch: "main" }));
    }
    return new Response(JSON.stringify({ documents: [] }));
  });
  const client = new Client({ name: "github-token-test", version: "1.0.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    for (let call = 0; call < 2; call += 1) {
      const result = await client.callTool({ name: "check_connection", arguments: {} });
      expect(result.structuredContent).toEqual({ github: "ok" });
    }
    expect(issued).toBe(2);
  } finally {
    await client.close();
    await server.close();
  }
});

test("통로 주소가 비었거나 치환되지 않은 참조면 붙여 넣은 token 을 쓴다", async () => {
  for (const tokenUrl of [undefined, "", "${CAREER_GITHUB_TOKEN_URL}"]) {
    const { result, seen } = await callGithubTool(
      { ...env, CAREER_GITHUB_TOKEN: pastedGithubToken, CAREER_GITHUB_TOKEN_URL: tokenUrl },
      () => {
        throw new Error("통로를 부르면 안 된다");
      },
    );
    expect(result.structuredContent).toEqual({ github: "ok" });
    expect(seen.some(({ url }) => url === channelUrl)).toBe(false);
    for (const call of seen.filter(({ url }) => url.startsWith("https://api.github.com/")))
      expect(call.authorization).toBe(`Bearer ${pastedGithubToken}`);
  }
});

test("token 과 통로 주소가 모두 없으면 GitHub 은 not_configured 다", async () => {
  const { result } = await callGithubTool({ ...env, CAREER_GITHUB_TOKEN: "", CAREER_GITHUB_TOKEN_URL: "" }, tokenJson);
  expect(result.structuredContent).toEqual({ github: "not_configured" });
});

test("통로 실패는 정한 CAREER 오류 코드로 나오고 결과에 통로 주소와 응답 원문이 없다", async () => {
  const leaked = "channel-body-should-not-leak";
  const cases: Array<[string, () => Response | Promise<Response>, string]> = [
    ["401", () => new Response(leaked, { status: 401 }), "CAREER_GITHUB_UNAUTHORIZED"],
    ["409", () => new Response(leaked, { status: 409 }), "CAREER_GITHUB_UNAUTHORIZED"],
    ["404", () => new Response(leaked, { status: 404 }), "CAREER_GITHUB_NOT_CONFIGURED"],
    ["503", () => new Response(leaked, { status: 503 }), "CAREER_GITHUB_UNAVAILABLE"],
    ["통신 실패", () => Promise.reject(new Error(leaked)), "CAREER_GITHUB_UNAVAILABLE"],
    ["token 없는 응답", () => new Response(JSON.stringify({ expires_at: leaked })), "CAREER_INVALID_RESPONSE"],
    ["공백 token", () => new Response(JSON.stringify({ access_token: "   ", expires_at: leaked })), "CAREER_INVALID_RESPONSE"],
    ["JSON 이 아닌 본문", () => new Response(leaked), "CAREER_INVALID_RESPONSE"],
    ["400", () => new Response(leaked, { status: 400 }), "CAREER_GITHUB_UNAVAILABLE"],
    ["500", () => new Response(leaked, { status: 500 }), "CAREER_GITHUB_UNAVAILABLE"],
  ];
  for (const [name, channel, code] of cases) {
    const { result, text, seen } = await callGithubTool(
      { ...env, CAREER_GITHUB_TOKEN_URL: channelUrl },
      channel,
      "get_github_profile",
    );
    expect({ name, isError: result.isError, code: JSON.parse(text).error.code }).toEqual({ name, isError: true, code });
    expect(text).not.toContain(leaked);
    expect(text).not.toContain(channelUrl);
    expect(seen.some(({ url }) => url.startsWith("https://api.github.com/"))).toBe(false);
  }
});

test("통로 주소 모양이 틀려도 서버는 뜨고 GitHub 도구만 CAREER_GITHUB_NOT_CONFIGURED 로 답한다", async () => {
  for (const tokenUrl of ["not a url", "file:///etc/token"]) {
    const { result, text, seen } = await callGithubTool(
      { ...env, CAREER_GITHUB_TOKEN: pastedGithubToken, CAREER_GITHUB_TOKEN_URL: tokenUrl },
      tokenJson,
      "get_github_profile",
    );
    expect(result.isError).toBe(true);
    expect(JSON.parse(text).error.code).toBe("CAREER_GITHUB_NOT_CONFIGURED");
    expect(text).not.toContain(tokenUrl);
    expect(seen.some(({ url }) => url.startsWith("https://api.github.com/"))).toBe(false);
  }
});

test("GitHub 도구 한 번에 통로를 한 번만 부르고 그 호출의 GitHub 요청이 모두 같은 token 을 쓴다", async () => {
  let issued = 0;
  const authorizations: Array<string | null> = [];
  const server = createServer({ ...env, CAREER_GITHUB_TOKEN_URL: channelUrl }, async (input, init) => {
    const url = String(input);
    if (url === channelUrl) {
      issued += 1;
      return new Response(JSON.stringify({ access_token: channelGithubToken }));
    }
    authorizations.push(new Headers(init?.headers).get("Authorization"));
    if (url === "https://api.github.com/repos/example-user/example-user")
      return new Response(JSON.stringify({ default_branch: "main" }));
    if (url.endsWith("/git/ref/heads/main")) return new Response(JSON.stringify({ object: { sha: "a".repeat(40) } }));
    if (url.includes("/contents/")) return new Response("{}", { status: 404 });
    throw new Error(`unexpected request ${url}`);
  });
  const client = new Client({ name: "github-token-test", version: "1.0.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    const result = await client.callTool({ name: "get_github_profile", arguments: {} });
    expect(result.isError).not.toBe(true);
    expect(issued).toBe(1);
    expect(authorizations.length).toBe(3);
    for (const authorization of authorizations) expect(authorization).toBe(`Bearer ${channelGithubToken}`);
    expect(JSON.stringify(result)).not.toContain(channelGithubToken);
  } finally {
    await client.close();
    await server.close();
  }
});

test("통로가 거절하면 붙여 넣은 token 으로 넘어가지 않는다", async () => {
  const { text, seen } = await callGithubTool(
    { ...env, CAREER_GITHUB_TOKEN: pastedGithubToken, CAREER_GITHUB_TOKEN_URL: channelUrl },
    () => new Response("{}", { status: 401 }),
    "get_github_profile",
  );
  expect(JSON.parse(text).error.code).toBe("CAREER_GITHUB_UNAUTHORIZED");
  expect(seen.some(({ url }) => url.startsWith("https://api.github.com/"))).toBe(false);
  expect(seen.some(({ authorization }) => authorization === `Bearer ${pastedGithubToken}`)).toBe(false);
});
