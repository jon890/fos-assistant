#!/usr/bin/env node
// PR의 base와 head의 공통 조상부터 추가·삭제 줄을 센다.
import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

// 수치와 제외 규칙은 이 파일에서 관리한다.
export const LARGE_THRESHOLD = 400;
export const MAX_LINES = 1000;

export function excluded(file) {
  return /(^|\/)(pnpm-lock\.yaml|bun\.lockb?|package-lock\.json|yarn\.lock|npm-shrinkwrap\.json|Cargo\.lock|poetry\.lock|uv\.lock|Pipfile\.lock|composer\.lock|Gemfile\.lock)$/.test(file) ||
    /(^|\/)(dist|build|\.next|coverage|__snapshots__|[^/]+-snapshots)\//.test(file) ||
    /\.(snap|min\.js|bundle\.js|js\.map|css\.map)$/.test(file) ||
    /(^|\/)config\/archunit\/store\//.test(file) ||
    ["backend/config/checkstyle/baseline.xml", "web/eslint-suppressions.json", "test/unit/migration-checksums.json"].includes(file);
}

export function countChanges(numstat) {
  const records = numstat.split("\0");
  let lines = 0;
  let ignored = 0;
  for (let i = 0; i < records.length; i++) {
    if (!records[i]) continue;
    const match = /^(\d+|-)\t(\d+|-)\t([\s\S]*)$/.exec(records[i]);
    if (!match) throw new Error("numstat 형식이 올바르지 않습니다");
    const [, added, deleted, file] = match;
    // -z 의 이름 변경 기록은 빈 경로 뒤에 이전·새 경로를 각각 담는다.
    const files = file ? [file] : [records[++i], records[++i]];
    if (files.some((name) => !name)) throw new Error("이름 변경 경로가 없습니다");
    const changed = added === "-" ? 0 : Number(added) + Number(deleted);
    if (files.every(excluded)) ignored += changed;
    else lines += changed;
  }
  return { lines, ignored };
}

export function classify(lines, labels = []) {
  return { large: lines > LARGE_THRESHOLD, pass: lines <= MAX_LINES || labels.includes("규모:예외") };
}

export function compare(base, head, cwd) {
  return countChanges(execFileSync("git", ["diff", "--numstat", "-z", "--find-renames", `${base}...${head}`, "--"], {
    cwd, encoding: "utf8", maxBuffer: 64 * 1024 * 1024,
  }));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [base, head = "HEAD"] = process.argv.slice(2);
  if (!base) throw new Error("사용법: node scripts/pr-size.mjs <base> [head]");
  const result = compare(base, head);
  const verdict = classify(result.lines, JSON.parse(process.env.PR_LABELS || "[]"));
  const summary = `PR 실제 변경: ${result.lines}줄 (제외 ${result.ignored}줄), 상한 ${MAX_LINES}줄, ${verdict.pass ? "통과" : "실패"}`;
  console.log(summary);
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `large=${verdict.large}\nlines=${result.lines}\n`);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${summary}\n`);
  if (!verdict.pass) {
    console.error(`::error::PR 실제 변경이 ${MAX_LINES}줄을 넘었습니다. 단계별 PR로 나누세요. 규모:예외 라벨은 사람이나 코디네이터만 붙입니다.`);
    process.exitCode = 1;
  }
}
