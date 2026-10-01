import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { accessSync, constants } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

const ROOT = join(import.meta.dirname, "../..");
const QUALITY_SH = join(ROOT, "scripts/quality.sh");
const QUALITY_REPORT = join(ROOT, "scripts/quality-report.mjs");

test("quality.sh 는 인자 없이 부르면 2 로 끝나고 사용법을 표준 오류에 낸다", () => {
  const result = spawnSync("bash", [QUALITY_SH], { encoding: "utf8" });

  assert.equal(result.status, 2);
  assert.match(result.stderr, /check/);
  assert.match(result.stderr, /fix/);
  assert.equal(result.stdout, "");
});

test("quality.sh 는 모르는 인자를 받으면 2 로 끝나고 사용법을 낸다", () => {
  const result = spawnSync("bash", [QUALITY_SH, "lint"], { encoding: "utf8" });

  assert.equal(result.status, 2);
  assert.match(result.stderr, /check/);
  assert.match(result.stderr, /fix/);
});

test("quality.sh 는 실행 권한이 있고 quality-report.mjs 는 Node 로 부를 수 있다", () => {
  accessSync(QUALITY_SH, constants.X_OK);

  const result = spawnSync("node", [QUALITY_REPORT, "--root", ROOT], {
    encoding: "utf8",
    env: { ...process.env, QUALITY_CHECKSTYLE_REPORTS: "/nonexistent/main.xml" },
  });
  assert.equal(result.status, 0);
});

test("quality-report.mjs 는 경고만 경고 목록에 내고 eslint error 는 실패한 위반 절에 낸다", async () => {
  const dir = await mkdtemp(join(tmpdir(), "quality-report-"));
  try {
    const checkstyle = join(dir, "main.xml");
    await writeFile(
      checkstyle,
      `<?xml version="1.0" encoding="UTF-8"?>
<checkstyle version="14.3.0">
<file name="${dir}/A.java">
<error line="1" severity="warning" message="File length is 567 lines (max allowed is 500)." source="com.puppycrawl.tools.checkstyle.checks.sizes.FileLengthCheck"/>
<error line="7" severity="error" message="Checkstyle 실패 항목 &lt;X&gt;" source="com.puppycrawl.tools.checkstyle.checks.imports.UnusedImportsCheck"/>
</file>
</checkstyle>
`,
    );
    const eslint = join(dir, "eslint.json");
    await writeFile(
      eslint,
      JSON.stringify([
        {
          filePath: `${dir}/b.ts`,
          messages: [
            { ruleId: "max-lines", severity: 1, line: 1, message: "파일이 400줄을 넘는다" },
            { ruleId: "no-restricted-globals", severity: 2, line: 9, message: "fetch 금지" },
          ],
        },
      ]),
    );

    const result = spawnSync(
      "node",
      [QUALITY_REPORT, "--root", dir, "--checkstyle", checkstyle, "--eslint", eslint],
      { encoding: "utf8" },
    );

    assert.equal(result.status, 0);
    const [failed, warnings] = result.stdout.split("경고 목록(실패 아님)");
    assert.match(failed, /실패한 위반\(eslint\)/);
    assert.match(failed, /\[eslint no-restricted-globals\] 1건/);
    assert.match(failed, /b\.ts:9 fetch 금지/);
    assert.match(warnings, /\[Checkstyle FileLength\] 1건/);
    assert.match(warnings, /A\.java:1 File length is 567 lines/);
    assert.match(warnings, /\[eslint max-lines\] 1건/);
    assert.match(warnings, /b\.ts:1 파일이 400줄을 넘는다/);
    assert.doesNotMatch(warnings, /Checkstyle 실패 항목/);
    assert.doesNotMatch(warnings, /fetch 금지/);
    assert.doesNotMatch(result.stdout, /UnusedImports/);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("quality-report.mjs 는 입력 보고서가 없으면 보고서 없음으로 알리고 0 으로 끝난다", () => {
  const result = spawnSync(
    "node",
    [
      QUALITY_REPORT,
      "--checkstyle",
      "/nonexistent/main.xml",
      "--eslint",
      "/nonexistent/eslint.json",
    ],
    { encoding: "utf8" },
  );

  assert.equal(result.status, 0);
  assert.match(result.stdout, /Checkstyle: 보고서 없음/);
  assert.match(result.stdout, /eslint: 보고서 없음/);
  assert.doesNotMatch(result.stdout, /실패한 위반/);
});
