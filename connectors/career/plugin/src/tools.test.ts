import { describe, expect, test } from "bun:test";
import type { FetchLike } from "./errors.ts";
import { GithubProfileRepo } from "./github.ts";
import { CareerTools, toolDefinitions } from "./tools.ts";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const parse = (result: { content: [{ text: string }] }) => JSON.parse(result.content[0].text);

test("도구는 GitHub, 원티드, 연결 확인 다섯뿐이다", () => {
  expect(Object.keys(toolDefinitions).sort()).toEqual([
    "check_connection",
    "get_github_profile",
    "get_wanted_resume",
    "update_github_profile",
    "update_wanted_resume",
  ]);
});

describe("입력 검증과 모르는 도구", () => {
  test("모르는 도구와 지운 Backend 도구는 CAREER_UNKNOWN_TOOL 이다", async () => {
    const tools = new CareerTools();
    for (const name of ["nope", "get_context_document", "save_interview_attempt", "list_usage_snapshots"]) {
      const result = await tools.call(name, {});
      expect(result.isError).toBe(true);
      expect(parse(result).error.code).toBe("CAREER_UNKNOWN_TOOL");
    }
  });

  test("check_connection 에 인자를 주면 CAREER_INVALID_INPUT 이다", async () => {
    const result = await new CareerTools().call("check_connection", { extra: true });
    expect(parse(result).error.code).toBe("CAREER_INVALID_INPUT");
  });
});

describe("GitHub 프로필 도구", () => {
  const githubToken = "g".repeat(40);
  const repo = "octo-example/octo-example";
  const gh = `https://api.github.com/repos/${repo}`;
  const readme = "# 프로필\n\n합성 README 입니다.\n";
  // 가짜 SHA 만 쓴다. headA 는 변경안을 검토할 때 get_github_profile 이 돌려준 값이다.
  const headA = "a".repeat(40);
  const headB = "b".repeat(40);
  const reviewed = { expectedBranch: "main", expectedHead: headA };

  type GhCall = { method: string; url: string; init: RequestInit | undefined; body: any };

  function connected(options: { github?: boolean; githubStatus?: number; head?: string } = {}) {
    const head = options.head ?? headA;
    const calls: GhCall[] = [];
    const fetchImpl: FetchLike = async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      const body = init?.body === undefined ? undefined : JSON.parse(String(init.body));
      calls.push({ method, url, init, body });
      if (options.githubStatus) return json({ message: "denied" }, options.githubStatus);
      const key = `${method} ${url.slice(gh.length)}`;
      switch (key) {
        case "GET ":
          return json({ default_branch: "main" });
        case `GET /contents/README.md?ref=${head}`:
          return json({ content: Buffer.from(readme, "utf8").toString("base64"), encoding: "base64" });
        case "GET /git/ref/heads/main":
          return json({ object: { sha: head } });
        case `GET /git/commits/${head}`:
          return json({ tree: { sha: "base-tree-sha" } });
        case "POST /git/blobs":
          return json({ sha: `blob-${calls.length}` }, 201);
        case "POST /git/trees":
          return json({ sha: "new-tree-sha" }, 201);
        case "POST /git/commits":
          return json({ sha: "new-commit-sha" }, 201);
        case "PATCH /git/refs/heads/main":
          return json({ object: { sha: "new-commit-sha" } });
      }
      throw new Error(`unexpected request ${method} ${url}`);
    };
    const github = options.github === false ? undefined : new GithubProfileRepo({ token: githubToken, repo }, fetchImpl);
    return { calls, tools: new CareerTools(github) };
  }

  const authOf = (call: GhCall) => (call.init?.headers as Record<string, string>).Authorization;

  test("update_github_profile 은 받은 README 한 파일만 한 커밋으로 올린다", async () => {
    const { calls, tools } = connected();
    const result = await tools.call("update_github_profile", { readme, ...reviewed });
    expect(result.isError).toBeUndefined();
    expect(parse(result)).toEqual({ changed: true, commitSha: "new-commit-sha", branch: "main" });
    const blobs = calls.filter((call) => call.url.endsWith("/git/blobs"));
    expect(blobs.map((call) => call.body.content)).toEqual([readme]);
    const tree = calls.find((call) => call.url.endsWith("/git/trees"));
    expect(tree!.body.tree.map((entry: { path: string }) => entry.path)).toEqual(["README.md"]);
    const commit = calls.find((call) => call.method === "POST" && call.url.endsWith("/git/commits"));
    expect(commit!.body.message).toBe("docs: 프로필 README 를 갱신한다");
    // 사용량 기록을 읽는 곳이 없다. 모든 요청이 GitHub 으로만 간다.
    expect(new Set(calls.map((call) => new URL(call.url).host))).toEqual(new Set(["api.github.com"]));
  });

  const rejected = [
    ["months 를 넘기면", { readme, months: ["2031-01"], ...reviewed }],
    ["숫자 total 을 더하면", { readme, total: "97.9B", ...reviewed }],
    ["chart 를 더하면", { readme, chart: "<svg/>", ...reviewed }],
    ["readme 가 공백뿐", { readme: "  \n", ...reviewed }],
    ["readme 가 65,537바이트", { readme: "a".repeat(65_537), ...reviewed }],
    // expectedBranch 와 expectedHead 가 생기기 전에 저장된 승인은 최신 head 로 실행하지 않고 거절한다.
    ["검토 기준이 없는 옛 형식이면", { readme }],
    ["expectedHead 만 없으면", { readme, expectedBranch: "main" }],
    ["expectedBranch 가 비면", { readme, ...reviewed, expectedBranch: "" }],
    ["expectedHead 가 대문자면", { readme, ...reviewed, expectedHead: "A".repeat(40) }],
    ["expectedHead 가 짧으면", { readme, ...reviewed, expectedHead: "a".repeat(7) }],
  ] as const;
  for (const [label, args] of rejected) {
    test(`${label} fetch 없이 CAREER_INVALID_INPUT 이다`, async () => {
      const { calls, tools } = connected();
      const result = await tools.call("update_github_profile", args);
      expect(parse(result).error.code).toBe("CAREER_INVALID_INPUT");
      expect(calls).toHaveLength(0);
    });
  }

  test("GitHub 연결이 없으면 GitHub 도구는 fetch 없이 CAREER_GITHUB_NOT_CONFIGURED 이고 연결 확인은 성공한다", async () => {
    const { calls, tools } = connected({ github: false });
    const update = await tools.call("update_github_profile", { readme, ...reviewed });
    expect(parse(update).error.code).toBe("CAREER_GITHUB_NOT_CONFIGURED");
    const read = await tools.call("get_github_profile", {});
    expect(parse(read).error.code).toBe("CAREER_GITHUB_NOT_CONFIGURED");
    const check = await tools.call("check_connection", {});
    expect(check.isError).toBeUndefined();
    expect(parse(check)).toEqual({ github: "not_configured" });
    expect(check.structuredContent).toEqual({ github: "not_configured" });
    expect(calls).toHaveLength(0);
  });

  test("get_github_profile 은 저장소와 branch, 끝 커밋, 그 커밋의 README 를 내고 차트 파일은 읽지 않는다", async () => {
    const { calls, tools } = connected();
    const result = await tools.call("get_github_profile", {});
    expect(parse(result)).toEqual({ repo, branch: "main", head: headA, readme });
    expect(calls.some((call) => call.url.includes("agent-usage.svg"))).toBe(false);
  });

  test("A 를 읽고 만든 요청이 B 가 커밋된 뒤 실행되면 CAREER_GITHUB_STALE_REVIEW 이고 GitHub 에 쓰지 않는다", async () => {
    const before = connected();
    const read = parse(await before.tools.call("get_github_profile", {}));
    const args = { readme, expectedBranch: read.branch, expectedHead: read.head };
    const after = connected({ head: headB });
    const result = await after.tools.call("update_github_profile", args);
    expect(result.isError).toBe(true);
    expect(parse(result).error.code).toBe("CAREER_GITHUB_STALE_REVIEW");
    expect(after.calls.filter((call) => call.method !== "GET")).toHaveLength(0);
  });

  test("승인 인자를 JSON 으로 저장했다가 새 인스턴스에서 실행해도 검토 기준이 그대로 쓰인다", async () => {
    // fos-assistant 는 승인할 때 argsJson 을 저장하고 나중에, 다른 프로세스에서 실행할 수도 있다.
    const stored = JSON.stringify({ readme, ...reviewed });
    const later = connected();
    const result = await later.tools.call("update_github_profile", JSON.parse(stored));
    expect(result.isError).toBeUndefined();
    const commit = later.calls.find((call) => call.method === "POST" && call.url.endsWith("/git/commits"));
    expect(commit!.body.parents).toEqual([headA]);
    const moved = connected({ head: headB });
    const stale = await moved.tools.call("update_github_profile", JSON.parse(stored));
    expect(parse(stale).error.code).toBe("CAREER_GITHUB_STALE_REVIEW");
  });

  test("check_connection 은 GitHub 가 있으면 저장소만 확인하고 structuredContent 에도 같은 값을 낸다", async () => {
    const { calls, tools } = connected();
    const result = await tools.call("check_connection", {});
    expect(parse(result)).toEqual({ github: "ok" });
    expect(result.structuredContent).toEqual({ github: "ok" });
    expect(calls.map((call) => call.url)).toEqual([gh]);
  });

  test("check_connection 은 GitHub 가 401 이면 CAREER_GITHUB_UNAUTHORIZED 다", async () => {
    const { tools } = connected({ githubStatus: 401 });
    const result = await tools.call("check_connection", {});
    expect(result.isError).toBe(true);
    expect(parse(result).error.code).toBe("CAREER_GITHUB_UNAUTHORIZED");
  });

  test("token 은 GitHub 으로만 가고 오류 결과에 나오지 않는다", async () => {
    const { calls, tools } = connected();
    await tools.call("update_github_profile", { readme, ...reviewed });
    await tools.call("check_connection", {});
    expect(calls.length).toBeGreaterThan(0);
    for (const call of calls)
      expect({ host: new URL(call.url).host, auth: authOf(call) }).toEqual({
        host: "api.github.com",
        auth: `Bearer ${githubToken}`,
      });
    const failures = [
      await connected({ githubStatus: 401 }).tools.call("check_connection", {}),
      await connected({ githubStatus: 502 }).tools.call("get_github_profile", {}),
      await connected({ head: headB }).tools.call("update_github_profile", { readme, ...reviewed }),
    ];
    for (const failure of failures) {
      expect(failure.isError).toBe(true);
      expect(failure.content[0].text).not.toContain(githubToken);
    }
  });
});
