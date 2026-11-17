import { readdir, readFile, stat } from "node:fs/promises";
import { join, posix } from "node:path";
import { devDocsRoot } from "./source.ts";

/**
 * 「개발 문서」 의 목록이다. 묶음과 순서는 docs/features/dev-overview/spec.md 의 DECISION4, DECISION12 가 정한다.
 *
 * 목록에 든 경로만 화면이 연다(REQ9). 본문은 메모리에 두지 않고 목록만 둔다.
 */

export type DocGroupKey = "product" | "features" | "decisions" | "rules";

export type DocEntry = {
  /** 저장소 root 기준 경로다. */
  path: string;
  title: string;
  /** 확장자를 뺀 파일 이름이다. 찾기가 맞춰 본다. */
  fileStem: string;
  /** ADR 의 상태 첫 문장이다. */
  status?: string;
  /** 기능의 스펙과 설계다. */
  children?: DocEntry[];
};

export type DocSection = {
  label: string;
  entries: DocEntry[];
  archived?: DocEntry[];
};

export type DocGroup = {
  key: DocGroupKey;
  label: string;
  sections: DocSection[];
};

export type Catalog = { groups: DocGroup[]; paths: Set<string> };

const PRODUCT_PATHS = [
  "docs/prd.md",
  "docs/code-architecture.md",
  "backend/docs/code-architecture.md",
  "web/docs/code-architecture.md",
  "hermes/docs/code-architecture.md",
  "backend/docs/data-schema.md",
  "hermes/docs/hermes-contract.md",
];

const DECISION_INDEXES = [
  { label: "공통", path: "docs/adr/INDEX.md" },
  { label: "backend", path: "backend/docs/adr/INDEX.md" },
  { label: "web", path: "web/docs/adr/INDEX.md" },
  { label: "hermes", path: "hermes/docs/adr/INDEX.md" },
];

/**
 * 문서 폴더 안의 파일 경로다. 경로는 실행 중 환경 변수에서 오므로 Next.js 파일 추적이 따라가지 않게 한다.
 * 따라가면 web 폴더 전체를 standalone 결과에 담는다(design.md 의 「문서를 담는 자리」).
 */
export function docPath(root: string, ...parts: string[]): string {
  return join(/* turbopackIgnore: true */ root, ...parts);
}

async function exists(file: string): Promise<boolean> {
  try {
    return (await stat(file)).isFile();
  } catch {
    return false;
  }
}

function stemOf(repoPath: string): string {
  return posix.basename(repoPath).replace(/\.md$/i, "");
}

/** 코드 펜스 밖의 첫 `# ` 제목, 없으면 `## ADR-...: ` 머리를 벗긴 첫 `## ` 제목이다. */
export function titleOf(markdown: string, fallback: string): string {
  let fence: string | null = null;
  let second: string | null = null;
  for (const line of markdown.split(/\r?\n/)) {
    const marker = /^\s*(`{3,}|~{3,})/.exec(line)?.[1];
    if (fence) {
      if (marker && marker[0] === fence[0] && marker.length >= fence.length)
        fence = null;
      continue;
    }
    if (marker) {
      fence = marker;
      continue;
    }
    const h1 = /^#\s+(.+?)\s*#*\s*$/.exec(line);
    if (h1) return h1[1];
    if (second === null) {
      const h2 = /^##\s+(.+?)\s*$/.exec(line);
      if (h2) second = h2[1].replace(/^ADR-[^:]+:\s*/, "");
    }
  }
  return second ?? fallback;
}

async function entryOf(root: string, repoPath: string): Promise<DocEntry> {
  const markdown = await readFile(docPath(root, repoPath), "utf8");
  return {
    path: repoPath,
    title: titleOf(markdown, stemOf(repoPath)),
    fileStem: stemOf(repoPath),
  };
}

async function productSection(root: string): Promise<DocSection> {
  const entries: DocEntry[] = [];
  for (const repoPath of PRODUCT_PATHS) {
    if (await exists(docPath(root, repoPath)))
      entries.push(await entryOf(root, repoPath));
  }
  return { label: "제품", entries };
}

async function featureSection(root: string): Promise<DocSection> {
  const dir = docPath(root, "docs/features");
  let names: string[] = [];
  try {
    names = (await readdir(dir)).filter((name) => name.endsWith(".md")).sort();
  } catch {
    return { label: "기능", entries: [] };
  }
  const entries: DocEntry[] = [];
  for (const name of names) {
    const entry = await entryOf(root, `docs/features/${name}`);
    const children: DocEntry[] = [];
    for (const child of ["spec.md", "design.md"]) {
      const childPath = `docs/features/${stemOf(name)}/${child}`;
      if (await exists(docPath(root, childPath)))
        children.push(await entryOf(root, childPath));
    }
    entries.push(children.length > 0 ? { ...entry, children } : entry);
  }
  return { label: "기능", entries };
}

/** 표 줄의 칸이다. 앞뒤 `|` 를 뗀다. */
function cellsOf(line: string): string[] {
  return line
    .trim()
    .replace(/^\|/, "")
    .replace(/\|$/, "")
    .split("|")
    .map((cell) => cell.trim());
}

/** 표 칸의 Markdown 링크와 백틱을 벗긴 글자다. */
export function plainCell(cell: string): string {
  return cell
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/`/g, "")
    .trim();
}

/** 상태 칸의 첫 마침표 앞 글자다. */
export function statusOf(cell: string): string {
  return plainCell(cell).split(".")[0].trim();
}

/**
 * ADR 목록 파일의 표를 읽는다. 첫 칸 링크 대상의 파일 이름이 `ADR-` 로 시작하는 줄만 받는다.
 * `## 보관` 뒤의 줄은 보관으로 나눈다. 링크 대상이 없으면 그 줄을 뺀다.
 */
export async function readDecisionIndex(
  root: string,
  indexPath: string,
): Promise<{ entries: DocEntry[]; archived: DocEntry[] }> {
  const markdown = await readFile(docPath(root, indexPath), "utf8");
  const base = posix.dirname(indexPath);
  const entries: DocEntry[] = [];
  const archived: DocEntry[] = [];
  let inArchive = false;
  for (const line of markdown.split(/\r?\n/)) {
    if (/^##\s+보관/.test(line)) inArchive = true;
    else if (/^##\s/.test(line)) inArchive = false;
    if (!line.trim().startsWith("|")) continue;
    const cells = cellsOf(line);
    const link = /\[([^\]]*)\]\(([^)#\s]+)\)/.exec(cells[0] ?? "");
    if (!link) continue;
    let target: string;
    try {
      target = decodeURIComponent(link[2]);
    } catch {
      console.warn(
        `개발 문서 목록: ${indexPath} 의 링크를 풀지 못했다: ${link[2]}`,
      );
      continue;
    }
    if (!/^ADR-.*\.md$/.test(posix.basename(target))) continue;
    const repoPath = posix.normalize(posix.join(base, target));
    if (!(await exists(docPath(root, repoPath)))) {
      console.warn(
        `개발 문서 목록: ${indexPath} 의 링크 대상이 없다: ${repoPath}`,
      );
      continue;
    }
    const entry: DocEntry = {
      path: repoPath,
      title: plainCell(cells[1] ?? "") || link[1],
      fileStem: stemOf(repoPath),
      status: cells.length > 2 ? statusOf(cells[cells.length - 1]) : undefined,
    };
    (inArchive ? archived : entries).push(entry);
  }
  return { entries, archived };
}

async function decisionSections(root: string): Promise<DocSection[]> {
  const sections: DocSection[] = [];
  for (const { label, path: indexPath } of DECISION_INDEXES) {
    if (!(await exists(docPath(root, indexPath)))) continue;
    const { entries, archived } = await readDecisionIndex(root, indexPath);
    sections.push({
      label,
      entries: [await entryOf(root, indexPath), ...entries],
      ...(archived.length > 0 ? { archived } : {}),
    });
  }
  return sections;
}

async function ruleSection(root: string): Promise<DocSection> {
  const dir = docPath(root, "docs/code-rules");
  const entries: DocEntry[] = [];
  if (await exists(docPath(dir, "INDEX.md")))
    entries.push(await entryOf(root, "docs/code-rules/INDEX.md"));
  let names: string[] = [];
  try {
    names = await readdir(dir);
  } catch {
    return { label: "코드 규칙", entries };
  }
  const rules = names
    .map((name) => /^rule-(\d+)\.md$/.exec(name))
    .filter((match): match is RegExpExecArray => match !== null)
    .sort((a, b) => Number(a[1]) - Number(b[1]));
  for (const match of rules)
    entries.push(await entryOf(root, `docs/code-rules/${match[0]}`));
  return { label: "코드 규칙", entries };
}

function collectPaths(groups: DocGroup[]): Set<string> {
  const paths = new Set<string>();
  const add = (entry: DocEntry) => {
    paths.add(entry.path);
    entry.children?.forEach(add);
  };
  for (const group of groups)
    for (const section of group.sections) {
      section.entries.forEach(add);
      section.archived?.forEach(add);
    }
  return paths;
}

export async function buildCatalog(root: string): Promise<Catalog> {
  const groups: DocGroup[] = [
    { key: "product", label: "제품", sections: [await productSection(root)] },
    { key: "features", label: "기능", sections: [await featureSection(root)] },
    { key: "decisions", label: "결정", sections: await decisionSections(root) },
    { key: "rules", label: "코드 규칙", sections: [await ruleSection(root)] },
  ];
  return { groups, paths: collectPaths(groups) };
}

let cached: { root: string; catalog: Catalog } | null = null;

/** 문서 폴더가 없으면 `null` 이다. 운영에서는 첫 결과를 두고 쓴다. 이미지 안의 문서는 바뀌지 않는다. */
export async function getCatalog(): Promise<Catalog | null> {
  const root = devDocsRoot();
  if (!root) return null;
  try {
    if (!(await stat(docPath(root, "docs"))).isDirectory()) return null;
  } catch {
    return null;
  }
  if (process.env.NODE_ENV === "production" && cached?.root === root)
    return cached.catalog;
  const catalog = await buildCatalog(root);
  cached = { root, catalog };
  return catalog;
}
