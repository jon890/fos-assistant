import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  readExecution,
  summarizeExecution,
} from "../../scripts/review-execution-summary.mjs";

test("실행 진단은 본문과 도구 입력을 제외하고 필요한 값만 남긴다", () => {
  const messages = [
    {
      type: "assistant",
      message: {
        content: [
          { type: "text", text: "비밀" },
          {
            type: "tool_use",
            name: "Bash",
            input: { command: "private-command" },
          },
          { type: "tool_use", name: "Bash", input: {} },
        ],
        stop_reason: "tool_use",
      },
    },
    {
      type: "assistant",
      message: {
        content: [{ type: "text", text: "완료" }],
        stop_reason: "end_turn",
      },
    },
    { type: "result", num_turns: 2, result: "private-result" },
  ];
  const result = summarizeExecution(messages);
  assert.deepEqual({ ...result.tools }, { Bash: 2 });
  assert.equal(result.turns, 2);
  assert.equal(result.lastMessageLength, 2);
  assert.equal(result.stopReason, "end_turn");
  assert.equal(JSON.stringify(result).includes("private"), false);
  assert.deepEqual(readExecution(JSON.stringify(messages)), messages);
  assert.deepEqual(
    readExecution(
      messages.map((message) => JSON.stringify(message)).join("\n"),
    ),
    messages,
  );
});

test("진단 정보가 없거나 stop 이유가 예상 밖이어도 본문을 출력하지 않는다", () => {
  assert.equal(summarizeExecution([]).turns, 0);
  const result = summarizeExecution([
    { type: "assistant", message: { stop_reason: "private-reason" } },
  ]);
  assert.equal(result.stopReason, "other");
  assert.equal(result.lastMessageLength, 0);
});

test("현재 커밋에 새로 제출한 Claude 리뷰만 성공으로 센다", () => {
  const review = {
    id: 11,
    user: { login: "claude[bot]" },
    commit_id: "head",
    submitted_at: "2026-10-01",
    state: "COMMENTED",
    body: "검토 완료",
  };
  const count = (reviews) => {
    const result = spawnSync(
      "jq",
      [
        "--arg",
        "sha",
        "head",
        "--argjson",
        "last",
        "10",
        "-f",
        "scripts/submitted-claude-reviews.jq",
      ],
      { input: JSON.stringify(reviews), encoding: "utf8" },
    );
    assert.equal(result.status, 0, result.stderr);
    return Number(result.stdout);
  };
  assert.equal(count([[], [review]]), 1);
  for (const change of [
    { id: 10 },
    { user: { login: "human" } },
    { commit_id: "old" },
    { submitted_at: null },
    { state: "PENDING" },
    { state: "DISMISSED" },
    { body: " \n " },
    { body: null },
  ]) {
    assert.equal(count([[{ ...review, ...change }]]), 0);
  }
  assert.equal(count([]), 0);
});

test("워크플로 게시 확인 단계는 누락과 커밋 변경, API 오류를 실패로 끝낸다", () => {
  const workflow = readFileSync(
    ".github/workflows/claude-code-review.yml",
    "utf8",
  );
  const step = workflow
    .split("- name: 현재 커밋에 Claude 리뷰가 게시됐는지 확인")[1]
    .split("# 작업 결과를")[0];
  const script = step
    .split("run: |\n")[1]
    .split("\n")
    .map((line) => line.replace(/^          /, ""))
    .join("\n")
    .replace(/\$\{\{[^}]+\}\}/g, "fixture")
    // CI 는 기본 브랜치의 사본을 .review-tools 에 두고 쓴다. 여기서는 작업 트리의 같은 파일을 쓴다.
    .replaceAll(".review-tools/", "");
  const run = (head, reviews, apiFailure = false) =>
    spawnSync(
      "bash",
      [
        "-c",
        `
    set -euo pipefail
    gh() {
      if [ "$1" = "pr" ]; then
        printf '%s\\n' "$MOCK_HEAD"
      elif [ "$MOCK_API_FAILURE" = "true" ]; then
        return 1
      else
        printf '%s\\n' "$MOCK_REVIEWS"
      fi
    }
    ${script}
  `,
      ],
      {
        encoding: "utf8",
        env: {
          ...process.env,
          MOCK_HEAD: head,
          MOCK_REVIEWS: JSON.stringify(reviews),
          MOCK_API_FAILURE: String(apiFailure),
          REVIEW_HEAD: "head",
          LAST_REVIEW_ID: "10",
          GITHUB_STEP_SUMMARY: "/dev/null",
        },
      },
    );
  const review = {
    id: 11,
    user: { login: "claude[bot]" },
    commit_id: "head",
    submitted_at: "2026-10-01",
    state: "COMMENTED",
    body: "검토 완료",
  };
  const success = run("head", [[review]]);
  assert.equal(success.status, 0, success.stderr);
  assert.equal(run("head", []).status, 1);
  assert.equal(run("new-head", [[review]]).status, 1);
  assert.notEqual(run("head", [[review]], true).status, 0);
});
