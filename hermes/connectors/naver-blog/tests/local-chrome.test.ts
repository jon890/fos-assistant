import { afterEach, expect, test } from "bun:test";
import { existsSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { CHROME_STARTUP_MS, localChrome } from "./local-chrome.ts";

const originalBinary = process.env.FOS_TEST_CHROME;
const directories: string[] = [];
afterEach(async () => {
  if (originalBinary === undefined) delete process.env.FOS_TEST_CHROME;
  else process.env.FOS_TEST_CHROME = originalBinary;
  while (directories.length)
    await rm(directories.pop()!, { recursive: true, force: true });
});

for (const scenario of ["exit", "http", "timeout"] as const) {
  test(`Chrome 시작 ${scenario} 실패는 정리 전 종료 상태와 제한된 stderr를 남긴다`, async () => {
    const directory = await mkdtemp(join(tmpdir(), "fos-chrome-startup-test-"));
    directories.push(directory);
    const executable = join(directory, "chrome");
    const ready = join(directory, "ready");
    const fixture = join(import.meta.dir, "chrome-startup.fixture.ts");
    await writeFile(
      executable,
      `#!/usr/bin/env bun\nimport { fakeChromeStartup } from ${JSON.stringify(fixture)};\nawait fakeChromeStartup(${JSON.stringify(scenario)});\nawait Bun.write(${JSON.stringify(ready)}, "ready");\n`,
      { mode: 0o700 },
    );
    process.env.FOS_TEST_CHROME = executable;
    // 대역이 진단을 쓴 뒤 가짜 시계를 상한까지 진행한다. 실제 시작 시간을 재지 않는다.
    const clock =
      scenario === "timeout"
        ? { now: () => (existsSync(ready) ? CHROME_STARTUP_MS : 0) }
        : {};
    const error = await localChrome(clock).catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(Error);
    const failure = error as Error;
    expect(failure.cause).toBeInstanceOf(Error);
    expect(failure.message).toStartWith(
      scenario === "exit"
        ? "합성 Chrome 시작 실패"
        : scenario === "timeout"
          ? "합성 Chrome 시작 시간 초과"
          : "NAVER_BLOG_BROWSER_UNREACHABLE",
    );
    const diagnostics = JSON.parse(
      failure.message.split("Chrome 시작 진단: ")[1]!,
    );
    expect(diagnostics.elapsed_ms).toBeGreaterThanOrEqual(0);
    if (scenario === "timeout")
      expect(diagnostics.elapsed_ms).toBe(CHROME_STARTUP_MS);
    expect(diagnostics.exit_code).toBe(scenario === "exit" ? 23 : null);
    expect(diagnostics.signal_code).toBeNull();
    if (scenario !== "http") expect(diagnostics.devtools_ready_ms).toBeNull();
    else {
      expect(diagnostics.devtools_ready_ms).toBeGreaterThanOrEqual(0);
      expect(diagnostics.devtools_ready_ms).toBeLessThanOrEqual(
        diagnostics.elapsed_ms,
      );
    }
    expect(diagnostics.stderr_tail).toContain("startup-final-marker");
    expect(diagnostics.stderr_tail).not.toContain("startup-0:");
    expect(diagnostics.stderr_tail.length).toBeLessThanOrEqual(8_192);
    expect(diagnostics.stderr_tail.split("\n").length).toBeLessThanOrEqual(20);
  }, 15_000);
}
