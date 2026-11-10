import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test, { afterEach, beforeEach } from "node:test";

const SCRIPT = join(import.meta.dirname, "../../web/scripts/changed-files.mjs");

let repo: string;

/** 사용자와 시스템의 git 설정(서명, 기본 브랜치 이름 등)이 결과에 끼지 않게 격리해 실행한다. */
function git(...args: string[]): string {
  return execFileSync("git", args, {
    cwd: repo,
    encoding: "utf8",
    env: { ...process.env, GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1" },
  });
}

function write(file: string, content: string): void {
  const target = join(repo, file);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, content);
}

function commitAll(message: string): void {
  git("add", "-A");
  git("-c", "user.name=test", "-c", "user.email=test@example.com", "commit", "-q", "-m", message);
}

function runChangedFiles(): { status: number | null; stdout: string; stderr: string } {
  const result = spawnSync("node", [SCRIPT], { cwd: join(repo, "web"), encoding: "utf8" });
  return { status: result.status, stdout: result.stdout, stderr: result.stderr };
}

beforeEach(() => {
  repo = mkdtempSync(join(tmpdir(), "changed-files-"));
  git("init", "-q", "-b", "main");
  write("web/src/base.ts", "export const base = 1;\n");
  write("web/src/other.ts", "export const other = 1;\n");
  write("web/src/gone.md", "# gone\n");
  commitAll("base");
});

afterEach(() => {
  rmSync(repo, { recursive: true, force: true });
});

test("공통 조상 뒤에 바뀐 web 파일과 새 web 파일만 낸다", () => {
  // origin/main 은 갈라진 뒤 앞서 나갔다. main 쪽에서만 바뀐 파일은 이 브랜치가 바꾼 것이 아니다.
  git("checkout", "-q", "-b", "feature");
  write("web/src/base.ts", "export const base = 2;\n");
  write("web/src/[code]/route.ts", "export const route = 1;\n");
  write("web/notes.txt", "Prettier 가 다루지 않는 확장자\n");
  write("api/outside.ts", "export const outside = 1;\n");
  git("rm", "-q", "web/src/gone.md");
  commitAll("feature");

  git("checkout", "-q", "main");
  write("web/src/other.ts", "export const other = 2;\n");
  commitAll("main advanced");
  git("update-ref", "refs/remotes/origin/main", "HEAD");
  git("checkout", "-q", "feature");

  write("web/src/untracked.tsx", "export const untracked = 1;\n");
  write("web/src/staged.ts", "export const staged = 1;\n");
  git("add", "web/src/staged.ts");

  const result = runChangedFiles();

  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(result.stdout.split("\n").filter(Boolean), [
    "src/[code]/route.ts",
    "src/base.ts",
    "src/staged.ts",
    "src/untracked.tsx",
  ]);
});

test("바뀐 파일이 없으면 아무것도 내지 않고 0 으로 끝난다", () => {
  git("update-ref", "refs/remotes/origin/main", "HEAD");
  git("checkout", "-q", "-b", "feature");

  const result = runChangedFiles();

  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, "");
});

test("origin/main 이 없으면 까닭을 표준 오류에 내고 2 로 끝난다", () => {
  const result = runChangedFiles();

  assert.equal(result.status, 2);
  assert.equal(result.stdout, "");
  assert.match(result.stderr, /origin\/main/);
});
