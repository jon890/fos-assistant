import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";

const script = resolve(import.meta.dirname, "../../scripts/check-migration-versions.mjs");
const directory = "backend/src/main/resources/db/migration";

function check(added: string[], existing = ["V80__old.sql"]) {
  const root = mkdtempSync(join(tmpdir(), "migration-versions-"));
  try {
    const migrations = join(root, directory);
    mkdirSync(migrations, { recursive: true });
    for (const file of existing) writeFileSync(join(migrations, file), "SELECT 1;\n");
    const git = (...args: string[]) => execFileSync("git", args, { cwd: root, stdio: "pipe" });
    git("init", "--quiet");
    git("add", ".");
    git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--quiet", "-m", "base");
    for (const file of added) writeFileSync(join(migrations, file), "SELECT 1;\n");
    return spawnSync(process.execPath, [script, "HEAD"], { cwd: root, encoding: "utf8" });
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test("기존 숫자 버전과 이미 머지된 시각 버전은 그대로 허용한다", () => {
  assert.equal(check([], ["V80__old.sql", "V82__merged.sql", "V20990101000000__merged.sql"]).status, 0);
});

test("새 UTC 시각 버전과 뒤늦게 도착한 낮은 시각 버전을 허용한다", () => {
  assert.equal(check(["V20261001000000__late.sql"], ["V80__old.sql", "V20261002000000__first.sql"]).status, 0);
});

test("새 숫자 버전과 시각 형식 밖의 SQL 을 거절한다", () => {
  for (const file of ["V83__new.sql", "V20261001__short.sql", "R__repeat.sql", "wrong.sql", "V20261001000000__.sql"]) {
    const result = check([file]);
    assert.equal(result.status, 1, file);
    assert.match(result.stderr, /형식/);
  }
});

test("같은 시각 버전과 기존 버전의 Flyway 표기 중복을 거절한다", () => {
  for (const files of [["V20261001000000__a.sql", "V20261001000000__b.sql"], ["V080__copy.sql"], ["V80_0__copy.sql"]]) {
    const result = check(files);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /버전 중복/);
  }
});

test("존재하지 않는 날짜와 범위 밖 시각을 거절한다", () => {
  for (const stamp of ["20260230000000", "20261301000000", "20261001240000", "20261001006000", "20261001000060"]) {
    const result = check([`V${stamp}__invalid.sql`]);
    assert.equal(result.status, 1, stamp);
    assert.match(result.stderr, /올바른 UTC/);
  }
});

test("지금보다 1일 넘게 미래인 시각을 거절한다", () => {
  const stamp = new Date(Date.now() + 2 * 24 * 60 * 60 * 1000).toISOString().replace(/\D/g, "").slice(0, 14);
  const result = check([`V${stamp}__future.sql`]);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /1일 넘게 미래/);
});

test("1일 안의 시각은 허용하고 기준 ref 가 없으면 실패한다", () => {
  const stamp = new Date(Date.now() + 23 * 60 * 60 * 1000).toISOString().replace(/\D/g, "").slice(0, 14);
  assert.equal(check([`V${stamp}__near.sql`]).status, 0);
  const result = spawnSync(process.execPath, [script, "refs/heads/missing-numbering-test"], { encoding: "utf8" });
  assert.equal(result.status, 1);
});

test("기존 불변 검사도 시각 버전의 체크섬 변경을 잡는다", () => {
  const root = mkdtempSync(join(tmpdir(), "migration-checksums-"));
  try {
    mkdirSync(join(root, directory), { recursive: true });
    mkdirSync(join(root, "test/unit"), { recursive: true });
    const file = "V20261001000000__locked.sql";
    const sql = "SELECT 1;\n";
    writeFileSync(join(root, directory, file), sql);
    writeFileSync(join(root, "test/unit/migration-checksums.json"), JSON.stringify({
      [file]: createHash("sha256").update(sql).digest("hex"),
    }));
    const immutable = join(root, "test/unit/migration-immutable.test.ts");
    writeFileSync(immutable, readFileSync(join(import.meta.dirname, "migration-immutable.test.ts")));
    // 부모의 테스트 IPC 를 물려받지 않고 별도 검사 프로세스의 종료 코드를 읽는다.
    const env = { ...process.env };
    delete env.NODE_TEST_CONTEXT;
    const run = () => spawnSync(process.execPath, ["--test", immutable], { encoding: "utf8", env });
    assert.equal(run().status, 0);
    writeFileSync(join(root, directory, file), `${sql}-- 바뀐 주석\n`);
    const result = run();
    assert.equal(result.status, 1);
    assert.match(result.stdout, /적용된 마이그레이션이 바뀌었다/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
