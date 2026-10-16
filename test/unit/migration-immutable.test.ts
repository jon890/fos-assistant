import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, unlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

const DB_DIR = join(import.meta.dirname, "../../backend/src/main/resources/db");

/**
 * 이미 운영에 적용된 SQL 마이그레이션과 COMMENT 리소스의 SHA-256 이다.
 *
 * Flyway 는 SQL 파일의 체크섬을 주석까지 포함해 계산하고, 기동할 때 적용 기록과 다르면 검증에서 멈춘다.
 * 그래서 적용된 파일은 주석 한 글자도 고치지 않는다. 2026-10-02 용어 변경이 V28 의 주석을 바꿔
 * 배포 직전에 되돌린 적이 있다. CI 는 빈 DB 에 처음부터 적용하므로 이 변화를 잡지 못한다.
 *
 * 새 마이그레이션을 운영에 배포한 뒤 그 파일을 이 목록에 더한다. 더하지 않은 새 파일은 검사하지 않는다.
 * SQL 파일은 기존 파일명을 쓰고, COMMENT 리소스는 comments/파일명.tsv를 키로 쓴다.
 */
const LOCKED: Record<string, string> = JSON.parse(
  readFileSync(join(import.meta.dirname, "migration-checksums.json"), "utf8"),
);

function assertLockedFiles(locked: Record<string, string>, directory = DB_DIR): void {
  const changed: string[] = [];
  for (const [file, expected] of Object.entries(locked)) {
    const path = file.startsWith("comments/") ? join(directory, file) : join(directory, "migration", file);
    assert.ok(existsSync(path), `적용된 마이그레이션이 사라졌다: ${file}`);
    const actual = createHash("sha256").update(readFileSync(path)).digest("hex");
    if (actual !== expected) changed.push(file);
  }
  assert.deepEqual(changed, [], `적용된 마이그레이션이 바뀌었다. 되돌린다: ${changed.join(", ")}`);
}

test("적용된 마이그레이션 파일은 내용이 바뀌지 않는다", () => {
  assertLockedFiles(LOCKED);
});

test("잠근 COMMENT 리소스의 수정과 삭제도 기존 SQL 파일과 함께 검사한다", () => {
  const directory = mkdtempSync(join(tmpdir(), "schema-comments-immutable-"));
  const resource = "comments/V20261016052729.tsv";
  try {
    mkdirSync(join(directory, "migration"));
    mkdirSync(join(directory, "comments"));
    const sql = "CREATE TABLE fixture (id BIGINT);";
    const tsv = "fixture\t*\t검사 테이블\n";
    writeFileSync(join(directory, "migration/V1__fixture.sql"), sql);
    writeFileSync(join(directory, resource), tsv);
    const locked = {
      "V1__fixture.sql": createHash("sha256").update(sql).digest("hex"),
      [resource]: createHash("sha256").update(tsv).digest("hex"),
    };
    assertLockedFiles(locked, directory);
    writeFileSync(join(directory, resource), tsv.replace("검사", "바꾼"));
    assert.throws(() => assertLockedFiles(locked, directory), /마이그레이션이 바뀌었다.*comments\/V20261016052729.tsv/);
    unlinkSync(join(directory, resource));
    assert.throws(() => assertLockedFiles(locked, directory), /마이그레이션이 사라졌다.*comments\/V20261016052729.tsv/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
