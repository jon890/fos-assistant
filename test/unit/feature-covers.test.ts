import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import test from "node:test";
import { coveredBy, parseCovers, staleFeatures } from "../../scripts/check-feature-covers.mjs";

const script = resolve(import.meta.dirname, "../../scripts/check-feature-covers.mjs");

test("parseCovers 는 제목과 첫 절 사이의 covers 줄에서 경로를 순서대로 낸다", () => {
  const markdown = [
    "# 커넥터 연결",
    "",
    "사용자가 커넥터에 계정을 연결하는 기능이다.",
    "",
    "covers: `backend/connector/`, `web/src/components/connector/`, `hermes/connectors/**/Art*`",
    "",
    "## 요구",
    "",
    "covers: `after/heading/`",
  ].join("\n");
  assert.deepEqual(parseCovers(markdown), [
    "backend/connector/",
    "web/src/components/connector/",
    "hermes/connectors/**/Art*",
  ]);
});

test("parseCovers 는 코드 펜스 안의 covers 줄을 무시하고 줄이 없으면 빈 배열이다", () => {
  const fenced = ["# 기능", "", "```markdown", "covers: `inside/fence/`", "```", "", "## 요구"].join("\n");
  assert.deepEqual(parseCovers(fenced), []);
  assert.deepEqual(parseCovers("# 기능\n\n한 문장이다.\n\n## 요구\n"), []);
  assert.deepEqual(parseCovers(""), []);
});

test("coveredBy 는 디렉터리와 파일, glob 경로를 구분해 맞춘다", () => {
  assert.equal(coveredBy("a/connector/Service.java", "a/connector/"), true);
  assert.equal(coveredBy("a/connector/deep/Service.java", "a/connector/"), true);
  assert.equal(coveredBy("a/connector-x/Service.java", "a/connector/"), false);
  assert.equal(coveredBy("a/b.ts", "a/b.ts"), true);
  assert.equal(coveredBy("a/b.tsx", "a/b.ts"), false);
  assert.equal(coveredBy("a/x/y/Artifact.java", "a/**/Art*"), true);
  assert.equal(coveredBy("a/x/Other.java", "a/**/Art*"), false);
  assert.equal(coveredBy("a/c.ts", "a/*.ts"), true);
  assert.equal(coveredBy("a/b/c.ts", "a/*.ts"), false);
});

test("staleFeatures 는 covers 아래만 바뀌고 기능 파일은 그대로인 기능만 이름 순서로 낸다", () => {
  const features = new Map([
    ["docs/features/connector.md", ["web/connector/"]],
    ["docs/features/chat.md", ["web/chat/", "web/connector/shared.ts"]],
  ]);
  // covers 아래 파일만 바뀌면 그 기능이 나온다.
  assert.deepEqual(staleFeatures(["web/chat/a.ts"], features), [
    { feature: "docs/features/chat.md", files: ["web/chat/a.ts"] },
  ]);
  // 기능 파일도 함께 바뀌면 나오지 않는다.
  assert.deepEqual(staleFeatures(["web/chat/a.ts", "docs/features/chat.md"], features), []);
  // covers 밖 파일만 바뀌면 나오지 않는다.
  assert.deepEqual(staleFeatures(["web/other/a.ts", "README.md"], features), []);
  // 두 기능이 같은 경로를 가지면 둘 다 나온다.
  assert.deepEqual(staleFeatures(["web/connector/shared.ts"], features), [
    { feature: "docs/features/chat.md", files: ["web/connector/shared.ts"] },
    { feature: "docs/features/connector.md", files: ["web/connector/shared.ts"] },
  ]);
});

function repository(t: test.TestContext) {
  const root = mkdtempSync(join(tmpdir(), "feature-covers-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const git = (...args: string[]) =>
    execFileSync("git", ["-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", ...args], {
      cwd: root,
      stdio: "pipe",
      encoding: "utf8",
    }).trim();
  const put = (file: string, text: string) => {
    mkdirSync(join(root, dirname(file)), { recursive: true });
    writeFileSync(join(root, file), text);
  };
  put("docs/features/connector.md", "# 커넥터\n\n한 문장이다.\n\ncovers: `src/연결/`, `src/a.ts`\n\n## 요구\n");
  put("docs/features/chat.md", "# 대화\n\n한 문장이다.\n\n## 요구\n");
  for (const name of ["one", "two", "three", "four", "five"]) put(`src/연결/${name}.ts`, "export {};\n");
  put("src/a.ts", "export {};\n");
  git("init", "--quiet");
  git("add", ".");
  git("commit", "--quiet", "-m", "base");
  const summary = join(root, "..", `${root.split("/").at(-1)}-summary.md`);
  writeFileSync(summary, "");
  t.after(() => rmSync(summary, { force: true }));
  const run = (...args: string[]) =>
    spawnSync(process.execPath, [script, ...args], {
      cwd: root,
      encoding: "utf8",
      env: { ...process.env, GITHUB_STEP_SUMMARY: summary },
    });
  return { root, git, put, run, base: git("rev-parse", "HEAD"), summary: () => readFileSync(summary, "utf8") };
}

test("스크립트는 covers 경로를 바꾸고 기능 파일을 그대로 둔 PR 에 경고 한 줄을 내고 0 으로 끝난다", (t) => {
  const repo = repository(t);
  for (const name of ["one", "two", "three", "four", "five"]) repo.put(`src/연결/${name}.ts`, "export const x = 1;\n");
  repo.git("commit", "--quiet", "-am", "change");
  const result = repo.run(repo.base);
  assert.equal(result.status, 0, result.stderr);
  const warnings = result.stdout.split("\n").filter((line) => line.startsWith("::warning"));
  assert.deepEqual(warnings.length, 1, result.stdout);
  assert.match(warnings[0], /^::warning file=docs\/features\/connector\.md::이 PR 이 covers 경로의 파일 5개\(src\/연결\/five\.ts, src\/연결\/four\.ts, src\/연결\/one\.ts 외 2개\)를 바꿨는데 이 기능 파일은 바꾸지 않았다/);
  assert.match(repo.summary(), /docs\/features\/connector\.md`: 이 PR 이 covers 경로의 파일 5개/);
});

test("스크립트는 기능 파일도 함께 바꾼 PR 에는 경고를 내지 않는다", (t) => {
  const repo = repository(t);
  repo.put("src/a.ts", "export const a = 1;\n");
  repo.put("docs/features/connector.md", "# 커넥터\n\n바꾼 문장이다.\n\ncovers: `src/연결/`, `src/a.ts`\n\n## 요구\n");
  repo.git("commit", "--quiet", "-am", "change");
  const result = repo.run(repo.base, "HEAD");
  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(result.stdout, /::warning/);
  assert.equal(repo.summary(), "");
});

test("스크립트는 covers 밖으로 이름을 바꿔 옮긴 파일도 경고한다", (t) => {
  const repo = repository(t);
  mkdirSync(join(repo.root, "lib"), { recursive: true });
  renameSync(join(repo.root, "src/연결/one.ts"), join(repo.root, "lib/one.ts"));
  repo.git("add", "-A");
  repo.git("commit", "--quiet", "-m", "move");
  const result = repo.run(repo.base);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /::warning file=docs\/features\/connector\.md::이 PR 이 covers 경로의 파일 1개\(src\/연결\/one\.ts\)를 바꿨는데/);
  assert.match(repo.summary(), /src\/연결\/one\.ts/);
});

test("스크립트는 base 를 읽지 못하면 알림만 내고 0 으로 끝난다", (t) => {
  const repo = repository(t);
  const result = repo.run("refs/heads/missing-feature-covers-base");
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /^알림: /m);
  assert.doesNotMatch(result.stdout, /::warning/);
  assert.equal(repo.summary(), "");
});
