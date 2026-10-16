import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join, normalize } from "node:path";
import test from "node:test";

const ROOT = join(import.meta.dirname, "../..");
const DIRECTORY = "docs/code-rules";
const ARCHITECTURE = "backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java";

interface RuleRow {
  number: number;
  sentence: string;
  checks: string;
}

function rowsOf(markdown: string): RuleRow[] {
  return [...markdown.matchAll(/^\| \[rule-([1-9]\d*)\]\(rule-\1\.md\) \| ([^|\n]+) \| ([^|\n]+) \|$/gm)]
    .map((row) => ({ number: Number(row[1]), sentence: row[2], checks: row[3] }));
}

/** 목록과 파일을 함께 받아 누락과 중복, 본문·검사 연결의 차이를 보고한다. */
function problemsOf(index: string, files: Map<string, string>, readSource: (file: string) => string | undefined): string[] {
  const problems: string[] = [];
  const rows = rowsOf(index);
  const numbers = new Set<number>();
  if (rows.length === 0) problems.push("규칙 목록이 비어 있다");
  const tableRows = index.split("\n").filter((line) => /^\|/.test(line));
  // 표 머리 두 줄 이외에는 모두 규칙 행이어야 잘못 쓴 행이 조용히 빠지지 않는다.
  if (tableRows.length !== rows.length + 2) problems.push("목록 행 형식이 잘못됐다");
  for (const row of rows) {
    const id = `rule-${row.number}`;
    if (numbers.has(row.number)) problems.push(`${id}: 번호 중복`);
    numbers.add(row.number);
    if (!row.sentence.endsWith(".")) problems.push(`${id}: 규칙 한 문장이 필요하다`);
    const body = files.get(`${id}.md`);
    if (body === undefined) {
      problems.push(`${id}: 파일이 없다`);
      continue;
    }
    if (!body.startsWith(`# ${id}\n\n${row.sentence}\n`)) problems.push(`${id}: 제목이나 규칙 문장이 다르다`);
    if (/^검사: (.+)$/m.exec(body)?.[1] !== row.checks) problems.push(`${id}: 검사 연결이 다르다`);
    for (const section of ["까닭", "예외와 검토 범위", "이 저장소의 사례"]) {
      if (!new RegExp(`^## ${section}\\n\\n\\S`, "m").test(body)) problems.push(`${id}: ${section}이 비어 있다`);
    }
    for (const [, target] of body.matchAll(/\[[^\]]+\]\(([^)]+)\)/g)) {
      if (/^https?:/.test(target)) continue;
      const source = normalize(join(DIRECTORY, target.split("#")[0]));
      if (source.startsWith("..") || readSource(source) === undefined) problems.push(`${id}: 본문 링크가 없다: ${target}`);
    }
    const links = [...row.checks.matchAll(/\[([^\]]+)\]\(\.\.\/\.\.\/([^)]+)\)/g)];
    if (links.length === 0 || row.checks.replace(/\[[^\]]+\]\(\.\.\/\.\.\/[^)]+\)/g, "").replace(/[,\s]/g, "") !== "") {
      problems.push(`${id}: 검사 이름과 소스 링크가 필요하다`);
    }
    for (const [, name, source] of links) {
      if (normalize(source) !== source || source.startsWith("..") || source.startsWith("/")) {
        problems.push(`${id}: 검사 경로가 저장소 밖이다`);
        continue;
      }
      const code = readSource(source);
      if (code === undefined) {
        problems.push(`${id}: 검사 소스가 없다: ${source}`);
        continue;
      }
      // JSON은 주석을 지원하지 않는다. npm script 이름 자체를 연결한다.
      if (source.endsWith(".json")) {
        if (!code.includes(`"${name}"`)) problems.push(`${id}: script 이름이 없다`);
      } else if (!new RegExp(`\\brule-${row.number}\\b`).test(code)) {
        problems.push(`${id}: 검사 코드가 번호를 참조하지 않는다: ${source}`);
      }
      if (source === ARCHITECTURE) {
        const declaration = new RegExp(`/\\*\\* rule-${row.number}: [^*]*\\*/\\s+public static final ArchRule ${name}\\b`);
        if (!declaration.test(code)) problems.push(`${id}: ArchRule 선언과 번호가 다르다`);
      }
      if (source.endsWith("checkstyle.xml") && !code.includes(`"${name}"`)) problems.push(`${id}: Checkstyle 이름이 없다`);
      if (source.endsWith("eslint.config.mjs") && !code.includes(`"${name}"`)) problems.push(`${id}: ESLint 이름이 없다`);
    }
  }
  for (const file of files.keys()) {
    const match = /^rule-([1-9]\d*)\.md$/.exec(file);
    if (!match || !numbers.has(Number(match[1]))) problems.push(`${file}: 목록에 없는 파일`);
  }
  return problems;
}

function readSource(file: string): string | undefined {
  try {
    return readFileSync(join(ROOT, file), "utf8");
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
    throw error;
  }
}

test("규칙 목록과 파일의 번호·문장·검사 및 코드 역참조가 같다", () => {
  const files = new Map(readdirSync(join(ROOT, DIRECTORY))
    .filter((file) => file !== "INDEX.md")
    .map((file) => [file, readFileSync(join(ROOT, DIRECTORY, file), "utf8")]));
  const index = readSource(`${DIRECTORY}/INDEX.md`)!;
  assert.deepEqual(problemsOf(index, files, readSource), []);
});

test("모든 ArchRule은 목록에 한 번씩 연결된다", () => {
  const code = readSource(ARCHITECTURE)!;
  const declarations = [...code.matchAll(/public static final ArchRule (\w+)/g)].map((match) => match[1]).sort();
  const links = [...readSource(`${DIRECTORY}/INDEX.md`)!.matchAll(/\[([A-Z][A-Z_0-9]+)\]\(([^)]+)\)/g)]
    .filter((match) => match[2] === `../../${ARCHITECTURE}`).map((match) => match[1]).sort();
  assert.ok(declarations.length > 0);
  assert.deepEqual(links, declarations);
});

const HEADER = "| 번호 | 규칙 한 문장 | 검사 |\n| --- | --- | --- |\n";
const ROW = "| [rule-1](rule-1.md) | 기존 동작을 지킨다. | [리뷰](../../review.txt) |\n";
const BODY = "# rule-1\n\n기존 동작을 지킨다.\n\n검사: [리뷰](../../review.txt)\n\n## 까닭\n\n회귀를 막는다.\n\n## 예외와 검토 범위\n\n리뷰로 판단한다.\n\n## 이 저장소의 사례\n\n기존 구현을 확인한다.\n";
const SOURCE = () => "rule-1";

test("규칙 목록 검사는 정상 연결을 허용한다", () => {
  assert.deepEqual(problemsOf(HEADER + ROW, new Map([["rule-1.md", BODY]]), SOURCE), []);
});

test("중복·누락·잘못된 번호·빈 목록을 거절한다", () => {
  const files = new Map([["rule-1.md", BODY]]);
  assert.match(problemsOf(HEADER + ROW + ROW, files, SOURCE).join("\n"), /번호 중복/);
  assert.match(problemsOf(HEADER + ROW, new Map(), SOURCE).join("\n"), /파일이 없다/);
  assert.match(problemsOf(HEADER, files, SOURCE).join("\n"), /비어 있다/);
  for (const row of [ROW.replaceAll("rule-1", "rule-0"), ROW.replace("(rule-1.md)", "(rule-2.md)")]) {
    assert.match(problemsOf(HEADER + row, files, SOURCE).join("\n"), /행 형식/);
  }
  files.set("rule-2.md", BODY);
  assert.match(problemsOf(HEADER + ROW, files, SOURCE).join("\n"), /목록에 없는 파일/);
});

test("문장·검사·까닭이 어긋나거나 검사 역참조가 없으면 거절한다", () => {
  for (const [body, message] of [
    [BODY.replace("기존 동작을 지킨다.", "다른 동작이다."), /규칙 문장/],
    [BODY.replace("검사: [리뷰]", "검사: [다른 검사]"), /검사 연결/],
    [BODY.replace("회귀를 막는다.", ""), /까닭이 비어/],
  ] as const) {
    assert.match(problemsOf(HEADER + ROW, new Map([["rule-1.md", body]]), SOURCE).join("\n"), message);
  }
  const files = new Map([["rule-1.md", BODY]]);
  assert.match(problemsOf(HEADER + ROW, files, () => undefined).join("\n"), /검사 소스가 없다/);
  assert.match(problemsOf(HEADER + ROW, files, () => "rule-10").join("\n"), /번호를 참조하지 않는다/);
});

test("검사 링크의 이름·경로와 본문의 끊어진 사례 링크를 거절한다", () => {
  for (const checks of ["리뷰", "[리뷰](../../missing.txt)", "[리뷰](../../../outside.txt)"]) {
    const row = ROW.replace("[리뷰](../../review.txt)", checks);
    const body = BODY.replace("[리뷰](../../review.txt)", checks);
    assert.notDeepEqual(problemsOf(HEADER + row, new Map([["rule-1.md", body]]), () => undefined), []);
  }
  const body = BODY + "\n[사례](../../missing.java)\n";
  assert.match(problemsOf(HEADER + ROW, new Map([["rule-1.md", body]]), (file) => file === "review.txt" ? "rule-1" : undefined).join("\n"), /본문 링크가 없다/);
});
