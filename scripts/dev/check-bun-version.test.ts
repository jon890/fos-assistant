import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

const script = new URL("./check-bun-version.py", import.meta.url).pathname;

function run(workflow: string, localBun: string | null) {
  const directory = realpathSync(mkdtempSync(join(tmpdir(), "check-bun-version-")));
  const bin = join(directory, "bin");
  mkdirSync(bin);
  if (localBun !== null) {
    writeFileSync(join(bin, "bun"), `#!/bin/sh\necho ${localBun}\n`);
    chmodSync(join(bin, "bun"), 0o755);
  }
  const file = join(directory, "ci.yml");
  writeFileSync(file, workflow);
  const python = spawnSync("sh", ["-c", "command -v python3"], { encoding: "utf8" }).stdout.trim();
  try {
    return spawnSync(python, ["-I", script, file], {
      env: { PATH: `${bin}:/usr/bin:/bin` },
      encoding: "utf8",
    });
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

const pinned = "steps:\n  - with:\n      bun-version: '1.3.14'\n";

test("버전이 같으면 아무것도 내지 않는다", () => {
  const result = run(pinned, "1.3.14");
  assert.equal(result.status, 0);
  assert.equal(result.stdout, "");
});

test("버전이 다르면 두 값을 알리고 멈추지 않는다", () => {
  const result = run(pinned, "1.3.9");
  assert.equal(result.status, 0);
  assert.match(result.stdout, /^알림: .*1\.3\.9.*1\.3\.14/);
});

test("bun 이 없거나 값을 못 찾으면 건너뛴다", () => {
  assert.match(run(pinned, null).stdout, /^알림: 로컬 bun 버전을 구하지 못해/);
  assert.match(run("steps: []\n", "1.3.14").stdout, /^알림: .*bun-version 을 찾지 못해/);
});
