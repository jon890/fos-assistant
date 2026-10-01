import assert from "node:assert/strict";
import { test } from "node:test";
import {
  checkShards,
  collectFailures,
  collectJobFailures,
  githubApi,
  latestJobs,
  publicError,
  publishFailures,
} from "../../scripts/browser-ci.mjs";

test("실패한 job 만 재실행하면 이전 성공과 최신 재실행 결과로 판정한다", () => {
  const previous = Array.from({ length: 4 }, (_, index) => ({
    name: `browser-mobile-shard-${index + 1}`,
    run_attempt: 1,
    conclusion: index === 0 ? "failure" : "success",
  }));
  const rerun = { ...previous[0], run_attempt: 2, conclusion: "success" };
  checkShards(latestJobs([...previous, rerun]), "mobile", 4);
  assert.throws(() =>
    checkShards(
      latestJobs([...previous, { ...rerun, conclusion: "cancelled" }]),
      "mobile",
      4,
    ),
  );
});

test("필수 검사는 각 폭의 모든 shard 가 성공했을 때만 통과한다", () => {
  const jobs = Array.from({ length: 4 }, (_, index) => ({
    name: `browser-mobile-shard-${index + 1}`,
    conclusion: "success",
  }));
  jobs.push({ name: "browser-desktop-shard-1", conclusion: "failure" });
  checkShards(jobs, "mobile", 4);
  for (const conclusion of ["failure", "cancelled", "skipped", null]) {
    assert.throws(() =>
      checkShards([{ ...jobs[0], conclusion }, ...jobs.slice(1)], "mobile", 4),
    );
  }
  assert.throws(() => checkShards(jobs.slice(1), "mobile", 4));
  assert.throws(() => checkShards([...jobs, jobs[0]], "mobile", 4));
});

function report(status = "unexpected") {
  return {
    suites: [
      {
        title: "chat.spec.ts",
        suites: [
          {
            title: "대화",
            specs: [
              {
                title: "응답을 표시한다",
                file: "chat.spec.ts",
                tests: [
                  {
                    status,
                    results: [
                      {
                        status: "failed",
                        error: {
                          message:
                            "TimeoutError: 대기 실패\n두 번째 오류\n세 번째 오류",
                        },
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };
}

test("잘린 JSON, 누락 결과, 기동 실패가 다른 shard 의 실패 집계를 막지 않는다", async () => {
  const jobs = [1, 2, 3, 4].map((shard) => ({
    name: `browser-mobile-shard-${shard}`,
    conclusion: shard === 1 ? "cancelled" : "failure",
  }));
  const failures = await collectJobFailures(jobs, async (filename: string) => {
    if (filename.endsWith("1.json")) return JSON.parse("{");
    if (filename.endsWith("2.json")) throw new Error("missing");
    if (filename.endsWith("3.json"))
      return { errors: [{ message: "Error: 전역 설정 실패" }] };
    return report();
  });
  assert.deepEqual(
    failures.map((failure: any) => failure.file),
    ["shard-1-setup", "shard-2-setup", "shard-3-setup", "chat.spec.ts"],
  );
  assert.equal(failures[2].error, "Error: 전역 설정 실패");
});

test("중첩된 JSON 에서 실패한 테스트와 첫 오류 두 줄만 모은다", () => {
  assert.deepEqual(collectFailures(report(), "mobile"), [
    {
      file: "chat.spec.ts",
      project: "mobile",
      title: "chat.spec.ts › 대화 › 응답을 표시한다",
      error: "TimeoutError: 대기 실패\n두 번째 오류",
    },
  ]);
  assert.equal(collectFailures(report("expected"), "mobile").length, 0);
  assert.equal(collectFailures(report("flaky"), "mobile").length, 1);
});

test("공개 오류에서 주소, 절대 경로, 인증값, 인용값을 제거한다", () => {
  const error = publicError(
    "\u001b[31mError: https://example.invalid/private /private/data token=hidden 'private-value'\u001b[0m",
  );
  for (const value of [
    "example.invalid",
    "/private/data",
    "hidden",
    "private-value",
    "\u001b",
  ]) {
    assert.equal(error.includes(value), false);
  }
  const absolute = report();
  absolute.suites[0].suites[0].specs[0].file = "/private/chat.spec.ts";
  assert.throws(() => collectFailures(absolute, "mobile"));
});

test("동일 spec 은 열린 이슈에 재발 횟수를 남기고 실행 재시도는 중복 게시하지 않는다", async () => {
  const issues: any[] = [];
  const comments: any[] = [];
  const posts: string[] = [];
  let labels: any[] = [];
  const api = async (method: string, path: string, body?: any) => {
    if (method === "GET") {
      if (path.includes("/labels?")) return labels;
      if (path.includes("/comments?")) return [...comments];
      return [...issues];
    }
    posts.push(path);
    if (path.endsWith("/labels")) labels = [body];
    if (path.endsWith("/comments")) comments.push(body);
    if (path.endsWith("/issues")) {
      const issue = { ...body, number: issues.length + 1 };
      issues.push(issue);
      return issue;
    }
    return body;
  };
  const context = {
    repository: "example/repo",
    serverUrl: "https://github.com",
    runId: "1",
    attempt: "1",
  };
  const failures = collectFailures(report(), "mobile");
  await publishFailures(
    [...failures, { ...failures[0], title: "다른 테스트" }],
    api,
    context,
  );
  assert.equal(issues.length, 1);
  assert.equal(posts.filter((path) => path.endsWith("/issues")).length, 1);
  await publishFailures(failures, api, context);
  assert.equal(comments.length, 0);
  await publishFailures(failures, api, { ...context, runId: "2" });
  assert.equal(comments.length, 1);
  assert.match(comments[0].body, /재발 2회/);
  await publishFailures(failures, api, { ...context, runId: "2" });
  assert.equal(comments.length, 1);
  await publishFailures(failures, api, { ...context, runId: "3" });
  assert.match(comments[1].body, /재발 3회/);
  await publishFailures(collectFailures(report(), "desktop"), api, context);
  assert.equal(issues.length, 2);
});

test("API 의 모든 페이지를 읽고 HTTP 실패를 성공으로 처리하지 않는다", async () => {
  let calls = 0;
  const api = githubApi("test-token", async () => {
    calls += 1;
    return new Response(JSON.stringify({ jobs: [{ name: String(calls) }] }), {
      headers:
        calls === 1
          ? { link: '<https://api.github.com/next>; rel="next"' }
          : {},
    });
  });
  assert.equal((await api("GET", "jobs")).length, 2);
  const failed = githubApi(
    "test-token",
    async () => new Response("failure", { status: 403 }),
  );
  await assert.rejects(() => failed("GET", "jobs"), /403/);
});
