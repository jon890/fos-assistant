#!/usr/bin/env node
// Hermes Python 테스트를 모듈마다 별도 프로세스로 실행한다. 큰 모듈부터 시작한다.
import { spawn } from "node:child_process";
import { readdirSync, statSync } from "node:fs";
import { cpus } from "node:os";
import { basename, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../../", import.meta.url));
const tests = join(root, "hermes/tests");
const timeout = 300_000;
let jobs = Math.min(4, cpus().length || 1);
const selected: string[] = [];
for (let index = 2; index < process.argv.length; index++) {
  const arg = process.argv[index];
  if (arg === "--help" || arg === "-h") {
    console.log("사용법: node scripts/checks/run-hermes-python-tests.ts [-j 동시수] [모듈 파일 이름...]\n모듈별 별도 Python 프로세스로 실행한다. 기본 동시 수는 CPU 수와 4 중 작은 값이다. 모듈당 제한은 300초다.");
    process.exit(0);
  }
  if (arg === "-j" || arg === "--jobs") {
    const value = process.argv[++index];
    if (!value || !/^-?\d+$/.test(value)) {
      console.error("동시 수는 정수여야 한다");
      process.exit(2);
    }
    jobs = Math.max(1, Number(value));
  } else if (arg.startsWith("-")) {
    console.error(`알 수 없는 옵션: ${arg}`);
    process.exit(2);
  } else selected.push(basename(arg));
}
const found = readdirSync(tests).filter(name => /^test.*\.py$/.test(name)).sort();
const missing = [...new Set(selected)].filter(name => !found.includes(name)).sort();
if (missing.length) {
  console.error(`없는 모듈이다: ${missing.join(", ")}`);
  process.exit(1);
}
const targets = found.filter(name => !selected.length || selected.includes(name))
  .sort((a, b) => statSync(join(tests, b)).size - statSync(join(tests, a)).size);
if (!targets.length) {
  console.error("돌릴 모듈이 없다");
  process.exit(1);
}
const started = performance.now();
const failed: string[] = [];
const children = new Set<ReturnType<typeof spawn>>();
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    for (const child of children) child.kill(signal);
    process.exit(signal === "SIGINT" ? 130 : 143);
  });
}
async function run(name: string) {
  const start = performance.now();
  let output = "";
  let expired = false;
  const child = spawn("python3", ["-m", "unittest", "discover", "-s", tests, "-p", name], { cwd: root });
  children.add(child);
  // 두 스트림을 같은 문자열로 모아 실패한 모듈만 자세히 보인다.
  child.stdout!.setEncoding("utf8").on("data", chunk => { output += chunk; });
  child.stderr!.setEncoding("utf8").on("data", chunk => { output += chunk; });
  const timer = setTimeout(() => { expired = true; child.kill("SIGKILL"); }, timeout);
  const code = await new Promise<number>(resolve => {
    child.on("error", error => { output += error.message; resolve(1); });
    child.on("close", code => resolve(expired ? 124 : code ?? 1));
  });
  clearTimeout(timer);
  children.delete(child);
  const seconds = ((performance.now() - start) / 1000).toFixed(1);
  if (code === 0) console.log(`통과 ${name} (${seconds}s)`);
  else {
    failed.push(name);
    console.log(`실패 ${name} (종료 코드 ${code}, ${seconds}s)\n${expired ? "300초 안에 끝나지 않았다\n" : ""}${output}`);
  }
}
let next = 0;
await Promise.all(Array.from({ length: Math.min(jobs, targets.length) }, async () => {
  while (next < targets.length) await run(targets[next++]);
}));
console.log(`모듈 ${targets.length}개를 ${Math.min(jobs, targets.length)}개 프로세스로 ${((performance.now() - started) / 1000).toFixed(1)}초에 돌렸다. 실패 ${failed.length}개`);
if (failed.length) console.error(`실패한 모듈: ${failed.join(", ")}`);
process.exitCode = failed.length ? 1 : 0;
