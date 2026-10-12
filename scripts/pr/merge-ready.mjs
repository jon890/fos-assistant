#!/usr/bin/env node
// PR 이 지금 머지할 수 있는지 한 번에 판정한다. 머지 직전에 손으로 반복하던 확인을 대신한다.
// 입구는 scripts/pr/merge-ready.sh 이고, 사용법과 종료 코드는 그 머리말이 갖는다.
//
// 판정 규칙
//   1. 브랜치 보호의 필수 검사 이름을 읽고 현재 head 의 check run 과 이름으로 대조한다.
//      조회가 실패하거나 필수 검사가 0개면 통과와 구분되지 않아 판정하지 않는다(종료 코드 2).
//   2. 미해결 리뷰 스레드, Draft, mergeStateStatus 를 본다.
//      필수가 아닌 claude-review 만 실패해 UNSTABLE 이면 CLEAN 과 같이 본다.
//   3. head 가 origin/main 을 포함하지 않으면 다시 합쳐야 하는지 가른다.
//      CI 뒤 main 에 들어온 커밋이 아래 조건에 하나도 걸리지 않으면 직전 CI 결과를 쓴다.
//      main 이 바뀔 때마다 전체 CI 를 다시 돌리면 여러 PR 이 함께 돌 때 머지가 끝없이 밀린다.
//   4. 조회하는 동안 head 가 바뀌지 않았는지 마지막에 다시 읽는다. 바뀌었으면 대기다.
//      판정한 head 는 출력 맨 앞의 head 줄에 있고, 머지는 그 해시로 고정한다(--match-head-commit).
//
// CI 가 돈 기준 main 은 PR 의 merge 커밋 부모가 아니라 가장 이른 검사 시작 시각의 main 으로 정한다.
// refs/pull/N/merge 는 main 이 움직이면 GitHub 가 다시 만들어 그때의 base 를 git 에서 읽을 수 없다.
import { spawnSync } from "node:child_process";
import { realpathSync } from "node:fs";
import { pathToFileURL } from "node:url";

export const VERDICT = { READY: "머지 가능", WAIT: "대기", REMERGE: "main 다시 합치기 필요", BLOCKED: "막힘" };
export const EXIT = { READY: 0, LOOKUP: 2, WAIT: 10, REMERGE: 11, BLOCKED: 12 };
export const NON_REQUIRED = "claude-review";

// 파일이 겹치지 않아도 함께 깨질 수 있는 곳이다. 한쪽이라도 건드렸으면 직전 CI 결과를 믿지 않는다.
// 마지막 줄의 공유 계약은 여러 모듈이 같은 값을 읽는 파일이고, 오늘 실제로 판정한 예에서 뽑았다.
export const RISK_PATHS = [
  ["마이그레이션", /^backend\/src\/main\/(resources\/db\/migration\/|java\/db\/migration\/)/],
  ["빌드와 의존성 파일", /(^|\/)(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|package\.json|pnpm-lock\.yaml|pnpm-workspace\.yaml|bun\.lockb?|Dockerfile|[^/]*\.Dockerfile|libs\.versions\.toml|gradle-wrapper\.properties|pyproject\.toml|requirements[^/]*\.txt)$/],
  ["저장소 전체를 보는 test/unit 검사", /^test\/unit\//],
  // Hermes 계약 문서(hermes 테스트의 입력), 커넥터 계약 테스트, 합성 커넥터 catalog 와 fixture(backend 와 hermes 가 읽음), 테스트 지원 코드와 가짜 Hermes(실제와 다르면 테스트가 통과한다), CI 판정 자체(워크플로와 scripts/ci, check-local.sh).
  ["공유 계약", /^(hermes\/docs\/hermes-contract\.md|hermes\/connectors\/[^/]+\/tests\/contracts?\.test\.ts|hermes\/tests\/(fixtures\/|[^/]*contract[^/]*)|test\/(resources|fixtures|support)\/|test\/e2e\/fake-hermes\/|\.github\/workflows\/|scripts\/ci\/|scripts\/dev\/check-local\.sh$)/],
];

/**
 * CI 뒤 main 에 들어온 파일과 PR 파일로 다시 합쳐야 하는 까닭을 모두 낸다. 비면 직전 CI 를 써도 된다.
 * 이름 변경은 옛 경로와 새 경로가 모두 들어 있어야 한다.
 */
export function remergeReasons(prFiles, mainFiles) {
  const reasons = [];
  const mainSet = new Set(mainFiles);
  const overlap = prFiles.filter((file) => mainSet.has(file));
  if (overlap.length) reasons.push(`PR 과 main 이 같은 파일을 고쳤다(${overlap.slice(0, 3).join(", ")}${overlap.length > 3 ? ` 외 ${overlap.length - 3}개` : ""})`);
  for (const [label, pattern] of RISK_PATHS) {
    const side = [["PR", prFiles], ["main", mainFiles]].filter(([, files]) => files.some((file) => pattern.test(file))).map(([name]) => name);
    if (side.length) reasons.push(`${side.join(" 과 ")} 이 ${label} 을 건드렸다`);
  }
  return reasons;
}

const PENDING_STATES = new Set(["PENDING", "IN_PROGRESS", "QUEUED", "WAITING", "REQUESTED", "EXPECTED"]);

/**
 * 필수 검사를 이름으로 대조한다. 아직 생기지 않은 검사는 도는 중으로 본다.
 * SKIPPED 는 GitHub 가 통과로 보지만 보수적으로 미통과로 둔다. 판정 job 이 건너뛰기를 통과로 보고하는 구조다.
 */
export function checkRequired(required, runs) {
  const byName = new Map();
  for (const run of runs) {
    // 같은 이름이 여러 번 있으면 가장 나쁜 쪽을 쓴다. 성공 하나가 실패를 가리지 않게 한다.
    const rank = (state) => (state === "SUCCESS" ? 0 : PENDING_STATES.has(state) ? 1 : 2);
    const prev = byName.get(run.name);
    if (!prev || rank(run.state) > rank(prev)) byName.set(run.name, run.state);
  }
  const pending = [];
  const failed = [];
  for (const name of required) {
    const state = byName.get(name);
    if (state === undefined || PENDING_STATES.has(state)) pending.push(name);
    else if (state !== "SUCCESS") failed.push(`${name}(${state})`);
  }
  return { pending, failed };
}

/** 필수가 아닌 검사 중 실패한 것의 이름. UNSTABLE 이 claude-review 때문인지 가린다. */
export function failedOthers(required, runs) {
  const names = new Set(required);
  return [...new Set(runs.filter((run) => !names.has(run.name) && !PENDING_STATES.has(run.state) && !["SUCCESS", "SKIPPED", "NEUTRAL"].includes(run.state)).map((run) => run.name))];
}

/**
 * 모은 사실로 판정 하나를 낸다. 순서가 곧 우선순위다.
 * input: { required, runs, isDraft, mergeable, mergeStateStatus, unresolvedThreads, containsMain, remergeReasons, headRefOid, headRecheck }
 */
export function decide(input) {
  const { required, runs, isDraft, mergeable, mergeStateStatus, unresolvedThreads, containsMain } = input;
  const blocked = (why) => ({ verdict: `${VERDICT.BLOCKED}(${why})`, code: EXIT.BLOCKED });
  if (input.headRecheck !== input.headRefOid) return { verdict: `${VERDICT.WAIT}(조회하는 동안 head 가 ${input.headRefOid} 에서 ${input.headRecheck} 로 바뀌었다. 다시 실행한다)`, code: EXIT.WAIT };
  if (isDraft) return blocked("Draft 상태라 gh pr ready 가 먼저다");
  if (mergeable === "CONFLICTING" || mergeStateStatus === "DIRTY") return blocked("main 과 충돌한다");
  const { pending, failed } = checkRequired(required, runs);
  if (failed.length) return blocked(`필수 검사 실패: ${failed.join(", ")}`);
  if (pending.length) return { verdict: `${VERDICT.WAIT}(도는 중이거나 아직 없는 필수 검사: ${pending.join(", ")})`, code: EXIT.WAIT };
  if (unresolvedThreads > 0) return blocked(`미해결 리뷰 스레드 ${unresolvedThreads}개`);
  if (mergeStateStatus === "UNSTABLE") {
    const others = failedOthers(required, runs);
    if (!(others.length > 0 && others.every((name) => name === NON_REQUIRED))) {
      return blocked(`UNSTABLE 이고 실패한 검사가 ${NON_REQUIRED} 만이 아니다(${others.join(", ") || "실패한 검사를 찾지 못함"})`);
    }
  } else if (mergeStateStatus === "BLOCKED") {
    return blocked("mergeStateStatus 가 BLOCKED 다(승인 요구나 브랜치 보호 규칙을 확인한다)");
  } else if (mergeStateStatus === "UNKNOWN" || mergeable === "UNKNOWN") {
    return { verdict: `${VERDICT.WAIT}(GitHub 가 mergeable 을 아직 계산하지 못했다)`, code: EXIT.WAIT };
  }
  if (!containsMain) {
    const reasons = input.remergeReasons;
    if (reasons.length) return { verdict: `${VERDICT.REMERGE}(${reasons.join("; ")})`, code: EXIT.REMERGE };
  }
  return { verdict: VERDICT.READY, code: EXIT.READY };
}

// ---- 조회. gh 와 git 호출은 run 으로 주입해 대역으로 바꾼다 ----

export function realRun(command, args) {
  const result = spawnSync(command, args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  return { status: result.status ?? 1, stdout: result.stdout ?? "", stderr: result.stderr ?? "" };
}

class LookupError extends Error {}

function must(run, command, args, what) {
  const result = run(command, args);
  if (result.status !== 0) throw new LookupError(`${what} 조회 실패: ${(result.stderr || "").trim().split("\n")[0] || `종료 코드 ${result.status}`}`);
  return result.stdout;
}

const lines = (text) => text.split("\n").map((line) => line.trim()).filter(Boolean);

const THREADS_QUERY = `query($owner:String!,$repo:String!,$number:Int!,$after:String){repository(owner:$owner,name:$repo){pullRequest(number:$number){reviewThreads(first:100,after:$after){pageInfo{hasNextPage endCursor} nodes{isResolved}}}}}`;

/** 판정에 필요한 사실을 모은다. 조회가 하나라도 실패하면 LookupError 로 끝낸다. */
export function collect(pr, run = realRun) {
  const view = JSON.parse(must(run, "gh", ["pr", "view", pr, "--json", "isDraft,mergeable,mergeStateStatus,headRefOid,baseRefName"], "PR"));
  const repo = JSON.parse(must(run, "gh", ["repo", "view", "--json", "owner,name"], "저장소"));
  const slug = `${repo.owner.login}/${repo.name}`;
  const required = lines(must(run, "gh", ["api", `repos/${slug}/branches/${view.baseRefName}/protection/required_status_checks`, "--jq", ".contexts[]"], "브랜치 보호 필수 검사"));
  if (required.length === 0) throw new LookupError("브랜치 보호 필수 검사가 0개로 나왔다. 통과와 구분되지 않아 판정하지 않는다");
  // 아직 도는 검사가 있으면 gh pr checks 가 8 로 끝나므로 출력이 있으면 읽는다.
  const checks = run("gh", ["pr", "checks", pr, "--json", "name,state,startedAt"]);
  if (!checks.stdout.trim()) throw new LookupError(`check run 조회 실패: ${(checks.stderr || "").trim().split("\n")[0] || `종료 코드 ${checks.status}`}`);
  const runs = JSON.parse(checks.stdout);

  let unresolvedThreads = 0;
  let after = "";
  for (;;) {
    const args = ["api", "graphql", "-f", `query=${THREADS_QUERY}`, "-F", `owner=${repo.owner.login}`, "-F", `repo=${repo.name}`, "-F", `number=${pr}`];
    if (after) args.push("-f", `after=${after}`);
    const page = JSON.parse(must(run, "gh", args, "리뷰 스레드")).data.repository.pullRequest.reviewThreads;
    unresolvedThreads += page.nodes.filter((node) => !node.isResolved).length;
    if (!page.pageInfo.hasNextPage) break;
    after = page.pageInfo.endCursor;
  }

  must(run, "git", ["fetch", "--quiet", "origin", view.baseRefName, `refs/pull/${pr}/head`], "git fetch");
  const base = `origin/${view.baseRefName}`;
  const containsMain = run("git", ["merge-base", "--is-ancestor", base, view.headRefOid]).status === 0;
  let reasons = [];
  if (!containsMain) {
    const started = runs.map((r) => r.startedAt).filter((t) => t && !t.startsWith("0001")).sort()[0];
    if (!started) throw new LookupError("검사 시작 시각을 찾지 못해 CI 가 돈 기준 main 을 정할 수 없다");
    const ciBase = lines(must(run, "git", ["rev-list", "-1", `--before=${started}`, base], "CI 기준 main"))[0];
    if (!ciBase) throw new LookupError("CI 시작 시각 이전의 main 커밋이 없다");
    const mainFiles = must(run, "git", ["diff", "--name-only", "--no-renames", ciBase, base], "main 변경 파일").split("\n").filter(Boolean);
    const prFiles = lines(must(run, "gh", ["pr", "diff", pr, "--name-only"], "PR 변경 파일"));
    reasons = remergeReasons(prFiles, mainFiles);
  }
  const headRecheck = JSON.parse(must(run, "gh", ["pr", "view", pr, "--json", "headRefOid"], "PR head 재확인")).headRefOid;
  return { ...view, required, runs, unresolvedThreads, containsMain, remergeReasons: reasons, headRecheck };
}

function main() {
  const pr = process.argv[2];
  if (!pr || !/^\d+$/.test(pr)) {
    console.error("사용법: scripts/pr/merge-ready.sh <PR 번호>");
    process.exit(EXIT.LOOKUP);
  }
  let facts;
  try {
    facts = collect(pr);
  } catch (error) {
    if (!(error instanceof LookupError)) throw error;
    console.error(error.message);
    process.exit(EXIT.LOOKUP);
  }
  const { pending, failed } = checkRequired(facts.required, facts.runs);
  console.log(`head: ${facts.headRefOid}`);
  console.log(`필수 검사 ${facts.required.length}개: 통과 ${facts.required.length - pending.length - failed.length}, 도는 중 ${pending.length}, 실패 ${failed.length}`);
  console.log(`Draft: ${facts.isDraft ? "예" : "아니오"}, mergeable: ${facts.mergeable}, mergeStateStatus: ${facts.mergeStateStatus}`);
  console.log(`미해결 리뷰 스레드: ${facts.unresolvedThreads}개`);
  console.log(`head 가 origin/${facts.baseRefName} 를 포함: ${facts.containsMain ? "예" : "아니오"}`);
  const { verdict, code } = decide(facts);
  console.log(verdict);
  process.exit(code);
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) main();
