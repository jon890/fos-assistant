import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const MIGRATION_DIR = join(import.meta.dirname, "../../backend/src/main/resources/db/migration");

/**
 * 이미 운영에 적용된 SQL 마이그레이션의 SHA-256 이다.
 *
 * Flyway 는 SQL 파일의 체크섬을 주석까지 포함해 계산하고, 기동할 때 적용 기록과 다르면 검증에서 멈춘다.
 * 그래서 적용된 파일은 주석 한 글자도 고치지 않는다. 2026-10-02 용어 변경이 V28 의 주석을 바꿔
 * 배포 직전에 되돌린 적이 있다. CI 는 빈 DB 에 처음부터 적용하므로 이 변화를 잡지 못한다.
 *
 * 새 마이그레이션을 운영에 배포한 뒤 그 파일을 이 목록에 더한다. 더하지 않은 새 파일은 검사하지 않는다.
 */
const LOCKED: Record<string, string> = JSON.parse(
  readFileSync(join(import.meta.dirname, "migration-checksums.json"), "utf8"),
);

test("적용된 마이그레이션 파일은 내용이 바뀌지 않는다", () => {
  const changed: string[] = [];
  for (const [file, expected] of Object.entries(LOCKED)) {
    const path = join(MIGRATION_DIR, file);
    assert.ok(existsSync(path), `적용된 마이그레이션이 사라졌다: ${file}`);
    const actual = createHash("sha256").update(readFileSync(path)).digest("hex");
    if (actual !== expected) changed.push(file);
  }
  assert.deepEqual(changed, [], `적용된 마이그레이션이 바뀌었다. 되돌린다: ${changed.join(", ")}`);
});
