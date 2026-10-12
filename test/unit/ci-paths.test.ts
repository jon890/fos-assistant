import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
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

test("커넥터 README 는 존재 여부를 확인하는 hermes 검사만 돌린다", () => {
  assert.deepEqual(running(["hermes/connectors/naver-blog/README.md"]), ["hermes"]);
  assert.deepEqual(running([".github/CODEOWNERS"]), ["hermes"]);
});

test("모듈 코드는 그 모듈과 그것을 띄우는 job 을 돌린다", () => {
  assert.deepEqual(running(["backend/src/main/java/A.java"]), ["backend", "browser", "e2e", "quality"]);
  assert.deepEqual(running(["web/src/app/page.tsx"]), ["web", "browser", "e2e", "quality"]);
  assert.deepEqual(running(["hermes/connectors/gmail/server.py"]), ["backend", "e2e", "hermes", "quality"]);
  assert.deepEqual(running(["hermes/connectors/gmail/skills/gmail/SKILL.md"]), ["backend", "e2e", "hermes", "quality"]);
  assert.deepEqual(running(["hermes/docs/hermes-contract.md"]), ["hermes"]);
  assert.deepEqual(running(["test/web/a.test.ts"]), ["web"]);
  assert.deepEqual(running(["test/browser/chat.spec.ts"]), ["browser"]);
  assert.deepEqual(running(["test/e2e/run.ts"]), ["backend", "browser", "e2e", "hermes"]);
});

test("브라우저는 backend 테스트와 커넥터 catalog 만 바꾸면 건너뛴다", () => {
  assert.deepEqual(running(["backend/src/test/java/A.java"]), ["backend", "e2e", "hermes", "quality"]);
  assert.deepEqual(running(["test/resources/connector-argument-catalog.json"]), ["backend", "hermes"]);
  assert.deepEqual(running(["test/resources/connector-argument-masked-catalog.json"]), ["backend", "hermes"]);
});

test("브라우저 서버가 쓰는 테스트 지원 API 와 리소스는 브라우저 검사도 돌린다", () => {
  for (const file of [
    "backend/src/test/java/com/bifos/assistant/testsupport/NowTestSupportController.java",
    "backend/src/test/resources/pricing/models-dev-sample.json",
  ]) assert.deepEqual(running([file]), ["backend", "browser", "e2e", "hermes", "quality"], file);
});

test("커넥터 카드가 읽는 manifest 와 아이콘 변경도 브라우저 검사를 돌린다", () => {
  for (const file of ["hermes/connectors/gmail/connector.json", "hermes/connectors/naver-blog/icon.svg"]) {
    assert.deepEqual(running([file]), ["backend", "browser", "e2e", "hermes", "quality"], file);
  }
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
  assert.deepEqual(jobsFor("scripts/checks/new-check.sh"), ["backend", "web", "e2e", "hermes", "quality"]);
});

test("판정 규칙과 workflow 를 바꾸거나 규칙에 없는 파일이면 모두 돌린다", () => {
  for (const file of [
    ".github/workflows/ci.yml", "scripts/ci/ci-paths.mjs", ".github/actions/setup/action.yml",
    "test/support/pick-port.ts", "test/fixtures/a.json", "test/resources/a.json",
    "test/package.json", "package.json", "new-dir/a.ts", "new-input.md", "new-dir/runtime.md",
  ]) {
    assert.deepEqual(running([file]), JOBS, file);
  }
});

test("바뀐 파일이 하나도 없으면 판정을 믿지 않고 모두 돌린다", () => {
  assert.deepEqual(running([]), JOBS);
});

test("문서와 코드를 함께 바꾸면 코드 쪽 job 을 돌리고 처음 맞은 파일을 남긴다", () => {
  const result = classify(["docs/prd.md", "web/src/a.ts"]);
  assert.equal(result.web.because, "web/src/a.ts");
  assert.equal(result.backend.run, false);
  assert.equal(result.backend.because, null);
});

test("필수 검사는 돌려야 했으면 성공만, 건너뛰기로 했으면 생략만 통과한다", () => {
  const results = ["success", "failure", "cancelled", "skipped", "", undefined];
  for (const changes of results) {
    for (const run of ["true", "false", "", "invalid", undefined]) {
      for (const result of results) {
        const expected = changes === "success" && ((run === "true" && result === "success") || (run === "false" && result === "skipped"));
        assert.equal(gate({ changes, run, result }).pass, expected, JSON.stringify({ changes, run, result }));
      }
    }
  }
});

test("gate 명령은 직접 경로와 심볼릭 링크에서 환경값을 같은 종료 코드로 판정한다", () => {
  const dir = mkdtempSync(join(tmpdir(), "ci-path-filter-gate-"));
  const script = fileURLToPath(new URL("../../scripts/ci/ci-paths.mjs", import.meta.url));
  const link = join(dir, "gate.mjs");
  symlinkSync(script, link);
  const cases = [
    { changes: "success", run: "true", result: "success" },
    { changes: "success", run: "false", result: "skipped" },
    { changes: "success", run: "true", result: "failure" },
    { changes: "success", run: "true", result: "cancelled" },
    { changes: "success", run: "true", result: "skipped" },
    { changes: "success", run: "false", result: "success" },
    { changes: "failure", run: "false", result: "skipped" },
    { changes: "cancelled", run: "false", result: "skipped" },
    { changes: "success", run: "invalid", result: "skipped" },
    { changes: undefined, run: undefined, result: undefined },
  ];
  try {
    for (const entry of [script, link]) {
      for (const values of cases) {
        const verdict = gate(values);
        const actual = spawnSync(process.execPath, [entry, "gate"], {
          encoding: "utf8", env: {
            ...process.env, CHANGES_RESULT: values.changes, SHOULD_RUN: values.run, JOB_RESULT: values.result,
          },
        });
        assert.equal(actual.status, verdict.pass ? 0 : 1, `${entry}: ${JSON.stringify(values)}`);
        assert.equal(actual.stdout.trim(), verdict.reason);
      }
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("changes 명령은 PR 밖에서 전체를 고르고, PR 은 merge 부모 기준으로 삭제와 이름 변경을 본다", () => {
  const dir = mkdtempSync(join(tmpdir(), "ci-path-filter-"));
  const script = fileURLToPath(new URL("../../scripts/ci/ci-paths.mjs", import.meta.url));
  const output = join(dir, "output");
  const summary = join(dir, "summary");
  const run = (event: string) => {
    writeFileSync(output, "");
    writeFileSync(summary, "");
    return spawnSync(process.execPath, [script, "changes"], {
      cwd: dir, encoding: "utf8",
      env: { ...process.env, GITHUB_EVENT_NAME: event, GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: summary },
    });
  };
  const selected = () => Object.fromEntries(readFileSync(output, "utf8").trim().split("\n").map((line) => line.split("=")));
  const git = (...args: string[]) => execFileSync("git", args, { cwd: dir, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  const commit = (message: string) => {
    git("add", ".");
    git("-c", "user.name=CI test", "-c", "user.email=ci@example.invalid", "-c", "commit.gpgsign=false", "commit", "-m", message);
  };
  try {
    for (const event of ["push", "schedule", "workflow_dispatch"]) {
      assert.equal(run(event).status, 0, event);
      assert.deepEqual(selected(), Object.fromEntries(JOBS.map((job) => [job, "true"])));
    }
    // PR checkout 이 merge 커밋이 아니면 판정을 성공시키지 않는다.
    git("init", "-b", "main");
    writeFileSync(join(dir, ".gitignore"), "output\nsummary\n");
    mkdirSync(join(dir, "backend"));
    mkdirSync(join(dir, "docs"));
    writeFileSync(join(dir, "backend/A.java"), "synthetic\n");
    commit("base");
    const invalid = run("pull_request");
    assert.notEqual(invalid.status, 0);
    assert.equal(readFileSync(output, "utf8"), "");
    git("checkout", "-b", "pr");
    git("mv", "backend/A.java", "docs/prd.md");
    commit("rename");
    git("checkout", "main");
    writeFileSync(join(dir, "web-only-on-main.ts"), "synthetic\n");
    commit("main advances");
    git("-c", "user.name=CI test", "-c", "user.email=ci@example.invalid", "-c", "commit.gpgsign=false", "merge", "--no-ff", "pr", "-m", "merge");
    const valid = run("pull_request");
    assert.equal(valid.status, 0, valid.stderr);
    assert.deepEqual(selected(), {
      backend: "true", web: "false", browser: "true", e2e: "true", hermes: "false", quality: "true",
    });
    assert.match(readFileSync(summary, "utf8"), /backend\/A.java/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ci.yml 을 job 단위 본문으로 나눈다. 들여쓰기 두 칸의 `<id>:` 줄이 job 의 시작이다.
function workflowJobs(): Map<string, string> {
  const text = readFileSync(".github/workflows/ci.yml", "utf8");
  const body = text.slice(text.indexOf("\njobs:\n") + 7);
  const jobs = new Map<string, string>();
  const parts = body.split(/^ {2}([a-z0-9-]+):\n/m);
  for (let i = 1; i < parts.length; i += 2) jobs.set(parts[i], parts[i + 1]);
  return jobs;
}

// 무거운 검사 job 과 그 결과를 필수 검사 이름으로 판정하는 job 이다.
const GATED = [
  { flag: "backend", work: "backend-tests", gate: "backend" },
  { flag: "web", work: "web-build", gate: "web" },
  { flag: "browser", work: "browser", gate: "browser-required" },
  { flag: "e2e", work: "e2e-tests", gate: "e2e" },
  { flag: "hermes", work: "hermes-tests", gate: "hermes" },
];

test("무거운 job 은 자기 판정 값으로만 돌고, 필수 검사 job 은 같은 값과 그 job 의 결과로 판정한다", () => {
  const jobs = workflowJobs();
  for (const { flag, work, gate: gateJob } of GATED) {
    const workBody = jobs.get(work) ?? assert.fail(`${work} job 이 없다`);
    assert.match(workBody, /^ {4}needs: changes\n {4}if: needs\.changes\.outputs\.([a-z0-9]+) == 'true'\n/m, work);
    assert.equal(/needs\.changes\.outputs\.([a-z0-9]+) == 'true'/.exec(workBody)?.[1], flag, work);

    const gateBody = jobs.get(gateJob) ?? assert.fail(`${gateJob} job 이 없다`);
    assert.match(gateBody, new RegExp(`^ {4}needs: \\[changes, ${work}\\]\\n {4}if: always\\(\\)\\n`, "m"), gateJob);
    assert.match(gateBody, /CHANGES_RESULT: \$\{\{ needs\.changes\.result \}\}/, gateJob);
    assert.match(gateBody, new RegExp(`SHOULD_RUN: \\$\\{\\{ needs\\.changes\\.outputs\\.${flag} \\}\\}`), gateJob);
    assert.match(gateBody, new RegExp(`JOB_RESULT: \\$\\{\\{ needs\\.${work}\\.result \\}\\}`), gateJob);
    assert.match(gateBody, /run: node scripts\/ci\/ci-paths\.mjs gate/, gateJob);
  }
  for (const job of JOBS) assert.match(jobs.get("changes") ?? "", new RegExp(`${job}: \\$\\{\\{ steps\\.paths\\.outputs\\.${job} \\}\\}`), job);
});

test("quality 는 판정 job 이 실패해도 생략되지 않고, 무거운 단계만 판정 값으로 건너뛴다", () => {
  const body = workflowJobs().get("quality") ?? assert.fail("quality job 이 없다");
  assert.match(body, /^ {4}needs: changes\n {4}if: always\(\)\n/m);
  assert.match(body, /run: test "\$CHANGES_RESULT" = success/);
  assert.match(body, /SHOULD_RUN: \$\{\{ needs\.changes\.outputs\.quality \}\}/);
  assert.match(body, /test "\$SHOULD_RUN" = true \|\| test "\$SHOULD_RUN" = false/);
  const steps = body.split(/^ {6}- /m).slice(1);
  const always = steps.filter((step) => !step.includes("if: needs.changes.outputs.quality == 'true'"));
  // 언제나 도는 단계는 판정 확인, checkout, 파일 길이 검사뿐이다.
  assert.equal(always.length, 3, always.join("\n---\n"));
  assert.ok(always.some((step) => step.includes("check-file-length.mjs")));
});

test("unit 과 public-safe 는 경로 판정과 무관하게 항상 돈다", () => {
  for (const job of ["unit", "public-safe"]) {
    const body = workflowJobs().get(job) ?? assert.fail(`${job} job 이 없다`);
    assert.doesNotMatch(body, /^ {4}(needs|if):/m, job);
  }
});

test("브라우저 필수 검사는 shard 를 돌린 경우에만 폭별 실제 결과를 조회한다", () => {
  const body = workflowJobs().get("browser-required") ?? assert.fail("browser-required job 이 없다");
  assert.match(body, /matrix:\n {8}project: \[mobile, desktop\]/);
  assert.match(body, /if: needs.changes.outputs.browser == 'true'\n {8}env:/);
  assert.match(body, /run: node scripts\/ci\/browser-ci.mjs check \$\{\{ matrix.project \}\} 4/);
});

test("job 이 부르는 스크립트는 그 job 을 돌리는 규칙에 있다", () => {
  const jobs = workflowJobs();
  const owners = [...GATED.map(({ flag, work }) => [flag, work]), ["quality", "quality"]];
  for (const [flag, work] of owners) {
    for (const [file] of (jobs.get(work) ?? "").matchAll(/scripts\/[\w./-]+\.(?:mjs|sh)/g)) {
      assert.ok(jobsFor(file).includes(flag), `${work} 가 부르는 ${file} 이 ${flag} 를 돌리지 않는다`);
    }
  }
});
