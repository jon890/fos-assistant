// 테스트 실행기가 실패를 숨기지 않고 선택한 모듈만 실행하는지 확인한다.
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

const runner = new URL("./run-hermes-python-tests.ts", import.meta.url).pathname;
test("없는 모듈은 실행 전에 실패한다", () => {
  const result = spawnSync(process.execPath, [runner, "missing-module.py"], { encoding: "utf8" });
  assert.equal(result.status, 1);
  assert.equal(result.stderr.trim(), "없는 모듈이다: missing-module.py");
});
test("기존 argparse의 붙여 쓴 동시 실행 수와 옵션 구분자를 받는다", () => {
  for (const options of [["--jobs=2"], ["-j2"], ["--jobs", "2"], ["--"]]) {
    const result = spawnSync(process.execPath, [runner, ...options, "missing-module.py"], { encoding: "utf8" });
    assert.equal(result.status, 1, `${options.join(" ")}: ${result.stderr}`);
    assert.equal(result.stderr.trim(), "없는 모듈이다: missing-module.py");
  }
});
test("선택 모듈을 각각 실행하고 실패 코드와 출력을 보존한다", () => {
  const directory = mkdtempSync(join(tmpdir(), "hermes-runner-"));
  const log = join(directory, "calls.jsonl");
  const python = join(directory, "python3");
  writeFileSync(python, `#!${process.execPath}\nconst fs=require('node:fs');const name=process.argv.at(-1);fs.appendFileSync(process.env.CALL_LOG,JSON.stringify(process.argv.slice(2))+'\\n');if(name==='test_plugin_loading.py'){console.error('모듈 실패 원문');process.exit(5)}console.log('성공 모듈 상세');\n`);
  chmodSync(python, 0o755);
  try {
    const result = spawnSync(process.execPath, [runner, "-j", "2", "test_plugin_loading.py", "test_hermes_contract.py"], {
      encoding: "utf8", env: { ...process.env, PATH: `${directory}:${process.env.PATH}`, CALL_LOG: log },
    });
    assert.equal(result.status, 1);
    assert.match(result.stdout, /통과 test_hermes_contract.py/);
    assert.match(result.stdout, /실패 test_plugin_loading.py \(종료 코드 5,/);
    assert.match(result.stdout, /모듈 실패 원문/);
    assert.doesNotMatch(result.stdout, /성공 모듈 상세/);
    assert.match(result.stdout, /모듈 2개를 2개 프로세스로 .*실패 1개/);
    assert.match(result.stderr, /실패한 모듈: test_plugin_loading.py/);
    const calls = readFileSync(log, "utf8").trim().split("\n").map(line => JSON.parse(line));
    assert.deepEqual(calls.map(args => args.at(-1)).sort(), ["test_hermes_contract.py", "test_plugin_loading.py"]);
    assert.ok(calls.every(args => args.slice(0, 3).join(" ") === "-m unittest discover"));
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
