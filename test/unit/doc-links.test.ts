import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, lstatSync, readFileSync } from "node:fs";
import { join, posix } from "node:path";
import test from "node:test";
import { blankFences, headingTexts, normalizeHeading } from "./markdown.ts";

const REPO_ROOT = join(import.meta.dirname, "../../");

/** Markdown 링크와, 링크 바로 뒤에 붙은 절 이름 「…」 이다. 코드가 문서를 가리키는 방향은 `doc-references.test.ts` 가 본다. */
const LINK = /\[[^\]]*\]\(([^)\s]+)\)(?:[`})]{0,2} ?(?:의)? ?「([^」]+)」)?/g;

export interface DocLink {
  /** 저장소 root 기준으로 푼 대상 경로다. 앵커는 뺐다. */
  target: string;
  section?: string;
  line: number;
}

/** 한 문서의 상대 링크를 찾는다. 코드 펜스와 백틱 안, 바깥 주소, 같은 문서 안의 앵커는 건너뛴다. */
export function findLinks(markdown: string, file: string): DocLink[] {
  const links: DocLink[] = [];
  blankFences(markdown).forEach((lineText, index) => {
    const prose = lineText.replace(/`[^`]*`/g, (code) => " ".repeat(code.length));
    for (const match of prose.matchAll(LINK)) {
      const [path] = match[1].split("#");
      if (path === "" || /^[a-z][a-z0-9+.-]*:/i.test(path) || path.startsWith("<")) continue;
      const target = posix.normalize(posix.join(posix.dirname(file), decodeURI(path)));
      const section = match[2] === undefined ? undefined : lineText.slice(match.index).match(/「([^」]+)」/)?.[1];
      links.push({ target, section, line: index + 1 });
    }
  });
  return links;
}

/** 추적하는 Markdown 과 아직 추적하지 않은 새 Markdown 이다. 심볼릭 링크(`CLAUDE.md`)는 뺀다. `-z` 라 한글 이름이 따옴표로 감싸이지 않는다. */
function markdownFiles(): string[] {
  return execFileSync("git", ["ls-files", "-z", "-co", "--exclude-standard", "*.md"], {
    cwd: REPO_ROOT,
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  })
    .split("\0")
    .filter((file) => file !== "" && existsSync(join(REPO_ROOT, file)))
    .filter((file) => !lstatSync(join(REPO_ROOT, file)).isSymbolicLink());
}

function read(path: string): string {
  return readFileSync(join(REPO_ROOT, path), "utf8");
}

/** 절로 가리킬 수 있는 이름이다. 헤딩과, ADR 이 절처럼 쓰는 `- **감당할 것**:` 같은 굵은 항목 이름이다. */
export function sectionNames(markdown: string): Set<string> {
  const names = headingTexts(markdown).map(normalizeHeading);
  for (const line of blankFences(markdown)) {
    const label = /^\s*(?:[-*] )?\*\*([^*]+)\*\*/.exec(line);
    if (label) names.push(normalizeHeading(label[1].replace(/[:.]$/, "")));
  }
  return new Set(names);
}

/** ADR 은 「」 로 다른 결정의 문장을 인용한다. 절 이름이 아니라서 ADR 디렉터리 안의 「」 는 확인하지 않는다. */
function quotesClaims(file: string): boolean {
  return /(^|\/)adr\//.test(file);
}

test("문서의 상대 링크가 가리키는 파일이 있고 「」 로 적은 절이 그 문서에 있다", () => {
  const problems: string[] = [];
  const headings = new Map<string, Set<string>>();
  for (const file of markdownFiles()) {
    for (const link of findLinks(read(file), file)) {
      const where = `${file}:${link.line}`;
      if (link.target.startsWith("../") || !existsSync(join(REPO_ROOT, link.target))) {
        problems.push(`${where}  ${link.target}`);
        continue;
      }
      if (link.section === undefined || !link.target.endsWith(".md") || quotesClaims(file)) continue;
      let set = headings.get(link.target);
      if (!set) {
        set = sectionNames(read(link.target));
        headings.set(link.target, set);
      }
      if (!set.has(normalizeHeading(link.section))) {
        problems.push(`${where}  ${link.target}  「${link.section}」`);
      }
    }
  }
  assert.deepEqual(problems, []);
});

test("링크는 그 문서 자리 기준으로 풀고 앵커는 뺀다", () => {
  assert.deepEqual(findLinks("[a](../adr/ADR-001-x.md#결정) 의 「결정」", "guide/backend/x.md"), [
    { target: "guide/adr/ADR-001-x.md", section: "결정", line: 1 },
  ]);
});

test("헤딩과 굵은 항목 이름을 절로 읽는다", () => {
  assert.deepEqual(
    [...sectionNames("## 결정\n\n- **감당할 것**: 글\n**대안 기각.** 글\n")],
    ["결정", "감당할 것", "대안 기각"],
  );
});

test("백틱 안과 코드 펜스 안, 바깥 주소와 앵커는 건너뛴다", () => {
  const markdown = "`[a](no.md)`\n```\n[b](no.md)\n```\n[c](https://example.com) [d](#절)";
  assert.deepEqual(findLinks(markdown, "README.md"), []);
});
