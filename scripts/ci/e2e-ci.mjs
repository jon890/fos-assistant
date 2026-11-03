#!/usr/bin/env node
// 정기·수동 실행에서 E2E 가 실패하면 `[E2E 실패] …` 이슈를 열거나 열린 이슈에 재발 댓글을 단다.
//
//   node scripts/ci/e2e-ci.mjs issues <실패 기록 폴더>
//
// 구조는 browser-ci.mjs 의 실패 이슈와 같다. 본문 marker 로 열린 이슈를 찾아 중복을 막고,
// 실행 marker 로 같은 실행의 재시도가 댓글을 겹쳐 달지 않게 한다. 규칙과 까닭은
// docs/adr/ADR-20261012-ci-path-filter.md 의 「정기 E2E 실패를 이슈로 남긴다」 가 갖는다.
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { githubApi, publicError } from "./browser-ci.mjs";

export const LABEL = "E2E 실패";
export const RECORD_FILE = "e2e-failure.txt";
// 시나리오 기록이 없을 때(기동 전 실패, 시간 초과, 로그 없음)의 이름이다.
export const UNKNOWN_SCENARIO = "시나리오 기록 없음";

// 처리하는 사람이 이슈에서 바로 읽도록 새 이슈 본문 끝에 붙인다. 재발 댓글에는 붙이지 않는다.
export const HANDLING =
  "PR 에서 난 실패는 그 PR 에서 고친다. 이 이슈는 main 과 매일 실행에서 모인 실패다. 고치거나, 고치지 않는 까닭을 적어 닫는다.";

/**
 * run.ts 가 실패하면 stderr 에 `실패: {"status":"FAILED","scenario":"…","elapsedMs":…}` 한 줄을 남긴다.
 * 예외 본문은 그 줄에 없으므로 그 줄의 시나리오 이름만 읽는다. 읽지 못하면 기록 없음으로 둔다.
 */
export function scenarioFrom(text) {
  const line = String(text ?? "")
    .split("\n")
    .find((candidate) => candidate.startsWith("실패: "));
  if (!line) return UNKNOWN_SCENARIO;
  try {
    const scenario = JSON.parse(line.slice("실패: ".length)).scenario;
    if (typeof scenario !== "string") return UNKNOWN_SCENARIO;
    // 이슈는 공개된다. 이름이 상수라도 같은 필터를 거치고 길이를 제한한다.
    return publicError(scenario).split("\n")[0].slice(0, 120) || UNKNOWN_SCENARIO;
  } catch {
    return UNKNOWN_SCENARIO;
  }
}

function markerFor(scenario) {
  return `<!-- e2e-failure:${createHash("sha256").update(scenario).digest("hex")} -->`;
}

export async function publishFailure(scenario, api, context) {
  const prefix = `repos/${context.repository}`;
  const labels = await api("GET", `${prefix}/labels?per_page=100`);
  if (!labels.some((label) => label.name === LABEL)) {
    await api("POST", `${prefix}/labels`, {
      name: LABEL,
      color: "D73A4A",
      description: "main 과 매일 실행에서 발견한 E2E 검사 실패",
    });
  }
  const open = await api(
    "GET",
    `${prefix}/issues?state=open&labels=${encodeURIComponent(LABEL)}&per_page=100`,
  );
  const marker = markerFor(scenario);
  const occurrence = `<!-- e2e-run:${context.runId}:${context.attempt} -->`;
  const link = `${context.serverUrl}/${context.repository}/actions/runs/${context.runId}/attempts/${context.attempt}`;
  const body = `${marker}\n${occurrence}\n\n- 실패한 시나리오: ${scenario}\n- 실행: [CI 결과](${link})`;
  const existing = open.find((issue) => issue.body?.includes(marker));
  if (!existing) {
    await api("POST", `${prefix}/issues`, {
      title: `[E2E 실패] ${scenario}`,
      labels: [LABEL],
      body: `${body}\n\n${HANDLING}`,
    });
    return;
  }
  const comments = await api(
    "GET",
    `${prefix}/issues/${existing.number}/comments?per_page=100`,
  );
  if (
    existing.body.includes(occurrence) ||
    comments.some((comment) => comment.body.includes(occurrence))
  )
    return;
  const count =
    2 + comments.filter((comment) => comment.body.includes("<!-- e2e-run:")).length;
  await api("POST", `${prefix}/issues/${existing.number}/comments`, {
    body: `재발 ${count}회\n\n${body}`,
  });
}

async function main() {
  const [command, directory] = process.argv.slice(2);
  if (command !== "issues" || !directory) {
    throw new Error("사용법: node scripts/ci/e2e-ci.mjs issues <실패 기록 폴더>");
  }
  if (
    !["schedule", "workflow_dispatch"].includes(process.env.GITHUB_EVENT_NAME) ||
    process.env.GITHUB_REF !== "refs/heads/main"
  ) {
    throw new Error("main 의 schedule 과 workflow_dispatch 에서만 실패 이슈를 등록한다");
  }
  const text = await readFile(resolve(directory, RECORD_FILE), "utf8").catch(() => "");
  await publishFailure(scenarioFrom(text), githubApi(process.env.GH_TOKEN), {
    repository: process.env.GITHUB_REPOSITORY,
    runId: process.env.GITHUB_RUN_ID,
    attempt: process.env.GITHUB_RUN_ATTEMPT,
    serverUrl: process.env.GITHUB_SERVER_URL,
  });
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  await main();
}
