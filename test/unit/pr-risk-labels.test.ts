import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import test from "node:test";

const SCRIPT = new URL("../../scripts/pr/pr-risk-labels.sh", import.meta.url).pathname;

test("SQL과 Java 마이그레이션 및 COMMENT 리소스에는 마이그레이션 위험 라벨이 붙는다", () => {
  for (const path of [
    "backend/src/main/resources/db/migration/V1__fixture.sql",
    "backend/src/main/java/db/migration/V20261016052729__SchemaComments.java",
    "backend/src/main/resources/db/comments/V20261016052729.tsv",
  ]) {
    assert.deepEqual(labelsFor(path), ["위험:마이그레이션"]);
  }
  assert.deepEqual(labelsFor("backend/src/main/resources/db/other.tsv"), []);
});

// 바뀐 파일 경로를 표준 입력으로 주고 스크립트가 낸 라벨 목록을 돌려받는다.
function labelsFor(...paths: string[]): string[] {
  const out = execFileSync("bash", [SCRIPT], { input: paths.join("\n") + "\n", encoding: "utf8" });
  return out.split("\n").filter(Boolean);
}

test("hermes plugin 파일을 바꾸면 Hermes 연동과 보안 라벨이 붙는다", () => {
  const labels = labelsFor("hermes/plugins/fos-ctx/__init__.py");
  assert.ok(labels.includes("위험:Hermes연동"), `라벨: ${labels.join(",")}`);
  assert.ok(labels.includes("위험:보안"), `라벨: ${labels.join(",")}`);
});

test("hermes 의 plugin 밖 파일은 Hermes 연동 라벨만 붙는다", () => {
  assert.deepEqual(labelsFor("hermes/README.md"), ["위험:Hermes연동"]);
});

test("가짜 Hermes의 진입 파일과 분리 모듈은 Hermes 연동 라벨이 붙는다", () => {
  assert.deepEqual(labelsFor("test/e2e/fake-hermes.ts"), ["위험:Hermes연동"]);
  assert.deepEqual(labelsFor("test/e2e/fake-hermes/event-routes.ts"), ["위험:Hermes연동"]);
  assert.deepEqual(labelsFor("test/e2e/fake-hermes/state.ts"), ["위험:Hermes연동"]);
  assert.deepEqual(labelsFor("test/e2e/fake-hermes-other.ts"), []);
});

test("Hermes 문서에서 옮겨 온 절을 가진 기능 문서는 Hermes 연동 라벨이 붙는다", () => {
  assert.deepEqual(labelsFor("docs/features/mcp.md"), ["위험:Hermes연동"]);
});

test("Hermes 절이 없는 backend 문서는 Hermes 연동 라벨이 붙지 않는다", () => {
  assert.deepEqual(labelsFor("backend/docs/code-architecture.md"), []);
});

test("hermes 와 무관한 파일은 Hermes 연동과 보안 라벨이 붙지 않는다", () => {
  const labels = labelsFor("web/src/app/page.tsx");
  assert.ok(!labels.includes("위험:Hermes연동"), `라벨: ${labels.join(",")}`);
  assert.ok(!labels.includes("위험:보안"), `라벨: ${labels.join(",")}`);
});
