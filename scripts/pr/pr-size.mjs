#!/usr/bin/env node
// PR의 base와 head의 공통 조상부터 추가·삭제 줄을 센다.
import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";
import { matchesGlob } from "node:path";
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

// 운영 코드로 셀 경로: 에이전트의 도구 사용을 정하는 커넥터 스킬 문서.
const PRODUCTION_PATHS = [/^hermes\/connectors\/[^/]+\/skills\/[^/]+\/SKILL\.md$/];

// 시험과 문서는 규모를 표시하되 운영 코드 상한에는 넣지 않는다.
export function category(file) {
  if (PRODUCTION_PATHS.some(pattern => pattern.test(file))) return "production";
  if (/\.md$/i.test(file) || /(^|\/)docs\//.test(file)) return "docs";
  if (/(^|\/)(test|tests|e2e)\//.test(file) || /\.(test|spec)\.[^/]+$/.test(file)) return "tests";
  return "production";
}

function parseChanges(numstat) {
  const changes = [];
  const records = numstat.split("\0");
  for (let i = 0; i < records.length; i++) {
    if (!records[i]) continue;
    const match = /^(\d+|-)\t(\d+|-)\t([\s\S]*)$/.exec(records[i]);
    if (!match) throw new Error("numstat 형식이 올바르지 않습니다");
    const [, added, deleted, file] = match;
    // -z 의 이름 변경 기록은 빈 경로 뒤에 이전·새 경로를 각각 담는다.
    const files = file ? [file] : [records[++i], records[++i]];
    if (files.some((name) => !name)) throw new Error("이름 변경 경로가 없습니다");
    const changed = added === "-" ? 0 : Number(added) + Number(deleted);
    let kind = "ignored";
    if (!files.every(excluded)) {
      const kinds = files.filter((name) => !excluded(name)).map(category);
      // 운영 코드를 다른 영역으로 옮기며 수정해도 그 변경을 상한에 넣는다.
      kind = kinds.includes("production") ? "production" : kinds.includes("tests") ? "tests" : "docs";
    }
    changes.push({ files, changed, kind });
  }
  return changes;
}

export function countChanges(numstat) {
  return sumChanges(parseChanges(numstat));
}

function sumChanges(changes) {
  const counts = { production: 0, tests: 0, docs: 0, ignored: 0 };
  for (const change of changes) counts[change.kind] += change.changed;
  return counts;
}

export function classify(production, labels = []) {
  return { large: production > LARGE_THRESHOLD, pass: production <= MAX_LINES || labels.includes("규모:예외") };
}

function git(args, cwd) {
  return execFileSync("git", args, { cwd, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
}

function changesBetween(base, head, cwd) {
  return parseChanges(git(["diff", "--numstat", "-z", "--find-renames", `${base}...${head}`, "--"], cwd));
}

export function compare(base, head, cwd) {
  return sumChanges(changesBetween(base, head, cwd));
}

function area(file) {
  const feature = /^(backend\/src\/(?:main|test)\/java\/com\/bifos\/assistant\/[^/]+|web\/src\/(?:app|features)\/[^/]+|hermes\/connectors\/[^/]+)(?=\/)/.exec(file);
  return feature?.[1] || file.split("/")[0];
}

// 후보는 서로 겹칠 수 있다. 각 묶음은 독립 PR 확정안이 아니라 나눌 곳의 단서다.
function splitCandidates(base, head, changes, cwd) {
  const candidates = [];
  const add = (kind, name, selected) => {
    if (selected.length) candidates.push({ kind, name,
      files: [...new Set(selected.flatMap(change => change.files))].sort(),
      counts: sumChanges(selected) });
  };
  const ancestor = git(["merge-base", base, head], cwd).trim();
  for (const file of [...new Set(changes.flatMap(change => change.files))].sort()) {
    if (!/^tasks\/[^/]+\/phase-\d+\.md$/.test(file)) continue;
    // 지운 계획서도 이번 PR의 경계 근거로 쓸 수 있다.
    const exists = git(["ls-tree", "--name-only", head, "--", file], cwd).trim();
    const content = git(["show", `${exists ? head : ancestor}:${file}`], cwd);
    const section = /^## 변경 파일[^\n]*\n([\s\S]*?)(?=^## |$(?![\s\S]))/m.exec(content)?.[1] || "";
    const patterns = [...section.matchAll(/`([^`]+)`/g)].map(match => match[1]);
    add("phase", file, changes.filter(change => change.files.some(name =>
      patterns.some(pattern => name === pattern || matchesGlob(name, pattern)))));
  }
  const statuses = git(["diff", "--name-status", "-z", "--find-renames", `${base}...${head}`, "--"], cwd).split("\0");
  for (let i = 0; i < statuses.length && statuses[i];) {
    const status = statuses[i++];
    const before = statuses[i++];
    const after = /^[RC]/.test(status) ? statuses[i++] : before;
    if (status === "R100") add("move", `${before} → ${after}`,
      changes.filter(change => change.files[0] === before && change.files[1] === after));
  }
  const areas = new Map();
  for (const change of changes) {
    const name = area(change.files.at(-1));
    if (!areas.has(name)) areas.set(name, []);
    areas.get(name).push(change);
  }
  for (const [name, selected] of [...areas].sort(([a], [b]) => a.localeCompare(b))) add("area", name, selected);
  return candidates;
}

export function report(base, head, cwd, labels = []) {
  const changes = changesBetween(base, head, cwd);
  const counts = sumChanges(changes);
  return { schema_version: 1, total_lines: counts.production + counts.tests + counts.docs,
    counts, large_threshold: LARGE_THRESHOLD, max_lines: MAX_LINES,
    ...classify(counts.production, labels), split_candidates: splitCandidates(base, head, changes, cwd) };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2);
  const json = args.includes("--json");
  const [base, head = "HEAD"] = args.filter(arg => arg !== "--json");
  if (!base) throw new Error("사용법: node scripts/pr/pr-size.mjs <base> [head] [--json]");
  const verdict = report(base, head, undefined, JSON.parse(process.env.PR_LABELS || "[]"));
  const result = verdict.counts;
  const summary = `PR 변경: 운영 코드 ${result.production}줄, 시험 ${result.tests}줄, 문서 ${result.docs}줄 (생성 파일 제외 ${result.ignored}줄), 운영 코드 상한 ${MAX_LINES}줄, ${verdict.pass ? "통과" : "실패"}`;
  const candidates = verdict.split_candidates.map(candidate =>
    `- ${candidate.kind}: ${candidate.name} (운영 코드 ${candidate.counts.production}줄, 파일 ${candidate.files.length}개)`);
  const detail = [summary, ...(verdict.large || verdict.split_candidates.some(candidate => candidate.kind === "move")
    ? ["나눌 경계 후보 (묶음끼리 겹칠 수 있음):", ...candidates] : [])].join("\n");
  console.log(json ? JSON.stringify(verdict) : detail);
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `large=${verdict.large}\nproduction=${result.production}\ntests=${result.tests}\ndocs=${result.docs}\n`);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${detail}\n`);
  if (!verdict.pass) {
    console.error(`::error::PR 운영 코드 변경이 ${MAX_LINES}줄을 넘었습니다. 단계별 PR로 나누세요. 규모:예외 라벨은 사람이나 코디네이터만 붙입니다.`);
    process.exitCode = 1;
  }
}
