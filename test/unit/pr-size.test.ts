import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync, renameSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { classify, compare, countChanges, excluded } from "../../scripts/pr-size.mjs";

const SCRIPT = new URL("../../scripts/pr-size.mjs", import.meta.url).pathname;

test("추가와 삭제를 합치고 바이너리는 줄 수에 넣지 않는다", () => {
  assert.deepEqual(countChanges("200\t201\tsrc/a.ts\0-\t-\timage.png\0"), { lines: 401, ignored: 0 });
});

test("공백, 탭, 개행이 있는 경로와 이름 변경 기록을 읽는다", () => {
  assert.deepEqual(countChanges("2\t3\tsrc/a\t b\n.ts\0" + "4\t5\t\0src/old.ts\0src/new.ts\0"), { lines: 14, ignored: 0 });
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
  assert.deepEqual(countChanges(generated.map(file => `10\t20\t${file}\0`).join("") + "3\t4\tsrc/app.ts\0"), { lines: 7, ignored: generated.length * 30 });
  assert.deepEqual(countChanges("2\t3\t\0web/dist/app.js\0src/app.js\0"), { lines: 5, ignored: 0 });
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
    git("add", "."); git("commit", "-qm", "head");
    const head = git("rev-parse", "HEAD");
    git("checkout", "--detach", ancestor);
    writeFileSync(join(cwd, "base.ts"), "base\n".repeat(1500));
    git("add", "."); git("commit", "-qm", "base");
    assert.deepEqual(compare("HEAD", head, cwd), { lines: 2, ignored: 2000 });
    const result = spawnSync("node", [SCRIPT, "HEAD", head], { cwd, encoding: "utf8" });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /실제 변경: 2줄/);
    git("checkout", "--detach", head);
    writeFileSync(join(cwd, "large.ts"), "change\n".repeat(1001));
    git("add", "."); git("commit", "-qm", "large");
    assert.equal(spawnSync("node", [SCRIPT, head], { cwd }).status, 1);
    assert.equal(spawnSync("node", [SCRIPT, head], { cwd, env: { ...process.env, PR_LABELS: '["규모:예외"]' } }).status, 0);
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});
