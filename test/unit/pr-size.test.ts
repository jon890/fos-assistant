import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync, renameSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { category, classify, compare, countChanges, excluded } from "../../scripts/pr-size.mjs";

const SCRIPT = new URL("../../scripts/pr-size.mjs", import.meta.url).pathname;
// 시험 자식이 CI 작업의 출력과 요약을 바꾸거나 예외 라벨을 물려받지 않게 한다.
const CLI_ENV = { ...process.env };
for (const key of ["GITHUB_OUTPUT", "GITHUB_STEP_SUMMARY", "PR_LABELS"]) delete CLI_ENV[key];

test("추가와 삭제를 합치고 바이너리는 줄 수에 넣지 않는다", () => {
  assert.deepEqual(countChanges("200\t201\tsrc/a.ts\0-\t-\timage.png\0"), { production: 401, tests: 0, docs: 0, ignored: 0 });
});

test("공백, 탭, 개행이 있는 경로와 이름 변경 기록을 읽는다", () => {
  assert.deepEqual(countChanges("2\t3\tsrc/a\t b\n.ts\0" + "4\t5\t\0src/old.ts\0src/new.ts\0"), { production: 14, tests: 0, docs: 0, ignored: 0 });
});

test("생성 파일만 제외하고 소스와 직접 관리하는 설정은 센다", () => {
  const generated = [
    "web/pnpm-lock.yaml", "hermes/connectors/example/bun.lock", "bun.lockb", "package-lock.json",
    "web/dist/app.js", "web/.next/server/app.js", "web/build/report.json", "assets/app.bundle.js",
    "test/__snapshots__/a.snap", "test/browser/page.spec.ts-snapshots/page.png",
    "backend/config/archunit/store/stored.rules", "backend/config/checkstyle/baseline.xml",
    "web/eslint-suppressions.json", "test/unit/migration-checksums.json",
  ];
  for (const file of generated) assert.equal(excluded(file), true, file);
  for (const file of ["src/lock.ts", "backend/config/checkstyle/suppressions.xml", "web/eslint.config.mjs", "src/build.ts", "test/unit/pr-size.test.ts"]) {
    assert.equal(excluded(file), false, file);
  }
  assert.deepEqual(countChanges(generated.map(file => `10\t20\t${file}\0`).join("") + "3\t4\tsrc/app.ts\0"), { production: 7, tests: 0, docs: 0, ignored: generated.length * 30 });
  assert.deepEqual(countChanges("2\t3\t\0web/dist/app.js\0src/app.js\0"), { production: 5, tests: 0, docs: 0, ignored: 0 });
});

test("시험과 문서는 별도로 표시하고 운영 코드만 상한에 넣는다", () => {
  const tests = ["backend/src/test/java/A.java", "web/src/a.test.ts", "web/src/a.spec.tsx", "hermes/tests/a.py", "hermes/connectors/sample/tests/a.ts", "test/e2e/a.ts", "e2e/a.ts"];
  const docs = ["docs/adr/INDEX.md", "AGENTS.md", "hermes/README.md", "docs/contract.json"];
  const production = ["backend/src/main/java/A.java", "web/src/app.ts", "hermes/plugins/a.py", "scripts/check.sh", ".github/workflows/ci.yml", "backend/build.gradle.kts"];
  for (const file of tests) assert.equal(category(file), "tests", file);
  for (const file of docs) assert.equal(category(file), "docs", file);
  for (const file of production) assert.equal(category(file), "production", file);
  const counts = countChanges("400\t0\tweb/src/app.ts\0" + "6000\t0\ttest/unit/a.test.ts\0" + "7000\t0\tdocs/prd.md\0");
  assert.deepEqual(counts, { production: 400, tests: 6000, docs: 7000, ignored: 0 });
  assert.deepEqual(classify(counts.production), { large: false, pass: true });
  assert.deepEqual(countChanges("1\t2\t\0scripts/check.sh\0test/check.sh\0"), { production: 3, tests: 0, docs: 0, ignored: 0 });
});

test("경계와 예외 라벨을 판정한다", () => {
  for (const [lines, large, pass] of [[0, false, true], [400, false, true], [401, true, true], [1000, true, true], [1001, true, false]] as const) {
    assert.deepEqual(classify(lines), { large, pass });
  }
  assert.deepEqual(classify(1001, ["규모:예외"]), { large: true, pass: true });
  assert.equal(classify(1001, ["규모:큼"]).pass, false);
});

test("잘못된 numstat는 조용히 통과시키지 않는다", () => {
  assert.throws(() => countChanges("invalid\0"));
  assert.throws(() => countChanges("1\t2\t\0old.ts\0"));
});

test("공통 조상부터 세므로 base의 새 변경은 포함하지 않고 이동은 중복 계산하지 않는다", () => {
  const cwd = mkdtempSync(join(tmpdir(), "pr-size-"));
  const git = (...args: string[]) => execFileSync("git", args, { cwd, encoding: "utf8" }).trim();
  try {
    git("init", "-q");
    git("config", "user.name", "Test");
    git("config", "user.email", "test@example.com");
    writeFileSync(join(cwd, "old.ts"), "a\nb\nc\n");
    git("add", "."); git("commit", "-qm", "initial");
    const ancestor = git("rev-parse", "HEAD");
    renameSync(join(cwd, "old.ts"), join(cwd, "new.ts"));
    writeFileSync(join(cwd, "app.ts"), "one\ntwo\n");
    writeFileSync(join(cwd, "pnpm-lock.yaml"), "generated\n".repeat(2000));
    writeFileSync(join(cwd, "a.test.ts"), "test\n".repeat(2000));
    writeFileSync(join(cwd, "README.md"), "document\n".repeat(2000));
    git("add", "."); git("commit", "-qm", "head");
    const head = git("rev-parse", "HEAD");
    git("checkout", "--detach", ancestor);
    writeFileSync(join(cwd, "base.ts"), "base\n".repeat(1500));
    git("add", "."); git("commit", "-qm", "base");
    assert.deepEqual(compare("HEAD", head, cwd), { production: 2, tests: 2000, docs: 2000, ignored: 2000 });
    const result = spawnSync("node", [SCRIPT, "HEAD", head], { cwd, encoding: "utf8", env: CLI_ENV });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /운영 코드 2줄, 시험 2000줄, 문서 2000줄/);
    git("checkout", "--detach", head);
    writeFileSync(join(cwd, "large.ts"), "change\n".repeat(1001));
    git("add", "."); git("commit", "-qm", "large");
    assert.equal(spawnSync("node", [SCRIPT, head], { cwd, env: CLI_ENV }).status, 1);
    assert.equal(spawnSync("node", [SCRIPT, head], { cwd, env: { ...CLI_ENV, PR_LABELS: '["규모:예외"]' } }).status, 0);
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});
