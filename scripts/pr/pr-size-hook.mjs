#!/usr/bin/env node
// Claude Code 의 PostToolUse hook 이다. Bash 로 git commit 을 한 뒤 PR 운영 코드 줄 수를 재고,
// 상한에 가까우면 남은 일을 다음 PR 로 미루라는 안내를 에이전트에게 보인다.
// 상한과 세는 규칙은 pr-size.mjs 가 갖는다. 이 스크립트는 그 --json 결과만 읽는다.
// commit 을 막지 않는다. 무엇이 실패해도 아무것도 내지 않고 0 으로 끝난다.
// 설정은 저장소 root 의 .claude/settings.json 에 있다.
import { execFileSync, spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

export const WARN_RATIO = 0.8;
const EXCEPTION_LABEL = "규모:예외";
const SIZE_SCRIPT = new URL("./pr-size.mjs", import.meta.url).pathname;

// 명령 안에 git commit 이 있는지 본다. `git add . && git commit -m ...`, `git -C dir commit` 도 잡는다.
export function isGitCommit(command) {
  return typeof command === "string" && /(?:^|[\s;&|(])git\s+(?:-[cC]\s+\S+\s+)*commit(?:\s|$)/.test(command);
}

// main, 분리된 HEAD(branch 가 빈 값), 예외 라벨이 붙은 PR 브랜치는 재지 않는다.
export function shouldSkip({ branch, labels = [] }) {
  return !branch || branch === "main" || labels.includes(EXCEPTION_LABEL);
}

// 80% 미만이면 null 이다. 넘었으면 예외 라벨 대신 코디네이터에게 물으라는 문장을 더한다.
export function advice(production, max) {
  if (!(production >= max * WARN_RATIO)) return null;
  const lines = [
    `PR 운영 코드 변경이 ${production}줄로 상한 ${max}줄의 ${Math.round(WARN_RATIO * 100)}% 이상이다(scripts/pr/pr-size.mjs 기준).`,
    "남은 phase 는 이 PR 에 넣지 말고 계획서 deferred 와 PR 본문 「남은 범위」 로 미루고 코디네이터에게 status 로 알린다. 확인받은 스펙의 PR 단위 안에서 미루는 것이라 사용자에게 묻지 않는다.",
  ];
  if (production > max) lines.push(`상한을 넘었다. ${EXCEPTION_LABEL} 라벨은 붙이지 말고 코디네이터에게 결정 블록으로 묻는다.`);
  return lines.join("\n");
}

function run(cmd, args, cwd, timeout) {
  return execFileSync(cmd, args, { cwd, encoding: "utf8", timeout, stdio: ["ignore", "pipe", "ignore"] }).trim();
}

function currentBranch(cwd) {
  try {
    return run("git", ["symbolic-ref", "--short", "-q", "HEAD"], cwd, 2000);
  } catch {
    return "";
  }
}

// 조회가 실패하면(PR 없음, gh 없음, 네트워크) 라벨이 없는 것으로 보고 잰다.
function prLabels(cwd) {
  try {
    return JSON.parse(run("gh", ["pr", "view", "--json", "labels", "--jq", "[.labels[].name]"], cwd, 4000));
  } catch {
    return [];
  }
}

// 상한을 넘으면 pr-size.mjs 가 1 로 끝나므로 종료 코드가 아니라 출력을 읽는다.
function measure(cwd) {
  const base = run("git", ["merge-base", "origin/main", "HEAD"], cwd, 2000);
  const env = { ...process.env };
  for (const key of ["GITHUB_OUTPUT", "GITHUB_STEP_SUMMARY", "PR_LABELS"]) delete env[key];
  const result = spawnSync(process.execPath, [SIZE_SCRIPT, base, "HEAD", "--json"], { cwd, env, encoding: "utf8", timeout: 6000 });
  return JSON.parse(result.stdout);
}

export function main(input) {
  if (!isGitCommit(input?.tool_input?.command)) return null;
  const cwd = input.cwd || process.cwd();
  const branch = currentBranch(cwd);
  if (shouldSkip({ branch })) return null;
  if (shouldSkip({ branch, labels: prLabels(cwd) })) return null;
  const size = measure(cwd);
  const text = advice(size.counts.production, size.max_lines);
  return text && { hookSpecificOutput: { hookEventName: "PostToolUse", additionalContext: text } };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const output = main(JSON.parse(readFileSync(0, "utf8")));
    if (output) process.stdout.write(JSON.stringify(output));
  } catch {
    // hook 이 실패해도 commit 과 대화를 막지 않는다.
  }
  process.exitCode = 0;
}
