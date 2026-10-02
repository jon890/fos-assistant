import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";

const REPO_ROOT = join(import.meta.dirname, "../../");

const SELF = "test/unit/doc-references.test.ts";

const CODE_EXTENSIONS = [
  ".java",
  ".kt",
  ".kts",
  ".sql",
  ".xml",
  ".py",
  ".ts",
  ".tsx",
  ".mjs",
  ".sh",
  ".yml",
  ".txt",
  ".template",
];

/** 문서 경로(`docs/…`)나루트와 하위의 `AGENTS.md` 다. 뒤에 절 이름 「…」 이 바로 올 수 있다. */
const REFERENCE =
  /(docs\/[A-Za-z0-9_./-]+\.md|(?:backend\/|web\/)?AGENTS\.md)(?:[`})]{0,2} ?(?:의)? ?「([^」]+)」)?/g;

export interface DocReference {
  /** 저장소 root 기준 경로다. */
  path: string;
  /** 경로 바로 뒤의 「」 안 글이다. 정규화 전 원문이고, 없으면 `undefined` 다. */
  section?: string;
  /** 1부터 세는 줄 번호다. */
  line: number;
}

/** 헤딩과 절 이름을 같은 모양으로 맞춘다. `{@code X}` 는 `X` 로 바꾸고 백틱과 앞뒤 공백을 지운다. */
export function normalizeHeading(text: string): string {
  return text
    .replace(/\{@code ([^}]*)\}/g, "$1")
    .replaceAll("`", "")
    .trim();
}

/** 텍스트에서 문서 경로와 그 바로 뒤의 절 이름을 찾는다. 줄을 넘어 떨어진 「」 는 잡지 않는다. */
export function findDocReferences(text: string): DocReference[] {
  const found: DocReference[] = [];
  text.split("\n").forEach((lineText, index) => {
    for (const match of lineText.matchAll(REFERENCE)) {
      const path = match[1];
      if (/NNN|<|\*/.test(path)) continue;
      found.push({ path, section: match[2], line: index + 1 });
    }
  });
  return found;
}

/** 코드와 프롬프트 파일 가운데 문서를 가리킬 수 있는 것을 `git ls-files` 에서 고른다. */
function targetFiles(): string[] {
  const output = execFileSync("git", ["ls-files", "-s"], {
    cwd: REPO_ROOT,
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  const files: string[] = [];
  for (const row of output.split("\n")) {
    const match = /^(\d+) \S+ \d+\t(.+)$/.exec(row);
    if (!match) continue;
    const [, mode, file] = match;
    if (mode === "120000") continue;
    if (file === SELF) continue;
    if (file.startsWith("docs/") || file.startsWith("tasks/")) continue;
    if (file.startsWith("backend/src/main/resources/db/migration/")) continue;
    const isCode = CODE_EXTENSIONS.some((extension) => file.endsWith(extension));
    const isOutsideDocsMarkdown = file.endsWith(".md");
    if (isCode || isOutsideDocsMarkdown) files.push(file);
  }
  return files;
}

async function collectReferences(): Promise<
  Array<DocReference & { file: string }>
> {
  const all: Array<DocReference & { file: string }> = [];
  for (const file of targetFiles()) {
    const text = await readFile(join(REPO_ROOT, file), "utf8");
    for (const reference of findDocReferences(text)) {
      all.push({ ...reference, file });
    }
  }
  return all;
}

/** 코드 펜스 밖의 `#` 부터 `######` 까지 모든 헤딩의 글을 나온 순서대로 모은다. */
function headingTexts(markdown: string): string[] {
  const headings: string[] = [];
  let inFence = false;
  for (const line of markdown.split("\n")) {
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence;
      continue;
    }
    if (inFence) continue;
    const match = /^#{1,6}\s+(.*)$/.exec(line);
    if (match) headings.push(match[1].trim());
  }
  return headings;
}

/** 코드 펜스 밖의 `#` 부터 `######` 까지 모든 헤딩을 정규화해 모은다. */
async function headingsOf(path: string): Promise<Set<string>> {
  const text = await readFile(join(REPO_ROOT, path), "utf8");
  return new Set(headingTexts(text).map(normalizeHeading));
}

/** 한 문서에서 두 번 이상 나오는 헤딩의 글을 처음 나온 순서대로 한 번씩 낸다. */
export function duplicateHeadings(markdown: string): string[] {
  const counts = new Map<string, number>();
  for (const heading of headingTexts(markdown)) {
    counts.set(heading, (counts.get(heading) ?? 0) + 1);
  }
  return [...counts].filter(([, count]) => count > 1).map(([heading]) => heading);
}

/** 같은 헤딩이 되풀이되면 안 되는 문서 디렉터리다. 하위 디렉터리는 따로 적는다. */
const UNIQUE_HEADING_DIRECTORIES = [
  "docs",
  "docs/backend",
  "docs/backend/schema",
  "docs/frontend",
];

test("코드와 프롬프트가 가리키는 문서 파일이 있다", async () => {
  const problems: string[] = [];
  for (const reference of await collectReferences()) {
    if (!existsSync(join(REPO_ROOT, reference.path))) {
      problems.push(`${reference.file}:${reference.line}  ${reference.path}`);
    }
  }
  assert.deepEqual(problems, []);
});

test("코드와 프롬프트가 「」 로 가리키는 절이 그 문서에 있다", async () => {
  const problems: string[] = [];
  const headingCache = new Map<string, Set<string>>();
  for (const reference of await collectReferences()) {
    if (reference.section === undefined) continue;
    if (!existsSync(join(REPO_ROOT, reference.path))) continue;
    let headings = headingCache.get(reference.path);
    if (!headings) {
      headings = await headingsOf(reference.path);
      headingCache.set(reference.path, headings);
    }
    if (!headings.has(normalizeHeading(reference.section))) {
      problems.push(
        `${reference.file}:${reference.line}  ${reference.path}  「${reference.section}」`,
      );
    }
  }
  assert.deepEqual(problems, []);
});

test("{@code} 로 감싼 경로에서 경로와 절 이름을 얻는다", () => {
  const [reference] = findDocReferences("{@code docs/connectors.md} 의 「승인」");
  assert.equal(reference.path, "docs/connectors.md");
  assert.equal(reference.section, "승인");
});

test("링크 뒤의 절 이름은 정규화하면 백틱이 없다", () => {
  const [reference] = findDocReferences(
    "[x](../docs/code-architecture.md) 의 「Hermes 쪽 코드 (`hermes/`)」",
  );
  assert.equal(reference.path, "docs/code-architecture.md");
  assert.equal(
    normalizeHeading(reference.section ?? ""),
    "Hermes 쪽 코드 (hermes/)",
  );
});

test("절 이름이 없으면 경로만 얻는다", () => {
  const references = findDocReferences("docs/hermes/profiles.md 를 본다");
  assert.equal(references.length, 1);
  assert.equal(references[0].path, "docs/hermes/profiles.md");
  assert.equal(references[0].section, undefined);
});

test("자리표시자 경로는 건너뛴다", () => {
  assert.deepEqual(findDocReferences("docs/adr/NNN-<슬러그>.md"), []);
});

test("AGENTS.md 는 하위 경로와 절 이름까지 읽는다", () => {
  const [reference] = findDocReferences("{@code backend/AGENTS.md} 「패키지 배치」");
  assert.equal(reference.path, "backend/AGENTS.md");
  assert.equal(reference.section, "패키지 배치");
});

test("한 문서 안에 같은 헤딩이 두 번 나오지 않는다", async () => {
  const problems: string[] = [];
  for (const directory of UNIQUE_HEADING_DIRECTORIES) {
    const names = (await readdir(join(REPO_ROOT, directory))).sort();
    for (const name of names) {
      if (!name.endsWith(".md")) continue;
      const file = `${directory}/${name}`;
      const text = await readFile(join(REPO_ROOT, file), "utf8");
      for (const heading of duplicateHeadings(text)) {
        problems.push(`${file}  「${heading}」`);
      }
    }
  }
  assert.deepEqual(problems, []);
});

test("헤딩이 모두 다르면 되풀이된 헤딩이 없다", () => {
  assert.deepEqual(duplicateHeadings("# 스킬\n\n## 저장\n\n### 갈리는 지점\n"), []);
});

test("같은 헤딩이 둘이면 그 글을 한 번 낸다", () => {
  assert.deepEqual(
    duplicateHeadings("# 스킬\n\n## 갈리는 지점\n\n글\n\n## 갈리는 지점\n"),
    ["갈리는 지점"],
  );
});

test("단계가 달라도 글이 같으면 되풀이된 헤딩이다", () => {
  assert.deepEqual(duplicateHeadings("# 스킬\n\n## 스킬\n"), ["스킬"]);
});

test("코드 펜스 안의 # 줄은 헤딩으로 세지 않는다", () => {
  assert.deepEqual(
    duplicateHeadings("# 검사\n\n```bash\n# 주석\n# 주석\n```\n"),
    [],
  );
});
