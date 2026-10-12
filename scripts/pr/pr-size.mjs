#!/usr/bin/env node
// PR의 base와 head의 공통 조상부터 추가·삭제 줄을 센다.
import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

// 수치와 제외 규칙은 이 파일에서 관리한다.
export const LARGE_THRESHOLD = 400;
export const MAX_LINES = 1500;

export function excluded(file) {
  return /(^|\/)(pnpm-lock\.yaml|bun\.lockb?|package-lock\.json|yarn\.lock|npm-shrinkwrap\.json|Cargo\.lock|poetry\.lock|uv\.lock|Pipfile\.lock|composer\.lock|Gemfile\.lock)$/.test(file) ||
    /(^|\/)(dist|build|\.next|coverage|__snapshots__|[^/]+-snapshots)\//.test(file) ||
    /\.(snap|min\.js|bundle\.js|js\.map|css\.map)$/.test(file) ||
    /(^|\/)config\/archunit\/store\//.test(file) ||
    ["backend/config/checkstyle/baseline.xml", "web/eslint-suppressions.json", "test/unit/migration-checksums.json"].includes(file);
}

// 시험과 문서는 규모를 표시하되 운영 코드 상한에는 넣지 않는다.
export function category(file) {
  if (/\.md$/i.test(file) || /(^|\/)docs\//.test(file)) return "docs";
  if (/(^|\/)(test|tests|e2e)\//.test(file) || /\.(test|spec)\.[^/]+$/.test(file)) return "tests";
  return "production";
}

export function countChanges(numstat) {
  const records = numstat.split("\0");
  const counts = { production: 0, tests: 0, docs: 0, ignored: 0 };
  for (let i = 0; i < records.length; i++) {
    if (!records[i]) continue;
    const match = /^(\d+|-)\t(\d+|-)\t([\s\S]*)$/.exec(records[i]);
    if (!match) throw new Error("numstat 형식이 올바르지 않습니다");
    const [, added, deleted, file] = match;
    // -z 의 이름 변경 기록은 빈 경로 뒤에 이전·새 경로를 각각 담는다.
    const files = file ? [file] : [records[++i], records[++i]];
    if (files.some((name) => !name)) throw new Error("이름 변경 경로가 없습니다");
    const changed = added === "-" ? 0 : Number(added) + Number(deleted);
    if (files.every(excluded)) counts.ignored += changed;
    else {
      const kinds = files.filter((name) => !excluded(name)).map(category);
      // 운영 코드를 다른 영역으로 옮기며 수정해도 그 변경을 상한에 넣는다.
      const kind = kinds.includes("production") ? "production" : kinds.includes("tests") ? "tests" : "docs";
      counts[kind] += changed;
    }
  }
  return counts;
}

export function classify(production, labels = []) {
  return { large: production > LARGE_THRESHOLD, pass: production <= MAX_LINES || labels.includes("규모:예외") };
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
  const verdict = classify(result.production, JSON.parse(process.env.PR_LABELS || "[]"));
  const summary = `PR 변경: 운영 코드 ${result.production}줄, 시험 ${result.tests}줄, 문서 ${result.docs}줄 (생성 파일 제외 ${result.ignored}줄), 운영 코드 상한 ${MAX_LINES}줄, ${verdict.pass ? "통과" : "실패"}`;
  console.log(summary);
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `large=${verdict.large}\nproduction=${result.production}\ntests=${result.tests}\ndocs=${result.docs}\n`);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${summary}\n`);
  if (!verdict.pass) {
    console.error(`::error::PR 운영 코드 변경이 ${MAX_LINES}줄을 넘었습니다. 단계별 PR로 나누세요. 규모:예외 라벨은 사람이나 코디네이터만 붙입니다.`);
    process.exitCode = 1;
  }
}
