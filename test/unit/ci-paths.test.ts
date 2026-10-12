import assert from "node:assert/strict";
import test from "node:test";
import { JOBS, classify, gate, jobsFor } from "../../scripts/ci/ci-paths.mjs";

const running = (files: string[]) => JOBS.filter((job) => classify(files)[job].run);

test("문서와 계획서만 바꾸면 무거운 job 을 모두 건너뛴다", () => {
  const docs = [
    "docs/adr/INDEX.md", "docs/features/connector.md", "tasks/plan1/phase-01.md", ".claude/skills/pr-merge/SKILL.md",
    "AGENTS.md", "README.ko.md", "backend/docs/data-schema.md", "backend/AGENTS.md", "web/docs/code-architecture.md",
    "web/AGENTS.md", ".github/workflows/code-review-prompt.txt", ".github/workflows/pr-size.yml", "scripts/pr/pr-size.mjs",
    "scripts/dev/check-local.sh", "test/unit/doc-files.test.ts",
  ];
  assert.deepEqual(running(docs), []);
});

test("모듈 코드는 그 모듈과 그것을 띄우는 job 을 돌린다", () => {
  assert.deepEqual(running(["backend/src/main/java/A.java"]), ["backend", "browser", "e2e", "quality"]);
  assert.deepEqual(running(["web/src/app/page.tsx"]), ["web", "browser", "e2e", "quality"]);
  assert.deepEqual(running(["hermes/connectors/gmail/server.py"]), ["backend", "browser", "e2e", "hermes", "quality"]);
  assert.deepEqual(running(["hermes/docs/hermes-contract.md"]), ["hermes"]);
  assert.deepEqual(running(["test/web/a.test.ts"]), ["web"]);
  assert.deepEqual(running(["test/browser/chat.spec.ts"]), ["browser"]);
  assert.deepEqual(running(["test/e2e/run.ts"]), ["backend", "browser", "e2e", "hermes"]);
});

test("모듈 안의 설정과 의존 목록도 모듈 코드로 본다", () => {
  for (const file of ["web/package.json", "web/pnpm-lock.yaml", "web/Dockerfile", "backend/build.gradle.kts", "hermes/connectors/naver-blog/bun.lock"]) {
    assert.ok(running([file]).length >= 4, file);
  }
});

test("job 이 부르는 스크립트는 그 job 을 돌린다", () => {
  assert.deepEqual(jobsFor("scripts/checks/check-mysql-migration.sh"), ["backend"]);
  assert.deepEqual(jobsFor("scripts/ci/check-connector-package.sh"), ["hermes"]);
  assert.deepEqual(jobsFor("scripts/checks/quality.sh"), ["quality"]);
  assert.deepEqual(jobsFor("scripts/ci/browser-ci.mjs"), ["browser"]);
});

test("판정 규칙과 workflow 를 바꾸거나 규칙에 없는 파일이면 모두 돌린다", () => {
  for (const file of [
    ".github/workflows/ci.yml", "scripts/ci/ci-paths.mjs", ".github/actions/setup/action.yml", "scripts/new-script.sh",
    "scripts/checks/new-check.sh", "test/support/pick-port.ts", "test/fixtures/a.json", "test/resources/a.json",
    "test/package.json", "package.json", "new-dir/a.ts",
  ]) {
    assert.deepEqual(running([file]), JOBS, file);
  }
});

test("바뀐 파일이 하나도 없으면 판정을 믿지 않고 모두 돌린다", () => {
  assert.deepEqual(running([]), JOBS);
});

test("문서와 코드를 함께 바꾸면 코드 쪽 job 을 돌리고 처음 맞은 파일을 남긴다", () => {
  const result = classify(["docs/a.md", "web/src/a.ts"]);
  assert.equal(result.web.because, "web/src/a.ts");
  assert.equal(result.backend.run, false);
  assert.equal(result.backend.because, null);
});

test("필수 검사는 돌려야 했으면 성공만, 건너뛰기로 했으면 생략만 통과한다", () => {
  const results = ["success", "failure", "cancelled", "skipped", ""];
  for (const changes of results) {
    for (const run of ["true", "false", ""]) {
      for (const result of results) {
        const expected = changes === "success" && ((run === "true" && result === "success") || (run === "false" && result === "skipped"));
        assert.equal(gate({ changes, run, result }).pass, expected, JSON.stringify({ changes, run, result }));
      }
    }
  }
});
