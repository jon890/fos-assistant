import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const MIGRATION_DIR = join(import.meta.dirname, "../../backend/src/main/resources/db/migration");

/**
 * 이 번호까지의 파일은 검사하지 않는다. 이미 적용돼 고칠 수 없고, 그 표들의 정렬 규칙은 V58 부터 V65 까지가 맞췄다.
 * 이 값을 올리지 않는다. 새 파일은 모두 검사를 받는다.
 */
const LAST_UNCHECKED_VERSION = 57;

const REQUIRED = /DEFAULT\s+CHARSET\s*=\s*utf8mb4\s+COLLATE\s*=\s*utf8mb4_0900_ai_ci/i;

/** `CREATE TABLE` 부터 그 문장의 `;` 까지를 문장마다 돌려준다. 주석 줄은 뺀다. */
export function createTableStatements(sql: string): string[] {
  const body = sql
    .split("\n")
    .filter((line) => !line.trimStart().startsWith("--"))
    .join("\n");
  return body
    .split(";")
    .map((statement) => statement.trim())
    .filter((statement) => /^CREATE\s+TABLE\b/i.test(statement));
}

/** 문자 집합과 정렬 규칙을 적지 않은 `CREATE TABLE` 의 표 이름을 돌려준다. */
export function tablesWithoutCollation(sql: string): string[] {
  return createTableStatements(sql)
    .filter((statement) => !REQUIRED.test(statement))
    .map((statement) => /^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)/i.exec(statement)?.[1] ?? statement);
}

test("문자 집합과 정렬 규칙을 적은 CREATE TABLE 은 통과한다", () => {
  const sql = `
    -- 설명
    CREATE TABLE sample (
        id BIGINT NOT NULL PRIMARY KEY,
        name VARCHAR(64) NOT NULL
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
  `;
  assert.deepEqual(tablesWithoutCollation(sql), []);
});

test("정렬 규칙을 적지 않은 CREATE TABLE 은 표 이름으로 잡힌다", () => {
  const sql = `
    CREATE TABLE no_options (id BIGINT NOT NULL PRIMARY KEY);
    CREATE TABLE charset_only (id BIGINT NOT NULL PRIMARY KEY) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    CREATE INDEX ix_sample ON no_options (id);
  `;
  assert.deepEqual(tablesWithoutCollation(sql), ["no_options", "charset_only"]);
});

test(`V${LAST_UNCHECKED_VERSION} 뒤의 마이그레이션은 CREATE TABLE 에 문자 집합과 정렬 규칙을 적는다`, () => {
  const violations: string[] = [];
  for (const name of readdirSync(MIGRATION_DIR)) {
    const version = Number(/^V(\d+)__.*\.sql$/.exec(name)?.[1]);
    if (!(version > LAST_UNCHECKED_VERSION)) {
      continue;
    }
    for (const table of tablesWithoutCollation(readFileSync(join(MIGRATION_DIR, name), "utf8"))) {
      violations.push(`${name}: ${table}`);
    }
  }
  assert.deepEqual(
    violations,
    [],
    "CREATE TABLE 끝에 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 를 적는다. " +
      "까닭은 docs/backend/schema/README.md 의 「마이그레이션 작성 규칙」 에 있다.",
  );
});
