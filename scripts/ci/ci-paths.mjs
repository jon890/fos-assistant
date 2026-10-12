#!/usr/bin/env node
// PR 이 바꾼 파일을 보고 CI 의 무거운 job 을 돌릴지 정하고, 필수 검사 job 이 그 결과를 판정한다.
//
//   node scripts/ci/ci-paths.mjs changes  바뀐 파일로 job 마다 true/false 를 GITHUB_OUTPUT 에 쓴다
//   node scripts/ci/ci-paths.mjs gate     CHANGES_RESULT, SHOULD_RUN, JOB_RESULT 로 필수 검사의 통과를 정한다
//
// 규칙과 까닭은 docs/adr/ADR-20261012-ci-path-filter.md 가 갖는다.
// 어느 규칙에도 맞지 않는 파일은 모든 job 을 돌린다. 새 디렉터리나 설정 파일이 검사를 건너뛰게 두지 않는다.
// CI 의 changes job 은 의존을 설치하지 않으므로 Node 내장 모듈만 쓴다.
import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

// unit, public-safe 와 quality 의 파일 길이 검사는 언제나 돌아 여기에 두지 않는다.
export const JOBS = ["backend", "web", "browser", "e2e", "hermes", "quality"];
const ALL = JOBS;
const NONE = [];

// 위에서부터 처음 맞는 규칙 하나만 쓴다. 좁은 규칙을 넓은 규칙보다 위에 둔다.
export const RULES = [
  // 판정 자체를 바꾸는 파일은 모두 돌린다.
  [/^\.github\/workflows\/ci\.yml$/, ALL],
  [/^scripts\/ci\/ci-paths\.mjs$/, ALL],
  // 스킬 본문은 실행에 주입하므로 설명 문서와 달리 Hermes 관련 검사를 돌린다.
  [/^hermes\/.*\/skills\/.*\.md$/, ["backend", "e2e", "hermes", "quality"]],
  // Hermes 계약 문서와 커넥터 README 존재 여부는 hermes 테스트의 입력이다.
  [/^hermes\/docs\/hermes-contract\.md$/, ["hermes"]],
  [/^hermes\/connectors\/[^/]+\/README\.md$/, ["hermes"]],
  [/^\.github\/CODEOWNERS$/, ["hermes"]],

  // 어느 job 의 입력도 아닌 문서다. 문서를 읽는 테스트는 언제나 도는 unit 에 있다.
  [/^(docs|tasks|\.claude)\//, NONE],
  [/(^|\/)(README(?:\.[^/.]+)?|AGENTS|CLAUDE|ICON-SOURCE)\.md$/, NONE],
  [/^(LICENSE|\.gitignore)$/, NONE],
  [/^(backend|web|hermes)\/docs\//, NONE],
  // 다른 workflow 와 리뷰 지시문은 CI job 이 읽지 않는다. 이것을 검사하는 테스트는 unit 에 있다.
  [/^\.github\/workflows\/(claude-code-review|claude-architecture-audit|pr-size)\.yml$/, NONE],
  [/^\.github\/workflows\/[^/]+\.txt$/, NONE],

  // hermes 의 계약 테스트가 hermes/docs 를 읽는다.
  [/^hermes\/docs\//, ["hermes"]],
  // 브라우저가 쓰는 테스트 지원 API 와 리소스는 smokeRun 의 입력이다.
  [/^backend\/src\/test\/(java\/com\/bifos\/assistant\/testsupport\/|resources\/)/, ["backend", "browser", "e2e", "hermes", "quality"]],
  // 그 밖의 backend 테스트 소스만 바꾸면 브라우저 shard 는 건너뛴다.
  [/^backend\/src\/test\//, ["backend", "e2e", "hermes", "quality"]],
  // 브라우저 검사가 Control Plane 을, E2E 가 Control Plane 과 web/src 를 띄운다.
  [/^backend\//, ["backend", "browser", "e2e", "quality"]],
  [/^web\//, ["web", "browser", "e2e", "quality"]],
  // backend 테스트가 커넥터와 plugin 원본을 읽는다. Hermes 만 바꾸면 브라우저 shard 는 건너뛴다.
  [/^hermes\//, ["hermes", "backend", "e2e", "quality"]],

  [/^test\/unit\//, NONE],
  [/^test\/web\//, ["web"]],
  [/^test\/browser\//, ["browser"]],
  // 브라우저 검사가 E2E 의 가짜 Hermes 를, backend 와 hermes 테스트가 E2E 의 Python plugin 을 쓴다.
  [/^test\/e2e\//, ["e2e", "browser", "backend", "hermes"]],
  [/^test\/spotless-jdt\.test\.py$/, ["quality"]],
  // 합성 커넥터 catalog 는 backend 와 hermes 계약 검사가 읽고 브라우저 대역은 읽지 않는다.
  [/^test\/resources\/connector-argument-(?:masked-)?catalog\.json$/, ["backend", "hermes"]],

  [/^scripts\/checks\/check-mysql-migration\.sh$/, ["backend"]],
  [/^scripts\/checks\/(check-connectors\.sh|check-hermes-contract\.sh)$/, ["hermes"]],
  [/^scripts\/ci\/check-connector-package\.sh$/, ["hermes"]],
  [/^scripts\/checks\/(quality\.sh|quality-report\.mjs|format-connectors\.mjs|check-file-length\.mjs|file-length-baseline\.json)$/, ["quality"]],
  [/^scripts\/(?:ci\/)?browser-[^/]+\.mjs$/, ["browser"]],
  // unit 과 public-safe 가 언제나 부르는 검사와, CI job 이 부르지 않는 로컬 입구와 PR 스크립트다.
  [/^scripts\/checks\/(check-public-safe\.sh|check-migration-versions\.mjs)$/, NONE],
  [/^scripts\/ci\/check-feature-covers\.mjs$/, NONE],
  [/^scripts\/(dev|pr)\//, NONE],
  [/^scripts\/README\.md$/, NONE],
  // 브라우저가 쓰는 스크립트는 위에서 따로 고른다. 그 밖의 새 스크립트도 브라우저 shard 는 켜지 않는다.
  [/^scripts\//, ["backend", "web", "e2e", "hermes", "quality"]],
];

/** 파일 하나가 돌려야 하는 job 목록. 맞는 규칙이 없으면 모두 돌린다. */
export function jobsFor(file) {
  for (const [pattern, jobs] of RULES) {
    if (pattern.test(file)) return jobs;
  }
  return ALL;
}

/**
 * 바뀐 파일 목록으로 job 마다 돌릴지와 처음 그렇게 정한 파일을 낸다.
 * 목록이 비면 판정이 잘못됐을 수 있어 모두 돌린다.
 */
export function classify(files) {
  const result = Object.fromEntries(JOBS.map((job) => [job, { run: false, because: null }]));
  if (files.length === 0) {
    for (const job of JOBS) result[job] = { run: true, because: "(바뀐 파일을 찾지 못함)" };
    return result;
  }
  for (const file of files) {
    for (const job of jobsFor(file)) {
      if (!result[job].run) result[job] = { run: true, because: file };
    }
  }
  return result;
}

/**
 * 필수 검사 job 의 통과 여부. 돌려야 했으면 성공만, 돌리지 않기로 했으면 생략만 통과다.
 * 판정 job 이 실패하거나 취소되면 그 뒤 job 도 생략되므로 판정 job 의 성공을 먼저 본다.
 */
export function gate({ changes, run, result }) {
  if (changes !== "success") return { pass: false, reason: `변경 판정 job 결과가 ${changes || "없음"}` };
  if (run === "true") {
    return result === "success"
      ? { pass: true, reason: "돌려야 하는 검사가 통과했다" }
      : { pass: false, reason: `돌려야 하는 검사의 결과가 ${result || "없음"}` };
  }
  if (run === "false") {
    return result === "skipped"
      ? { pass: true, reason: "바뀐 파일이 이 검사의 입력이 아니어서 건너뛰었다" }
      : { pass: false, reason: `건너뛰기로 한 검사의 결과가 ${result || "없음"}` };
  }
  return { pass: false, reason: `돌릴지 여부가 올바르지 않다: ${run || "없음"}` };
}

/** PR 의 merge 커밋이 첫 부모(base) 에 대해 바꾼 파일. 이름 변경은 옛 경로와 새 경로를 모두 낸다. */
function changedFiles() {
  const parents = execFileSync("git", ["rev-list", "--parents", "-n", "1", "HEAD"], { encoding: "utf8" }).trim().split(" ");
  if (parents.length !== 3) throw new Error(`PR merge 커밋이 아니다(부모 ${parents.length - 1}개). checkout 의 ref 와 fetch-depth 를 확인한다`);
  const output = execFileSync("git", ["diff", "--name-only", "--no-renames", "-z", "HEAD^1", "HEAD"], { encoding: "utf8" });
  return output.split("\0").filter(Boolean);
}

function main() {
  const [command] = process.argv.slice(2);
  if (command === "changes") {
    // push 와 정기 실행은 모든 검사를 돌려 main 에서 판정 규칙이 놓친 것을 잡는다.
    const pr = process.env.GITHUB_EVENT_NAME === "pull_request";
    const result = pr
      ? classify(changedFiles())
      : Object.fromEntries(JOBS.map((job) => [job, { run: true, because: `(${process.env.GITHUB_EVENT_NAME || "PR 밖"} 실행)` }]));
    const lines = JOBS.map((job) => `${job}=${result[job].run}`);
    if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `${lines.join("\n")}\n`);
    const table = ["| job | 실행 | 처음 맞은 파일 |", "| --- | --- | --- |",
      ...JOBS.map((job) => `| ${job} | ${result[job].run ? "돌림" : "건너뜀"} | ${result[job].because ?? ""} |`)];
    if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${table.join("\n")}\n`);
    console.log(lines.join("\n"));
    return;
  }
  if (command === "gate") {
    const verdict = gate({ changes: process.env.CHANGES_RESULT, run: process.env.SHOULD_RUN, result: process.env.JOB_RESULT });
    console.log(verdict.reason);
    process.exit(verdict.pass ? 0 : 1);
  }
  console.error("사용법: node scripts/ci/ci-paths.mjs <changes|gate>");
  process.exit(2);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
