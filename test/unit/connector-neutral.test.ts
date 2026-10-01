/**
 * Control Plane 과 웹의 운영 코드에 특정 서비스의 이름이 들어오지 않는지 본다.
 *
 * 커넥터는 manifest 가 정하므로 코드는 어느 서비스도 알면 안 된다. 금지 낱말은 이 파일 안에만 둔다.
 */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const ROOT = join(import.meta.dirname, "../..");

/** 이름을 지우기 전의 데이터 이관 기록과, 옛 경로를 새 경로로 넘기는 페이지만 예외다. */
const EXCLUDED = new Set([
  "backend/src/main/resources/db/migration/V36__accountbook_connection.sql",
  "backend/src/main/resources/db/migration/V38__connector_connection.sql",
  "web/src/app/connections/accountbook/page.tsx",
]);

const FORBIDDEN = ["accountbook", "ACCOUNTBOOK_", "fab_", "가계부"].map((word) => word.toLowerCase());

/** 금지 낱말이 든 파일의 경로를 돌려준다. 읽는 방법을 받아 파일 없이도 시험한다. */
export function findForbidden(files: readonly string[], read: (file: string) => string): string[] {
  return files.filter((file) => {
    if (EXCLUDED.has(file)) return false;
    const text = read(file).toLowerCase();
    return FORBIDDEN.some((word) => text.includes(word));
  });
}

test("운영 코드에 특정 서비스의 이름이 없다", () => {
  const listed = execFileSync("git", ["ls-files", "backend/src/main", "web/src"], { cwd: ROOT, encoding: "utf8" });
  const files = listed.split("\n").filter((line) => line.length > 0);
  assert.ok(files.length > 0, "검사할 파일 목록이 비었다");

  const offenders = findForbidden(files, (file) => readFileSync(join(ROOT, file), "utf8"));

  assert.deepEqual(offenders, [], `금지 낱말이 든 파일이 있다: ${offenders.join(", ")}`);
});

test("금지 낱말이 든 파일은 그 이름을 돌려준다", () => {
  const contents: Record<string, string> = {
    "backend/src/main/A.java": "class A { String k = \"ACCOUNTBOOK_URL\"; }",
    "web/src/b.tsx": "<p>가계부 연결</p>",
    "web/src/c.ts": "const prefix = 'FAB_';",
    "web/src/ok.ts": "const connector = 'generic';",
  };

  const offenders = findForbidden(Object.keys(contents), (file) => contents[file]!);

  assert.deepEqual(offenders, ["backend/src/main/A.java", "web/src/b.tsx", "web/src/c.ts"]);
});

test("예외 목록의 파일은 금지 낱말이 있어도 걸리지 않는다", () => {
  const offenders = findForbidden(
    ["web/src/app/connections/accountbook/page.tsx"],
    () => "accountbook 가계부",
  );

  assert.deepEqual(offenders, []);
});
