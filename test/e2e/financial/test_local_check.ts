// 로컬 검사 실행 순서와 purge 변경 감지를 임시 checkout에서 확인한다.
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, unlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repo = fileURLToPath(new URL("../../../", import.meta.url));
const inputs = ["backend/src/main/resources/db/migration/probe.sql", "backend/src/main/java/db/migration/Probe.java", "backend/src/test/resources/purge/tables.tsv"];
function fixture() {
  const root = mkdtempSync(join(tmpdir(), "fos-local-check-"));
  const log = join(root, "commands.log");
  function put(name: string, content: string, executable = false) {
    const target = join(root, name);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, content);
    if (executable) chmodSync(target, 0o755);
  }
  put("scripts/dev/check-local.sh", readFileSync(join(repo, "scripts/dev/check-local.sh"), "utf8"));
  for (const name of inputs) put(name, "baseline");
  for (const name of ["check-public-safe", "quality", "check-mysql-migration", "check-connectors"]) put(`scripts/checks/${name}.sh`, "#!/bin/bash\nexit 0\n", true);
  function git(...args: string[]) {
    const result = spawnSync("git", ["-C", root, ...args], { encoding: "utf8" });
    assert.equal(result.status, 0, result.stderr);
  }
  git("init", "-b", "main");
  git("config", "user.email", "test@example.test");
  git("config", "user.name", "검사");
  git("add", "."); git("commit", "-m", "baseline");
  git("update-ref", "refs/remotes/origin/main", "HEAD");
  git("checkout", "-b", "topic");
  const stub = `#!${process.execPath}\nconst fs=require('node:fs'),p=require('node:path');\nfs.appendFileSync(process.env.COMMAND_LOG,p.basename(process.argv[1])+' '+process.argv.slice(2).join(' ')+'\\n');\nif(p.basename(process.argv[1])==='node'&&process.argv.includes('-p'))console.log('24.0.0');\n`;
  for (const name of ["node", "pnpm", "bun", "python3", "gradlew"]) put(name === "gradlew" ? "backend/gradlew" : `bin/${name}`, stub, true);
  function check(...args: string[]) {
    rmSync(log, { force: true });
    const result = spawnSync("bash", [join(root, "scripts/dev/check-local.sh"), ...args], {
      encoding: "utf8", env: { ...process.env, PATH: `${join(root, "bin")}:${process.env.PATH}`, COMMAND_LOG: log },
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    return { output: result.stdout, commands: readFileSync(log, "utf8").trim().split("\n") };
  }
  return { root, git, check, close: () => rmSync(root, { recursive: true, force: true }) };
}

test("빠른 검사는 변경이 없으면 purge와 커넥터 설치를 건너뛴다", () => {
  const f = fixture();
  try {
    const { output, commands } = f.check("--fast");
    assert.ok(output.includes("purge-classification: 변경이 없어 건너뛴다."));
    assert.ok(!commands.some(command => /^(gradlew test|bun install)/.test(command)));
  } finally { f.close(); }
});
test("커밋·stage·수정·새 파일·삭제된 purge 입력은 대상 테스트만 돌린다", () => {
  const f = fixture();
  try {
    for (const name of inputs) for (const state of ["committed", "staged", "unstaged", "new", "deleted"]) {
      f.git("reset", "--hard", "origin/main");
      const target = join(f.root, state === "new" ? join(dirname(name), `new-${name.split("/").at(-1)}`) : name);
      if (state === "deleted") unlinkSync(target); else writeFileSync(target, "changed");
      if (["committed", "staged"].includes(state)) f.git("add", name);
      if (state === "committed") f.git("commit", "-m", "change");
      const { output, commands } = f.check("--fast");
      assert.ok(output.includes("purge-classification"));
      assert.deepEqual(commands.filter(command => command.startsWith("gradlew test")), ["gradlew test --tests *PurgeClassificationTest"]);
      assert.ok(!commands.some(command => command.startsWith("bun install")));
      if (state === "new") unlinkSync(target);
    }
  } finally { f.close(); }
});
test("비교 기준이 없으면 purge 검사를 보수적으로 돌린다", () => {
  const f = fixture();
  try {
    f.git("update-ref", "-d", "refs/remotes/origin/main");
    const { output, commands } = f.check("--fast");
    assert.ok(output.includes("비교 기준을 구할 수 없어"));
    assert.ok(commands.includes("gradlew test --tests *PurgeClassificationTest"));
  } finally { f.close(); }
});
test("전체 검사는 backend와 E2E 전에 커넥터를 설치한다", () => {
  const f = fixture();
  try {
    const { commands } = f.check("--skip-browser");
    const install = commands.indexOf(`bun install --frozen-lockfile --cwd ${join(f.root, "hermes/connectors/tossinvest")}`);
    assert.ok(install >= 0 && install < commands.indexOf("gradlew test"));
    assert.ok(install < commands.findIndex(command => command.includes("test/e2e/run.ts")));
  } finally { f.close(); }
});
