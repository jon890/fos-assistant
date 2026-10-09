#!/usr/bin/env node
// 파일 전체 길이는 빈 줄과 주석을 포함한다. 기존 긴 파일은 기준값보다 늘어나지 못한다.
// 모듈 문서의 한도는 ADR-20261009 / docs-per-module 이, 기능 문서의 한도는 ADR-20261009 / feature-docs 가 정하고, 넘으면 실패가 아니라 알림이다.
import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const BASELINE = "scripts/file-length-baseline.json";
const SKIP_DIRS = new Set([
  "test", "tests", "dist", "build", "bundle", "bundles", "node_modules",
  ".git", ".next", ".venv", "venv", "__pycache__",
]);

export function lineCount(text) {
  if (!text) return 0;
  const lines = text.split(/\r\n|\n|\r/);
  return lines.length - (lines.at(-1) === "" ? 1 : 0);
}

/** 루트와 모듈의 `docs/` 바로 아래 문서다. ADR 은 `adr/` 아래라 빠진다. */
const MODULE_DOC = /^(?:(?:backend|web|hermes)\/)?docs\/[^/]+\.md$/;
/** 루트 `docs/features/` 의 기능 문서다. */
const FEATURE_DOC = /^docs\/features\/[^/]+\.md$/;
const SCAN_DIRS = ["backend/src/main", "web/src", "hermes", "scripts", "docs", "backend/docs", "web/docs"];

export function limitFor(file) {
  if (MODULE_DOC.test(file)) return 1000;
  if (FEATURE_DOC.test(file)) return 500;
  const parts = file.split("/");
  if (parts.some((part) => SKIP_DIRS.has(part)) || /\.(test|spec)\.[cm]?[jt]sx?$/.test(file)) return null;
  if (file.startsWith("backend/src/main/") && file.endsWith(".java")) return 500;
  if (file.startsWith("hermes/") && file.endsWith(".py")) {
    if (/^(test_.*|.*_test)\.py$/.test(parts.at(-1))) return null;
    return 400;
  }
  if ((file.startsWith("web/src/") || /^hermes\/connectors\/[^/]+\/src\//.test(file)) && /\.tsx?$/.test(file)) return 400;
  if (file.startsWith("scripts/") && /\.(ts|mjs)$/.test(file)) return 400;
  return null;
}

function sourceFiles(root, directory) {
  const result = [];
  for (const entry of readdirSync(join(root, directory), { withFileTypes: true })) {
    const file = `${directory}/${entry.name}`;
    if (entry.isDirectory() && !SKIP_DIRS.has(entry.name)) result.push(...sourceFiles(root, file));
    else if (entry.isFile() && limitFor(file) !== null) result.push(file);
  }
  return result;
}

function readBaseline(root) {
  const baseline = JSON.parse(readFileSync(join(root, BASELINE), "utf8"));
  if (baseline.version !== 1 || !baseline.files || !baseline.exclusions ||
      typeof baseline.files !== "object" || Array.isArray(baseline.files) ||
      typeof baseline.exclusions !== "object" || Array.isArray(baseline.exclusions)) {
    throw new Error("기준 파일 형식이 잘못됐다.");
  }
  for (const [file, count] of Object.entries(baseline.files)) {
    const limit = limitFor(file);
    if (limit === null || !Number.isInteger(count) || count <= limit || file in baseline.exclusions) {
      throw new Error(`잘못된 기준 항목: ${file}`);
    }
  }
  for (const [file, reason] of Object.entries(baseline.exclusions)) {
    if (limitFor(file) === null || typeof reason !== "string" || !reason.trim()) {
      throw new Error(`제외 항목에는 검사 대상 경로와 까닭이 필요하다: ${file}`);
    }
  }
  return baseline;
}

export function checkFileLengths(root, { update = false } = {}) {
  const baseline = readBaseline(root);
  const counts = new Map();
  const errors = [];
  const notices = [];
  for (const file of SCAN_DIRS.filter((directory) => existsSync(join(root, directory)))
    .flatMap((directory) => sourceFiles(root, directory)).sort()) {
    if (file in baseline.exclusions) continue;
    const count = lineCount(readFileSync(join(root, file), "utf8"));
    counts.set(file, count);
    const allowed = baseline.files[file] ?? limitFor(file);
    // 모듈 문서는 정해진 파일이라 나눌 수 없고 기능 문서는 새 주제를 절로 더한다. 넘으면 알리기만 한다.
    if (count > allowed && (MODULE_DOC.test(file) || FEATURE_DOC.test(file))) notices.push(`${file}: ${count}줄 > ${allowed}줄. 코드가 가진 값의 복사본을 지워 줄인다.`);
    else if (count > allowed) errors.push(`${file}: ${count}줄 > ${allowed}줄. 파일을 나눈다.`);
  }
  for (const [file, previous] of Object.entries(baseline.files)) {
    const actual = counts.get(file);
    if (actual !== undefined && actual >= previous) continue;
    notices.push(`${file}: ${previous}줄 → ${actual ?? "삭제"}. 기준값을 낮추라: node scripts/check-file-length.mjs --update`);
    if (update) {
      if (actual === undefined || actual <= limitFor(file)) delete baseline.files[file];
      else baseline.files[file] = actual;
    }
  }
  // 위반이 있으면 부분 갱신도 하지 않는다. 새 항목을 추가하거나 기준값을 올리지 않는다.
  if (update && errors.length === 0) {
    writeFileSync(join(root, BASELINE), `${JSON.stringify(baseline, null, 2)}\n`);
  }
  return { errors, notices, checked: counts.size };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    if (args.length > 1 || (args.length === 1 && args[0] !== "--update")) {
      throw new Error("사용법: node scripts/check-file-length.mjs [--update]");
    }
    const result = checkFileLengths(resolve(dirname(fileURLToPath(import.meta.url)), ".."), { update: args.includes("--update") });
    for (const notice of result.notices) console.log(`알림: ${notice}`);
    for (const error of result.errors) console.error(error);
    console.log(`파일 길이: ${result.checked}개 검사, ${result.errors.length}개 위반`);
    process.exitCode = result.errors.length ? 1 : 0;
  } catch (error) {
    console.error(error.message);
    process.exitCode = 2;
  }
}
