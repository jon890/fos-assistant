import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { test } from "node:test";
import { HANDLING, LABEL, UNKNOWN_SCENARIO, publishFailure, scenarioFrom } from "../../scripts/ci/e2e-ci.mjs";

test("run.ts 가 남긴 실패 줄에서 시나리오 이름만 읽고 읽지 못하면 기록 없음으로 둔다", () => {
  const line = '실패: {"status":"FAILED","scenario":"채팅 시나리오","elapsedMs":1200}';
  assert.equal(scenarioFrom(`== 채팅 시나리오\n\n${line}\n--- Control Plane 로그 ---`), "채팅 시나리오");
  for (const text of ["", undefined, "모두 통과했다", "실패: 깨진 JSON", '실패: {"status":"FAILED"}', '실패: {"scenario":3}']) {
    assert.equal(scenarioFrom(text), UNKNOWN_SCENARIO, String(text));
  }
});

test("공개 이슈에 주소, 경로, 인증값, 인용값이 새지 않게 시나리오 이름을 거른다", () => {
  const name = scenarioFrom('실패: {"scenario":"https://example.invalid/x /private/data token=hidden \'quoted\'"}');
  for (const value of ["example.invalid", "/private/data", "hidden", "quoted"]) assert.equal(name.includes(value), false, value);
  assert.ok(scenarioFrom(`실패: ${JSON.stringify({ scenario: "가".repeat(500) })}`).length <= 120);
});

function fakeApi() {
  const issues: any[] = [];
  const comments: any[] = [];
  let labels: any[] = [];
  const api = async (method: string, path: string, body?: any) => {
    if (method === "GET") {
      if (path.includes("/labels?")) return labels;
      if (path.includes("/comments?")) return [...comments];
      return [...issues];
    }
    if (path.endsWith("/labels")) labels = [body];
    if (path.endsWith("/comments")) comments.push(body);
    if (path.endsWith("/issues")) {
      const issue = { ...body, number: issues.length + 1 };
      issues.push(issue);
      return issue;
    }
    return body;
  };
  return { api, issues, comments, labels: () => labels };
}

const context = { repository: "example/repo", serverUrl: "https://github.com", runId: "1", attempt: "1" };

test("같은 시나리오 실패는 열린 이슈에 재발로 남기고 같은 실행의 재시도는 겹쳐 달지 않는다", async () => {
  const { api, issues, comments, labels } = fakeApi();
  await publishFailure("채팅 시나리오", api, context);
  assert.equal(issues.length, 1);
  assert.equal(issues[0].title, "[E2E 실패] 채팅 시나리오");
  assert.deepEqual(issues[0].labels, [LABEL]);
  assert.equal(labels()[0].name, LABEL);
  assert.ok(issues[0].body.endsWith(HANDLING));
  const marker = `<!-- e2e-failure:${createHash("sha256").update("채팅 시나리오").digest("hex")} -->`;
  assert.ok(issues[0].body.includes(marker));
  // 같은 실행을 다시 처리해도 이슈와 댓글이 늘지 않는다.
  await publishFailure("채팅 시나리오", api, context);
  assert.equal(issues.length, 1);
  assert.equal(comments.length, 0);
  // 다른 실행은 댓글로 남기고 처리 안내는 붙이지 않는다.
  await publishFailure("채팅 시나리오", api, { ...context, runId: "2" });
  assert.equal(comments.length, 1);
  assert.match(comments[0].body, /재발 2회/);
  assert.equal(comments[0].body.includes(HANDLING), false);
  await publishFailure("채팅 시나리오", api, { ...context, runId: "2" });
  assert.equal(comments.length, 1);
  await publishFailure("채팅 시나리오", api, { ...context, runId: "3" });
  assert.match(comments[1].body, /재발 3회/);
  // 다른 시나리오의 실패는 별도 이슈다.
  await publishFailure("메모리 시나리오", api, context);
  assert.equal(issues.length, 2);
});
