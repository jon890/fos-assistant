import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { EXIT, NON_REQUIRED, checkRequired, collect, decide, remergeReasons } from "../../scripts/pr/merge-ready.mjs";

const REQUIRED = ["unit", "backend"];
const ok = (name: string) => ({ name, state: "SUCCESS", startedAt: "2026-10-12T01:00:00Z" });
const base = {
  required: REQUIRED,
  runs: [ok("unit"), ok("backend")],
  isDraft: false,
  mergeable: "MERGEABLE",
  mergeStateStatus: "CLEAN",
  unresolvedThreads: 0,
  containsMain: true,
  remergeReasons: [],
};

test("필수 검사를 이름으로 대조하고 아직 없는 검사는 도는 중으로 본다", () => {
  assert.deepEqual(checkRequired(REQUIRED, [ok("unit")]), { pending: ["backend"], failed: [] });
  assert.deepEqual(checkRequired(REQUIRED, [ok("unit"), { name: "backend", state: "IN_PROGRESS" }]), { pending: ["backend"], failed: [] });
  assert.deepEqual(checkRequired(REQUIRED, [ok("unit"), { name: "backend", state: "FAILURE" }]), { pending: [], failed: ["backend(FAILURE)"] });
  assert.deepEqual(checkRequired(REQUIRED, [ok("unit"), { name: "backend", state: "SKIPPED" }]), { pending: [], failed: ["backend(SKIPPED)"] });
});

test("같은 이름의 검사가 여러 번 있으면 성공 하나가 실패를 가리지 않는다", () => {
  const runs = [ok("unit"), ok("backend"), { name: "backend", state: "FAILURE" }];
  assert.deepEqual(checkRequired(REQUIRED, runs).failed, ["backend(FAILURE)"]);
});

test("모든 조건이 맞으면 머지 가능이다", () => {
  assert.deepEqual(decide(base), { verdict: "머지 가능", code: EXIT.READY });
});

test("필수가 아닌 claude-review 만 실패한 UNSTABLE 은 머지할 수 있다", () => {
  const unstable = { ...base, mergeStateStatus: "UNSTABLE", runs: [...base.runs, { name: NON_REQUIRED, state: "FAILURE" }] };
  assert.equal(decide(unstable).code, EXIT.READY);
  const other = { ...unstable, runs: [...base.runs, { name: NON_REQUIRED, state: "FAILURE" }, { name: "lint", state: "FAILURE" }] };
  assert.equal(decide(other).code, EXIT.BLOCKED);
  assert.match(decide(other).verdict, /lint/);
  // 실패한 검사를 하나도 찾지 못한 UNSTABLE 은 원인을 모르므로 통과시키지 않는다.
  assert.equal(decide({ ...base, mergeStateStatus: "UNSTABLE" }).code, EXIT.BLOCKED);
});

test("도는 중이거나 없는 필수 검사는 대기, 실패는 막힘이다", () => {
  assert.equal(decide({ ...base, runs: [ok("unit")] }).code, EXIT.WAIT);
  assert.equal(decide({ ...base, runs: [ok("unit"), { name: "backend", state: "FAILURE" }] }).code, EXIT.BLOCKED);
  assert.equal(decide({ ...base, mergeStateStatus: "UNKNOWN", mergeable: "UNKNOWN" }).code, EXIT.WAIT);
});

test("Draft, 충돌, 미해결 스레드, BLOCKED 는 막힘이다", () => {
  assert.match(decide({ ...base, isDraft: true }).verdict, /^막힘\(Draft/);
  assert.equal(decide({ ...base, mergeable: "CONFLICTING", mergeStateStatus: "DIRTY" }).code, EXIT.BLOCKED);
  assert.match(decide({ ...base, unresolvedThreads: 2 }).verdict, /미해결 리뷰 스레드 2개/);
  assert.equal(decide({ ...base, mergeStateStatus: "BLOCKED" }).code, EXIT.BLOCKED);
});

test("head 가 main 을 포함하지 않아도 까닭이 없으면 머지 가능, 있으면 다시 합치기다", () => {
  assert.equal(decide({ ...base, containsMain: false }).code, EXIT.READY);
  const stale = decide({ ...base, containsMain: false, remergeReasons: ["PR 과 main 이 같은 파일을 고쳤다(a.ts)"] });
  assert.equal(stale.code, EXIT.REMERGE);
  assert.match(stale.verdict, /^main 다시 합치기 필요\(PR 과 main 이 같은 파일/);
});

test("겹치지 않고 위험 경로도 없으면 직전 CI 를 쓴다", () => {
  assert.deepEqual(remergeReasons(["web/src/a.ts"], ["backend/src/main/java/A.java", "web/src/c.ts"]), []);
});

test("같은 파일을 고쳤으면 다시 합친다", () => {
  const reasons = remergeReasons(["web/src/a.ts", "web/src/b.ts"], ["web/src/b.ts"]);
  assert.equal(reasons.length, 1);
  assert.match(reasons[0], /같은 파일.*web\/src\/b\.ts/);
});

test("마이그레이션, 빌드와 의존성, test/unit, 공유 계약은 어느 쪽이 건드려도 다시 합친다", () => {
  const cases: [string, string][] = [
    ["backend/src/main/resources/db/migration/V20261012000000__a.sql", "마이그레이션"],
    ["backend/build.gradle.kts", "빌드와 의존성"],
    ["web/pnpm-lock.yaml", "빌드와 의존성"],
    ["backend/gradle/libs.versions.toml", "빌드와 의존성"],
    ["backend/gradle/wrapper/gradle-wrapper.properties", "빌드와 의존성"],
    ["hermes/connectors/naver-blog/scripts/test.Dockerfile", "빌드와 의존성"],
    ["hermes/connectors/gmail/package.json", "빌드와 의존성"],
    ["test/unit/doc-files.test.ts", "test/unit"],
    ["hermes/docs/hermes-contract.md", "공유 계약"],
    ["hermes/connectors/gmail/tests/contracts.test.ts", "공유 계약"],
    ["test/resources/connector-argument-catalog.json", "공유 계약"],
    ["test/fixtures/connector-tool-descriptions.json", "공유 계약"],
    ["test/support/pick-port.ts", "공유 계약"],
    ["test/e2e/fake-hermes/run-routes.ts", "공유 계약"],
    [".github/workflows/ci.yml", "공유 계약"],
  ];
  for (const [file, label] of cases) {
    const fromMain = remergeReasons(["web/src/a.ts"], [file]);
    assert.equal(fromMain.length, 1, file);
    assert.match(fromMain[0], new RegExp(`^main 이 .*${label}`), file);
    const fromPr = remergeReasons([file], ["web/src/x.ts"]);
    assert.match(fromPr[0], new RegExp(`^PR 이 .*${label}`), file);
  }
});

// gh 와 git 호출을 흉내 낸다. 키는 명령과 인자를 공백으로 이은 앞부분이다.
function fakeRun(overrides: Record<string, { status?: number; stdout?: string }> = {}) {
  const table: Record<string, { status?: number; stdout?: string }> = {
    "gh pr view": { stdout: JSON.stringify({ isDraft: false, mergeable: "MERGEABLE", mergeStateStatus: "CLEAN", headRefOid: "abc", baseRefName: "main" }) },
    "gh repo view": { stdout: JSON.stringify({ owner: { login: "o" }, name: "r" }) },
    "gh api repos": { stdout: "unit\nbackend\n" },
    "gh pr checks": { status: 8, stdout: JSON.stringify([ok("unit"), ok("backend")]) },
    "gh api graphql": { stdout: JSON.stringify({ data: { repository: { pullRequest: { reviewThreads: { pageInfo: { hasNextPage: false }, nodes: [{ isResolved: true }, { isResolved: false }] } } } } }) },
    "git fetch": {},
    "git merge-base": {},
    ...overrides,
  };
  return (command: string, args: string[]) => {
    const key = [command, ...args].slice(0, 3).join(" ");
    const hit = Object.entries(table).find(([prefix]) => key.startsWith(prefix));
    if (!hit) return { status: 1, stdout: "", stderr: `대역에 없는 호출: ${key}` };
    return { status: hit[1].status ?? 0, stdout: hit[1].stdout ?? "", stderr: "" };
  };
}

test("조회한 사실을 모아 판정 입력으로 만든다", () => {
  const facts = collect("7", fakeRun());
  assert.deepEqual(facts.required, ["unit", "backend"]);
  assert.equal(facts.unresolvedThreads, 1);
  assert.equal(facts.containsMain, true);
  assert.equal(decide(facts).code, EXIT.BLOCKED);
});

test("head 가 main 을 포함하지 않으면 CI 시작 시각의 main 과 PR 파일을 비교한다", () => {
  const run = fakeRun({
    "git merge-base": { status: 1 },
    "git rev-list": { stdout: "deadbeef\n" },
    "git diff": { stdout: "backend/build.gradle.kts\n" },
    "gh pr diff": { stdout: "web/src/a.ts\n" },
  });
  const facts = collect("7", run);
  assert.equal(facts.containsMain, false);
  assert.match(facts.remergeReasons.join(), /main 이 빌드와 의존성/);
});

test("필수 검사 조회가 실패하거나 0개면 판정하지 않는다", () => {
  assert.throws(() => collect("7", fakeRun({ "gh api repos": { status: 1 } })), /브랜치 보호 필수 검사 조회 실패/);
  assert.throws(() => collect("7", fakeRun({ "gh api repos": { stdout: "" } })), /0개/);
});

test("PR 번호가 없으면 종료 코드 2 로 끝난다", () => {
  const result = spawnSync("node", ["scripts/pr/merge-ready.mjs"], { encoding: "utf8" });
  assert.equal(result.status, EXIT.LOOKUP);
  assert.match(result.stderr, /사용법/);
});
