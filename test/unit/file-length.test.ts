import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { checkFileLengths, limitFor, lineCount } from "../../scripts/check-file-length.mjs";

function fixture(t: test.TestContext, files: Record<string, number> = {}, exclusions: Record<string, string> = {}) {
  const root = mkdtempSync(join(tmpdir(), "file-length-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const directory of ["backend/src/main", "web/src", "hermes", "scripts"]) {
    mkdirSync(join(root, directory), { recursive: true });
  }
  const baseline = join(root, "scripts/file-length-baseline.json");
  writeFileSync(baseline, JSON.stringify({ version: 1, exclusions, files }));
  return {
    root, baseline,
    put(file: string, lines: number) {
      mkdirSync(join(root, file, ".."), { recursive: true });
      writeFileSync(join(root, file), "// 줄\n".repeat(lines));
    },
    stored() { return JSON.parse(readFileSync(baseline, "utf8")).files; },
  };
}

test("전체 줄 수는 빈 줄과 주석을 포함하고 마지막 개행을 중복하지 않는다", () => {
  assert.equal(lineCount(""), 0);
  assert.equal(lineCount("// 주석\n\n코드\n"), 3);
  assert.equal(lineCount("// 주석\r\n\r\n코드"), 3);
  assert.equal(lineCount("\n"), 1);
});

test("언어별 범위와 시험 및 생성물 제외를 판정한다", () => {
  for (const file of ["backend/src/main/java/App.java", "backend/src/main/resources/db/migration/App.java"]) assert.equal(limitFor(file), 500);
  for (const file of ["web/src/page.tsx", "web/src/a.ts", "hermes/connectors/demo/src/server.ts", "scripts/tool.ts", "scripts/tool.mjs", "hermes/plugins/demo/__init__.py", "hermes/connectors/demo/scripts/tool.py"]) assert.equal(limitFor(file), 400);
  for (const file of ["backend/src/test/java/App.java", "test/helper.ts", "web/src/a.test.ts", "web/src/a.spec.tsx", "hermes/tests/helper.py", "hermes/plugins/test_demo.py", "hermes/plugins/demo_test.py", "hermes/connectors/demo/tests/helper.py", "hermes/plugins/demo/dist/a.py", "scripts/bundle/tool.mjs", "scripts/node_modules/tool/index.mjs", "hermes/connectors/demo/scripts/build.ts", "web/out/page.tsx"]) assert.equal(limitFor(file), null, file);
});

test("루트와 모듈의 docs 바로 아래 문서는 1000줄이고 ADR 과 그 밖의 Markdown 은 보지 않는다", () => {
  for (const file of ["docs/prd.md", "backend/docs/flow.md", "web/docs/prd.md", "hermes/docs/hermes-contract.md"]) assert.equal(limitFor(file), 1000, file);
  for (const file of ["docs/adr/ADR-001-x.md", "backend/docs/adr/INDEX.md", "hermes/README.md", "README.md", "docs/images/a.md"]) assert.equal(limitFor(file), null, file);
});

test("새 파일은 상한까지 통과하고 한 줄만 초과해도 실패한다", (t) => {
  const f = fixture(t);
  f.put("backend/src/main/App.java", 500);
  f.put("web/src/page.tsx", 400);
  f.put("hermes/plugins/demo.py", 400);
  assert.deepEqual(checkFileLengths(f.root).errors, []);
  f.put("backend/src/main/App.java", 501);
  f.put("web/src/page.tsx", 401);
  f.put("hermes/plugins/demo.py", 401);
  assert.equal(checkFileLengths(f.root).errors.length, 3);
});

test("기존 파일은 기준값까지 통과하고 늘어나면 실패한다", (t) => {
  const f = fixture(t, { "web/src/page.tsx": 600 });
  f.put("web/src/page.tsx", 600);
  assert.deepEqual(checkFileLengths(f.root).errors, []);
  f.put("web/src/page.tsx", 601);
  const before = readFileSync(f.baseline, "utf8");
  assert.equal(checkFileLengths(f.root, { update: true }).errors.length, 1);
  assert.equal(readFileSync(f.baseline, "utf8"), before);
});

test("줄어든 기준은 안내만 내며 갱신할 때만 낮춘다", (t) => {
  const f = fixture(t, { "web/src/page.tsx": 600 });
  f.put("web/src/page.tsx", 550);
  const result = checkFileLengths(f.root);
  assert.deepEqual(result.errors, []);
  assert.match(result.notices[0], /기준값을 낮추라/);
  assert.equal(f.stored()["web/src/page.tsx"], 600);
  checkFileLengths(f.root, { update: true });
  assert.equal(f.stored()["web/src/page.tsx"], 550);
  f.put("web/src/page.tsx", 400);
  checkFileLengths(f.root, { update: true });
  assert.deepEqual(f.stored(), {});
});

test("삭제된 기준은 안내만 내고 갱신으로 지운다", (t) => {
  const f = fixture(t, { "web/src/deleted.ts": 600 });
  assert.equal(checkFileLengths(f.root).notices.length, 1);
  assert.equal(f.stored()["web/src/deleted.ts"], 600);
  checkFileLengths(f.root, { update: true });
  assert.deepEqual(f.stored(), {});
});

test("새 위반이 있으면 기준을 더하거나 다른 기준을 부분 갱신하지 않는다", (t) => {
  const f = fixture(t, { "web/src/old.ts": 600 });
  f.put("web/src/old.ts", 450);
  f.put("scripts/new.mjs", 401);
  assert.equal(checkFileLengths(f.root, { update: true }).errors.length, 1);
  assert.deepEqual(f.stored(), { "web/src/old.ts": 600 });
});

test("까닭이 있는 데이터 표와 시험 및 생성물은 기준 목록에 들어가지 않는다", (t) => {
  const f = fixture(t, {}, { "hermes/connectors/demo/src/table.ts": "데이터 표" });
  for (const file of ["hermes/connectors/demo/src/table.ts", "web/src/page.test.ts", "hermes/tests/helper.py", "hermes/plugins/dist/bundle.py"]) f.put(file, 900);
  assert.deepEqual(checkFileLengths(f.root).errors, []);
  assert.equal(checkFileLengths(f.root).checked, 0);
});

test("잘못된 기준과 까닭 없는 제외 항목은 거절한다", (t) => {
  const f = fixture(t, { "web/src/page.tsx": 400 });
  assert.throws(() => checkFileLengths(f.root), /잘못된 기준/);
  writeFileSync(f.baseline, JSON.stringify({ version: 1, files: {}, exclusions: { "web/src/table.ts": " " } }));
  assert.throws(() => checkFileLengths(f.root), /까닭이 필요/);
});
