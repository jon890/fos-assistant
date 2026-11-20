#!/usr/bin/env node
// 마이그레이션 버전의 날짜로 14일이 지난 데이터 테스트를 지울 후보로 뽑는다. 실패시키지 않고 후보만 알린다.
//
// 후보는 backend 테스트 중 Flyway 를 쓰고 시각 버전(14자리)을 적은 클래스와 그 클래스를 상속한 클래스다.
// 적은 버전 가운데 가장 늦은 것의 날짜가 오늘에서 14일보다 앞서면 후보다. 버전의 날짜는 번호를 받은 날이라 머지 날짜와 가깝다.
// 운영에 실제로 적용됐는지는 이 저장소에서 볼 수 없다. 지우는 PR 에서 운영 기록과 대조하는 절차는
// backend/docs/data-schema.md 의 「운영에 적용된 지 14일이 지난 마이그레이션의 데이터 검사는 지운다」 가 갖는다.
//
// 오래돼도 지우지 않을 검사는 파일에 `// migration-test-prune: keep <까닭>` 줄을 둔다. 그 클래스를 상속한 클래스도 빠진다.
//
// 사용법: node scripts/checks/migration-test-prune.ts [--today YYYY-MM-DD] [--days N] [--github]
//   --github 는 후보마다 GitHub Actions 경고 줄(::warning)을 낸다. 종료 코드는 후보가 있어도 0 이다.
import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

export type Candidate = { file: string; className: string; version: string; ageDays: number };

type TestClass = { file: string; className: string; parent?: string; versions: string[]; keep: boolean; flyway: boolean };

const KEEP = /^\s*\/\/\s*migration-test-prune:\s*keep\b/m;
const VERSION = /\b(?:V)?(20\d{12})\b/g;
const DAY = 86_400_000;

function javaFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const full = join(directory, entry.name);
    if (entry.isDirectory()) return javaFiles(full);
    return entry.name.endsWith(".java") ? [full] : [];
  });
}

function parse(root: string, file: string): TestClass | undefined {
  const text = readFileSync(file, "utf8");
  const declared = /^(?:public\s+|abstract\s+|final\s+)*class\s+(\w+)(?:<[^>]*>)?(?:\s+extends\s+(\w+))?/m.exec(text);
  if (!declared) return undefined;
  return {
    file: relative(root, file),
    className: declared[1],
    parent: declared[2],
    versions: [...new Set([...text.matchAll(VERSION)].map(match => match[1]))].sort(),
    keep: KEEP.test(text),
    flyway: text.includes("org.flywaydb"),
  };
}

/** 버전 앞 8자리를 UTC 날짜로 읽는다. 날짜가 아니면 undefined 다. */
function versionDate(version: string): number | undefined {
  const year = Number(version.slice(0, 4));
  const month = Number(version.slice(4, 6));
  const day = Number(version.slice(6, 8));
  const time = Date.UTC(year, month - 1, day);
  const date = new Date(time);
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day ? time : undefined;
}

export function findCandidates(root: string, today: Date, days = 14): Candidate[] {
  const tests = join(root, "backend/src/test/java");
  const classes = new Map<string, TestClass>();
  for (const file of javaFiles(tests)) {
    const parsed = parse(root, file);
    if (parsed) classes.set(parsed.className, parsed);
  }
  const todayTime = Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate());

  /** 그 클래스나 조상이 정한 데이터 테스트의 버전이다. 지우지 않는 표시가 있거나 데이터 테스트가 아니면 undefined 다. */
  const own = (testClass: TestClass, seen = new Set<string>()): string | undefined => {
    if (testClass.keep || seen.has(testClass.className)) return undefined;
    seen.add(testClass.className);
    if (testClass.flyway && testClass.versions.length) return testClass.versions.at(-1);
    const parent = testClass.parent && classes.get(testClass.parent);
    return parent ? own(parent, seen) : undefined;
  };

  const candidates: Candidate[] = [];
  for (const testClass of classes.values()) {
    const version = own(testClass);
    const time = version && versionDate(version);
    if (!version || time === undefined) continue;
    const ageDays = Math.floor((todayTime - time) / DAY);
    if (ageDays > days) candidates.push({ file: testClass.file, className: testClass.className, version: `V${version}`, ageDays });
  }
  return candidates.sort((a, b) => a.version.localeCompare(b.version) || a.file.localeCompare(b.file));
}

function main(argv: string[]) {
  let today = new Date();
  let days = 14;
  let github = false;
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index];
    if (arg === "--github") github = true;
    else if (arg === "--today") {
      const value = argv[++index] ?? "";
      if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(Date.parse(`${value}T00:00:00Z`))) {
        console.error("--today 는 YYYY-MM-DD 여야 한다");
        process.exit(2);
      }
      today = new Date(`${value}T00:00:00Z`);
    } else if (arg === "--days") {
      const value = argv[++index] ?? "";
      if (!/^\d+$/.test(value)) {
        console.error("--days 는 0 이상의 정수여야 한다");
        process.exit(2);
      }
      days = Number(value);
    } else if (arg === "--help" || arg === "-h") {
      console.log("사용법: node scripts/checks/migration-test-prune.ts [--today YYYY-MM-DD] [--days N] [--github]");
      process.exit(0);
    } else {
      console.error(`알 수 없는 옵션: ${arg}`);
      process.exit(2);
    }
  }
  const root = fileURLToPath(new URL("../../", import.meta.url));
  const candidates = findCandidates(root, today, days);
  if (!candidates.length) {
    console.log(`버전 날짜로 ${days}일이 지난 마이그레이션 데이터 테스트가 없다.`);
    return;
  }
  console.log(`버전 날짜로 ${days}일이 지난 마이그레이션 데이터 테스트 ${candidates.length}개다. 운영 적용 기록과 대조해 지운다.`);
  console.log("절차: backend/docs/data-schema.md 의 「운영에 적용된 지 14일이 지난 마이그레이션의 데이터 검사는 지운다」");
  for (const candidate of candidates) {
    const line = `${candidate.className} (${candidate.version}, ${candidate.ageDays}일 지남)`;
    console.log(`- ${candidate.file}: ${line}`);
    if (github) console.log(`::warning file=${candidate.file}::지울 후보인 마이그레이션 데이터 테스트다. ${line}`);
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main(process.argv.slice(2));
