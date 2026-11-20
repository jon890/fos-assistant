import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";
import { findCandidates } from "./migration-test-prune.ts";

const script = resolve(import.meta.dirname, "./migration-test-prune.ts");

function withTests(files: Record<string, string>, body: (root: string) => void) {
  const root = mkdtempSync(join(tmpdir(), "migration-test-prune-"));
  try {
    for (const [name, text] of Object.entries(files)) {
      const file = join(root, "backend/src/test/java/com/example", name);
      mkdirSync(join(file, ".."), { recursive: true });
      writeFileSync(file, text);
    }
    body(root);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

const flywayTest = (name: string, ...versions: string[]) =>
  `package com.example;\nimport org.flywaydb.core.Flyway;\nclass ${name} {\n${versions.map(v => `  String v = "${v}";`).join("\n")}\n}\n`;

test("가장 늦은 버전의 날짜가 14일보다 오래된 Flyway 테스트와 그 하위 클래스를 후보로 뽑는다", () => {
  withTests({
    "OldMigrationTest.java": flywayTest("OldMigrationTest", "20261001000000", "20261101120000"),
    "OldMysqlMigrationTest.java": "package com.example;\nclass OldMysqlMigrationTest extends OldMigrationTest {}\n",
    "NewMigrationTest.java": flywayTest("NewMigrationTest", "20261110000000"),
  }, root => {
    const found = findCandidates(root, new Date("2026-11-20T00:00:00Z"));
    assert.deepEqual(found.map(c => [c.className, c.version, c.ageDays]), [
      ["OldMigrationTest", "V20261101120000", 19],
      ["OldMysqlMigrationTest", "V20261101120000", 19],
    ]);
  });
});

test("14일째인 날은 아직 후보가 아니고 15일째부터 후보다", () => {
  withTests({ "EdgeMigrationTest.java": flywayTest("EdgeMigrationTest", "V20261106235959") }, root => {
    assert.equal(findCandidates(root, new Date("2026-11-20T23:00:00Z")).length, 0);
    assert.equal(findCandidates(root, new Date("2026-11-21T00:00:00Z")).length, 1);
  });
});

test("지우지 않는 표시가 있으면 그 클래스와 하위 클래스를 빼고, Flyway 를 쓰지 않거나 버전이 없으면 후보가 아니다", () => {
  withTests({
    "KeptMigrationTest.java": `// migration-test-prune: keep 지금 스키마를 본다\n${flywayTest("KeptMigrationTest", "20260101000000")}`,
    "KeptMysqlMigrationTest.java": "package com.example;\nclass KeptMysqlMigrationTest extends KeptMigrationTest {}\n",
    "PlainTest.java": `package com.example;\nclass PlainTest {\n  String v = "20260101000000";\n}\n`,
    "LatestMigrationTest.java": `package com.example;\nimport org.flywaydb.core.Flyway;\nclass LatestMigrationTest { String v = "68"; }\n`,
  }, root => {
    assert.deepEqual(findCandidates(root, new Date("2026-11-20T00:00:00Z")), []);
  });
});

test("명령은 후보가 있어도 0 으로 끝나고 잘못된 날짜는 2 로 끝난다", () => {
  const ok = spawnSync(process.execPath, [script, "--today", "2026-11-20", "--github"], { encoding: "utf8" });
  assert.equal(ok.status, 0, ok.stderr);
  const bad = spawnSync(process.execPath, [script, "--today", "2026-13-40"], { encoding: "utf8" });
  assert.equal(bad.status, 2);
});
