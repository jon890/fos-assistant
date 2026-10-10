import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

const prepare = resolve("scripts/prepare-intellij-format.py");
const install = resolve("scripts/install-intellij-formatter.py");

function fixture(t: test.TestContext) {
  const root = mkdtempSync(join(tmpdir(), "fos-intellij-test-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const backend = join(root, "backend");
  const source = join(backend, "src/main/java/App.java");
  mkdirSync(join(backend, "src/main/java"), { recursive: true });
  writeFileSync(source, "class App { int n=1; }\n");
  const settings = join(root, "style.xml");
  writeFileSync(settings, "one");
  const output = join(backend, "build/result");
  const binary = join(root, "idea");
  writeFileSync(binary, `#!/usr/bin/env python3
import os, pathlib, sys, time
mode = os.environ.get('FAKE_IDEA_MODE', '')
if mode == 'timeout': time.sleep(5)
if mode == 'failure': sys.exit(7)
if mode == 'error': print('ERROR: formatter exception', file=sys.stderr)
if mode == 'input-changed': pathlib.Path(os.environ['FAKE_IDEA_SOURCE']).write_text('class App { int n=2; }\\n')
assert 'IDEA_PROPERTIES' in os.environ
props = pathlib.Path(os.environ['IDEA_PROPERTIES']).read_text()
assert all('idea.' + key + '.path=' in props for key in ('config','system','plugins','log'))
folder = pathlib.Path(sys.argv[-1])
files = list(folder.rglob('*.java'))
style = pathlib.Path(sys.argv[sys.argv.index('-s') + 1]).read_text()
for file in files:
    file.write_text(file.read_text().replace('n=1', 'n = 1') + '// ' + style + '\\n')
    print('Formatting ' + str(file) + '...OK')
count = len(files) - (1 if mode == 'partial' else 0)
print(str(count) + ' file(s) scanned.')
print(str(count) + ' file(s) formatted.')
`);
  chmodSync(binary, 0o755);
  function run(mode = "", timeout = 2) {
    return spawnSync("python3", [prepare, "--backend", backend, "--output", output, "--settings", settings, "--binary", binary, "--timeout", String(timeout)], {
      encoding: "utf8", env: { ...process.env, FAKE_IDEA_MODE: mode, FAKE_IDEA_SOURCE: source },
    });
  }
  return { root, backend, source, settings, output, run };
}

test("batch check 는 원본을 보존하고 전체 성공 사본만 게시한다", (t) => {
  const f = fixture(t);
  const before = readFileSync(f.source, "utf8");
  assert.equal(f.run().status, 0);
  assert.equal(readFileSync(f.source, "utf8"), before);
  assert.equal(readFileSync(join(f.output, "original/src/main/java/App.java"), "utf8"), before);
  assert.match(readFileSync(join(f.output, "formatted/src/main/java/App.java"), "utf8"), /n = 1/);
  assert.equal(Object.keys(JSON.parse(readFileSync(join(f.output, "manifest.json"), "utf8")).files).length, 1);
});

test("Java 변경과 삭제 및 설정 변경 뒤에 이전 사본을 쓰지 않는다", (t) => {
  const f = fixture(t);
  assert.equal(f.run().status, 0);
  writeFileSync(f.source, "class App { int n=2; }\n");
  writeFileSync(f.settings, "two");
  assert.equal(f.run().status, 0);
  assert.match(readFileSync(join(f.output, "formatted/src/main/java/App.java"), "utf8"), /n=2.*\n\/\/ two/);
  rmSync(f.source);
  assert.equal(f.run().status, 0);
  assert.equal(existsSync(join(f.output, "formatted/src/main/java/App.java")), false);
  assert.deepEqual(JSON.parse(readFileSync(join(f.output, "manifest.json"), "utf8")).files, {});
});

for (const mode of ["failure", "partial", "timeout", "error"]) {
  test(`IntelliJ ${mode} 에서 실패하고 이전 성공 결과도 폐기한다`, (t) => {
    const f = fixture(t);
    assert.equal(f.run().status, 0);
    assert.notEqual(f.run(mode, mode === "timeout" ? 0.05 : 2).status, 0);
    assert.equal(existsSync(f.output), false);
    assert.equal(readFileSync(f.source, "utf8"), "class App { int n=1; }\n");
  });
}

test("개발자 설치의 IntelliJ 버전과 build 가 다르면 거절한다", (t) => {
  const f = fixture(t);
  const home = join(f.root, "ide");
  mkdirSync(join(home, "bin"), { recursive: true });
  const binary = join(home, "bin/idea.sh");
  writeFileSync(binary, "#!/bin/sh\nexit 0\n");
  chmodSync(binary, 0o755);
  const info = join(home, "product-info.json");
  const catalog = join(f.root, "versions.toml");
  writeFileSync(catalog, '[versions]\nintellij-idea = "2026.1.3"\nintellij-idea-build = "261.25134.95"\n');
  const run = () => spawnSync("python3", [install, "--home", home, "--catalog", catalog], { encoding: "utf8" });
  writeFileSync(info, JSON.stringify({ version: "2026.1.3", buildNumber: "261.25134.95" }));
  assert.equal(run().status, 0);
  writeFileSync(info, JSON.stringify({ version: "2026.1.2", buildNumber: "261.25134.95" }));
  assert.notEqual(run().status, 0);
  writeFileSync(info, JSON.stringify({ version: "2026.1.3", buildNumber: "wrong" }));
  assert.notEqual(run().status, 0);
});

test("배포본 checksum 이 다르면 설치를 게시하지 않는다", (t) => {
  const f = fixture(t);
  const cache = join(f.root, "cache");
  const script = `import io, platform, runpy, sys, urllib.request
module = runpy.run_path(sys.argv[1])
platform.system = lambda: 'Linux'
platform.machine = lambda: 'aarch64'
urllib.request.urlopen = lambda *args, **kwargs: io.BytesIO(b'corrupted archive')
module['resolve'](sys.argv[2], cache=sys.argv[3])
`;
  const result = spawnSync("python3", ["-c", script, install, resolve("backend/gradle/libs.versions.toml"), cache], { encoding: "utf8" });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /SHA256 불일치/);
  assert.equal(existsSync(join(cache, "2026.1.3-aarch64-7659e7916092/.verified-sha256")), false);
});

test("formatter 실행 중 Java 입력이 바뀌면 성공 결과를 게시하지 않는다", (t) => {
  const f = fixture(t);
  assert.equal(f.run().status, 0);
  const result = f.run("input-changed");
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /원본 Java 입력이 바뀌었다/);
  assert.equal(existsSync(f.output), false);
  assert.equal(readFileSync(f.source, "utf8"), "class App { int n=2; }\n");
});

test("source 디렉터리를 결과 경로로 지정해도 삭제하지 않는다", (t) => {
  const f = fixture(t);
  const result = spawnSync("python3", [prepare, "--backend", f.backend, "--output", join(f.backend, "src/main/java"), "--settings", f.settings, "--binary", "missing"], { encoding: "utf8" });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /backend\/build/);
  assert.equal(readFileSync(f.source, "utf8"), "class App { int n=1; }\n");
});
