import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { isAbsolute, join } from "node:path";
import test from "node:test";

function runCheck(fail: boolean, failPublicSafe = false) {
  const directory = realpathSync(mkdtempSync(join(tmpdir(), "check-local-logs-")));
  const checks = join(directory, "scripts/checks");
  const dev = join(directory, "scripts/dev");
  const bin = join(directory, "bin");
  const logs = join(directory, "temporary logs");
  for (const folder of [checks, dev, bin, logs]) mkdirSync(folder, { recursive: true });
  copyFileSync(new URL("../../scripts/dev/check-local.sh", import.meta.url), join(dev, "check-local.sh"));
  const stdout = Array.from({ length: 65 }, (_, index) => `output-${index + 1}\n`).join("");
  const output = stdout + "stderr-output\n";
  writeFileSync(join(checks, "check-public-safe.sh"),
    `#!/bin/bash\necho synthetic-private-marker\nexit ${failPublicSafe ? 1 : 0}\n`, { mode: 0o755 });
  writeFileSync(join(checks, "quality.sh"),
    `#!/bin/bash\nprintf '%s' '${stdout}'\necho stderr-output >&2\nexit ${fail ? 1 : 0}\n`, { mode: 0o755 });
  for (const command of ["node", "git", "pnpm"]) {
    writeFileSync(join(bin, command), "#!/bin/bash\nexit 0\n", { mode: 0o755 });
  }
  const run = (args = ["--fast"]) => spawnSync("bash", [join(dev, "check-local.sh"), ...args], {
    cwd: directory,
    env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, TMPDIR: "temporary logs" },
    encoding: "utf8",
    timeout: 30_000,
  });
  return { directory, output, run, bin, logs };
}

test("사용법이나 Node 버전 오류에서는 로그 폴더를 만들지 않는다", () => {
  const fixture = runCheck(false);
  try {
    for (const args of [["--bogus"], ["--fast", "browser.spec.ts"]]) {
      assert.equal(fixture.run(args).status, 2);
      assert.deepEqual(readdirSync(fixture.logs), []);
    }
    writeFileSync(join(fixture.bin, "node"), "#!/bin/bash\nexit 1\n", { mode: 0o755 });
    assert.equal(fixture.run().status, 2);
    assert.deepEqual(readdirSync(fixture.logs), []);
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true });
  }
});

test("실패 단계의 마지막 줄에 절대 경로를 보이고 원본 전체를 실행별로 보존한다", () => {
  const fixture = runCheck(true);
  try {
    const paths: string[] = [];
    for (let attempt = 0; attempt < 2; attempt++) {
      const result = fixture.run();
      assert.equal(result.status, 1, result.stderr);
      const match = result.stderr.trimEnd().match(/원본 로그: (.+)$/);
      assert.ok(match, result.stderr);
      const log = match[1];
      assert.ok(isAbsolute(log));
      assert.ok(log.startsWith(join(fixture.directory, "temporary logs") + "/"));
      assert.equal(readFileSync(log, "utf8"), fixture.output);
      assert.ok(!existsSync(join(log, "../public-safe.log")));
      assert.ok(!result.stderr.includes("output-1\n"));
      assert.ok(result.stderr.includes("output-65\nstderr-output\n"));
      paths.push(log);
    }
    assert.notEqual(paths[0], paths[1]);
    assert.ok(paths.every((log) => existsSync(log)));
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true });
  }
});

test("공개 정보 검사가 실패하면 출력은 보이되 원본 로그를 남기지 않는다", () => {
  const fixture = runCheck(false, true);
  try {
    const result = fixture.run();
    assert.equal(result.status, 1, result.stderr);
    assert.ok(result.stderr.includes("synthetic-private-marker"));
    assert.ok(result.stderr.includes("공개 정보 검사 로그는 비공개 값이 남지 않도록 보존하지 않는다."));
    assert.ok(!result.stderr.includes("원본 로그:"));
    assert.deepEqual(readdirSync(fixture.logs), []);
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true });
  }
});

test("모든 단계가 성공하면 실행 로그 폴더를 지운다", () => {
  const fixture = runCheck(false);
  try {
    const result = fixture.run();
    assert.equal(result.status, 0, result.stderr);
    const match = result.stdout.match(/^임시 로그\(.*\): (.+)$/m);
    assert.ok(match, result.stdout);
    assert.ok(!existsSync(match[1]));
    assert.ok(result.stdout.includes("빠른 검사를 모두 통과했다."));
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true });
  }
});
