import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

test("다른 사용자 요청의 선행 실패도 러너 catch와 finally에 전달된다", () => {
  const work = mkdtempSync(join(tmpdir(), "fos-limit-regression-"));
  try {
    const child = spawnSync(
      process.execPath,
      [
        fileURLToPath(
          new URL(
            "../support/user-execution-limit-failure.ts",
            import.meta.url,
          ),
        ),
        work,
      ],
      {
        encoding: "utf8",
        timeout: 5_000,
      },
    );
    assert.equal(child.error, undefined);
    assert.equal(child.signal, null);
    assert.equal(
      child.status,
      1,
      "요청 실패는 러너의 실패 종료 코드로 남아야 한다",
    );
    const events = child.stdout
      .split("\n")
      .filter((line) => line.startsWith("{"))
      .map((line) => JSON.parse(line));
    assert.deepEqual(events, [
      { status: "FAILED", caught: true, requests: 7 },
      { cleanup: "DONE", serverClosed: true },
    ]);
    assert.deepEqual(
      readdirSync(work),
      [],
      "finally가 러너의 임시 디렉터리를 지워야 한다",
    );
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
});
