import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const REPO_ROOT = join(import.meta.dirname, "../../");

/** ADR 을 두는 디렉터리다. 둘 곳의 규칙은 `docs/adr/INDEX.md` 「둘 곳」 이 갖는다. */
const ADR_DIRECTORIES = ["docs/adr", "backend/docs/adr", "web/docs/adr", "hermes/docs/adr"];
const ROOT_DIRECTORY = "docs/adr";

export interface IndexRow {
  /** 링크 글이다. `ADR-006` 이나 `ADR-20261007 / slug` 다. */
  label: string;
  /** INDEX 기준 상대 경로다. */
  target: string;
  /** 루트 목록에만 있다. */
  layer?: string;
  status: string;
  /** `결정 목록` 이나 `보관` 이다. */
  table: string;
}

/** INDEX 의 `## 결정 목록` 과 `## 보관` 표에서 ADR 행을 읽는다. */
export function parseIndex(markdown: string, withLayer: boolean): IndexRow[] {
  const rows: IndexRow[] = [];
  let table = "";
  for (const line of markdown.split("\n")) {
    const heading = /^## (.+)$/.exec(line);
    if (heading) table = heading[1].trim();
    const match = /^\| \[(ADR-[^\]]+)\]\(([^)]+)\) \| (.*) \|$/.exec(line);
    if (!match) continue;
    const cells = match[3].split(" | ");
    const status = cells.at(-1) ?? "";
    const layer = withLayer ? cells.at(-2) : undefined;
    rows.push({ label: match[1], target: match[2], layer, status, table });
  }
  return rows;
}

/** 파일 이름으로 정렬 열쇠를 만든다. 숫자 ADR 이 먼저, 그 뒤에 날짜와 슬러그 순서다. */
export function sortKey(fileName: string): string {
  const numbered = /^ADR-(\d{3})-/.exec(fileName);
  if (numbered) return `0 ${numbered[1]}`;
  const dated = /^ADR-(\d{8})-(.+)\.md$/.exec(fileName);
  if (dated) return `1 ${dated[1]} ${dated[2]}`;
  return `2 ${fileName}`;
}

/** 파일 이름에서 링크 글과 제목 머리가 가져야 할 식별자를 얻는다. */
export function identifierOf(fileName: string): string {
  const numbered = /^(ADR-\d{3})-/.exec(fileName);
  if (numbered) return numbered[1];
  const dated = /^(ADR-\d{8})-(.+)\.md$/.exec(fileName);
  if (dated) return `${dated[1]} / ${dated[2]}`;
  return fileName;
}

function baseName(path: string): string {
  return path.slice(path.lastIndexOf("/") + 1);
}

/** 추적하는 파일과 아직 추적하지 않은 새 파일 가운데 ADR 파일을 모두 얻는다. */
function adrFiles(): string[] {
  return execFileSync("git", ["ls-files", "-co", "--exclude-standard", "*ADR-*.md"], {
    cwd: REPO_ROOT,
    encoding: "utf8",
  })
    .split("\n")
    .filter((file) => file !== "" && existsSync(join(REPO_ROOT, file)));
}

function read(path: string): string {
  return readFileSync(join(REPO_ROOT, path), "utf8");
}

function statusOf(markdown: string): string | undefined {
  return /^- \*\*status\*\*: `([a-z]+)`/m.exec(markdown)?.[1];
}

test("ADR 파일은 정해진 디렉터리와 그 archive 에만 있다", () => {
  const allowed = new Set(ADR_DIRECTORIES.flatMap((dir) => [dir, `${dir}/archive`]));
  const problems = adrFiles().filter(
    (file) => !allowed.has(file.slice(0, file.lastIndexOf("/"))),
  );
  assert.deepEqual(problems, []);
});

for (const directory of ADR_DIRECTORIES) {
  const isRoot = directory === ROOT_DIRECTORY;

  test(`${directory}: 파일과 목록이 같고 status 와 둘 곳이 맞다`, () => {
    const rows = parseIndex(read(`${directory}/INDEX.md`), isRoot);
    const listed = rows.map((row) => `${directory}/${row.target}`).sort();
    const files = adrFiles()
      .filter((file) => file.startsWith(`${directory}/`))
      .filter((file) => !file.slice(directory.length + 1).replace(/^archive\//, "").includes("/"))
      .sort();
    assert.deepEqual(
      {
        "목록에 없는 파일": files.filter((file) => !listed.includes(file)),
        "파일이 없는 행": listed.filter((file) => !files.includes(file)),
      },
      { "목록에 없는 파일": [], "파일이 없는 행": [] },
    );

    const problems: string[] = [];
    for (const row of rows) {
      const file = `${directory}/${row.target}`;
      if (!existsSync(join(REPO_ROOT, file))) continue;
      const status = statusOf(read(file));
      const archived = row.target.startsWith("archive/");
      const retired = status === "superseded" || status === "retired";
      const listedStatus = /^[A-Za-z]+/.exec(row.status)?.[0].toLowerCase();
      if (status !== listedStatus) problems.push(`${row.label}: status ${status}, 목록 ${listedStatus}`);
      if (archived !== retired) problems.push(`${row.label}: status ${status} 인데 archive ${archived}`);
      if (archived !== (row.table === "보관")) problems.push(`${row.label}: ${row.table} 표에 있다`);
      if (row.label !== identifierOf(baseName(row.target))) {
        problems.push(`${row.label}: 링크 글이 파일 이름과 다르다`);
      }
      if (isRoot && !(row.layer === "공통" || row.layer?.includes(","))) {
        problems.push(`${row.label}: 층 ${row.layer} 하나면 그 모듈의 docs/adr/ 에 둔다`);
      }
    }
    assert.deepEqual(problems, []);
  });

  test(`${directory}: 표마다 정렬 규칙을 따른다`, () => {
    const rows = parseIndex(read(`${directory}/INDEX.md`), isRoot);
    for (const table of new Set(rows.map((row) => row.table))) {
      const keys = rows.filter((row) => row.table === table).map((row) => sortKey(baseName(row.target)));
      assert.deepEqual(keys, [...keys].sort(), `${directory} 「${table}」`);
    }
  });
}

test("ADR 의 제목 머리 식별자가 파일 이름과 같다", () => {
  const problems: string[] = [];
  for (const file of adrFiles()) {
    const first = read(file).split("\n").find((line) => line.startsWith("#"));
    const expected = identifierOf(baseName(file));
    if (!first?.match(/^#{1,2} /) || !first.replace(/^#{1,2} /, "").startsWith(`${expected}: `)) {
      problems.push(`${file}: ${first}`);
    }
  }
  assert.deepEqual(problems, []);
});

test("목록 행에서 링크와 층과 상태를 읽는다", () => {
  const [row] = parseIndex(
    "## 결정 목록\n\n| [ADR-001](ADR-001-x.md) | 제목 | backend, hermes | Accepted. 일부 |\n",
    true,
  );
  assert.deepEqual(row, {
    label: "ADR-001",
    target: "ADR-001-x.md",
    layer: "backend, hermes",
    status: "Accepted. 일부",
    table: "결정 목록",
  });
});

test("숫자 ADR 이 날짜 ADR 보다 앞이고 같은 날은 슬러그 순서다", () => {
  const files = ["ADR-20261008-b.md", "ADR-20261008-a.md", "ADR-093-z.md", "ADR-20261007-z.md"];
  assert.deepEqual(
    files.sort((left, right) => sortKey(left).localeCompare(sortKey(right))),
    ["ADR-093-z.md", "ADR-20261007-z.md", "ADR-20261008-a.md", "ADR-20261008-b.md"],
  );
});

test("날짜 ADR 의 식별자는 날짜와 슬러그다", () => {
  assert.equal(identifierOf("ADR-20261009-adr-per-module.md"), "ADR-20261009 / adr-per-module");
  assert.equal(identifierOf("ADR-006-작업-영역.md"), "ADR-006");
});
