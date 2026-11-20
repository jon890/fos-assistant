import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { MAX_LINES } from "./pr-size.mjs";
import { WARN_RATIO, advice, isGitCommit, shouldSkip } from "./pr-size-hook.mjs";

const SCRIPT = new URL("./pr-size-hook.mjs", import.meta.url).pathname;
const WARN_AT = Math.ceil(MAX_LINES * WARN_RATIO);
// 테스트 자식이 CI 출력이나 다른 저장소의 PR 을 보지 않게 한다.
const ENV = { ...process.env };
for (const key of ["GITHUB_OUTPUT", "GITHUB_STEP_SUMMARY", "PR_LABELS", "GH_REPO"]) delete ENV[key];

test("git commit 이 든 명령만 잡는다", () => {
  for (const command of ["git commit -m x", "git add -A && git commit -F msg", "git -C a/b commit", "cd x; git commit"]) {
    assert.equal(isGitCommit(command), true, command);
  }
  for (const command of ["git status", "git log --grep commit", "echo git", "git commit-tree abc", undefined]) {
    assert.equal(isGitCommit(command), false, String(command));
  }
});

test("main, 분리된 HEAD, 예외 라벨 브랜치는 건너뛴다", () => {
  assert.equal(shouldSkip({ branch: "main" }), true);
  assert.equal(shouldSkip({ branch: "" }), true);
  assert.equal(shouldSkip({ branch: "jon890/a", labels: ["규모:예외"] }), true);
  assert.equal(shouldSkip({ branch: "jon890/a", labels: ["규모:큼"] }), false);
  assert.equal(shouldSkip({ branch: "jon890/a" }), false);
});

test("상한의 80% 부터 안내하고 넘으면 예외 라벨 대신 코디네이터에게 물으라고 더한다", () => {
  assert.equal(advice(WARN_AT - 1, MAX_LINES), null);
  const near = advice(WARN_AT, MAX_LINES);
  assert.match(near ?? "", new RegExp(`${WARN_AT}줄.*${MAX_LINES}줄`));
  assert.match(near ?? "", /남은 범위/);
  assert.doesNotMatch(near ?? "", /결정 블록/);
  assert.doesNotMatch(advice(MAX_LINES, MAX_LINES) ?? "", /결정 블록/);
  assert.match(advice(MAX_LINES + 1, MAX_LINES) ?? "", /규모:예외 라벨은 붙이지 말고 코디네이터에게 결정 블록으로 묻는다/);
});

function repoWith(lines: number, branch: string) {
  const dir = mkdtempSync(join(tmpdir(), "pr-size-hook-"));
  const git = (...args: string[]) => execFileSync("git", args, { cwd: dir, encoding: "utf8" });
  git("init", "-q", "-b", "main");
  git("config", "user.email", "test@example.com");
  git("config", "user.name", "test");
  writeFileSync(join(dir, "README.md"), "base\n");
  git("add", ".");
  git("commit", "-qm", "base");
  git("update-ref", "refs/remotes/origin/main", "HEAD");
  if (branch !== "main") git("switch", "-qc", branch);
  writeFileSync(join(dir, "app.ts"), "x\n".repeat(lines));
  git("add", ".");
  git("commit", "-qm", "change");
  return { dir, git };
}

function runHook(cwd: string, command = "git commit -m change") {
  const result = spawnSync(process.execPath, [SCRIPT], { input: JSON.stringify({ cwd, tool_input: { command } }), env: ENV, encoding: "utf8" });
  assert.equal(result.status, 0);
  return result.stdout ? JSON.parse(result.stdout).hookSpecificOutput : null;
}

test("실제 저장소에서 commit 뒤 80% 이상이면 안내를 내고 미만이거나 main 이면 내지 않는다", t => {
  const cases: Array<[number, string, boolean]> = [[WARN_AT - 1, "feature", false], [WARN_AT, "feature", true], [WARN_AT, "main", false]];
  for (const [lines, branch, expected] of cases) {
    const { dir } = repoWith(lines, branch);
    t.after(() => rmSync(dir, { recursive: true, force: true }));
    const output = runHook(dir);
    assert.equal(Boolean(output), expected, `${lines}줄, ${branch}`);
    if (output) {
      assert.equal(output.hookEventName, "PostToolUse");
      assert.match(output.additionalContext, new RegExp(`${lines}줄`));
    }
  }
});

test("분리된 HEAD, commit 이 아닌 명령, 잘못된 입력에서는 조용히 끝난다", t => {
  const { dir, git } = repoWith(MAX_LINES + 1, "feature");
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  assert.equal(runHook(dir, "git status"), null);
  assert.match(runHook(dir).additionalContext, /결정 블록/);
  git("switch", "-q", "--detach");
  assert.equal(runHook(dir), null);
  const broken = spawnSync(process.execPath, [SCRIPT], { input: "not json", env: ENV, encoding: "utf8" });
  assert.equal(broken.status, 0);
  assert.equal(broken.stdout, "");
});
