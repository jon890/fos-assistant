import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import test from "node:test";

const SCRIPT = new URL("../../scripts/pr-risk-labels.sh", import.meta.url).pathname;

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

test("hermes 와 무관한 파일은 Hermes 연동과 보안 라벨이 붙지 않는다", () => {
  const labels = labelsFor("web/src/app/page.tsx");
  assert.ok(!labels.includes("위험:Hermes연동"), `라벨: ${labels.join(",")}`);
  assert.ok(!labels.includes("위험:보안"), `라벨: ${labels.join(",")}`);
});
