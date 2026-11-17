import { readFile } from "node:fs/promises";
import { posix } from "node:path";
import { docPath, getCatalog } from "./catalog.ts";
import { devDocsRoot } from "./source.ts";

/**
 * 주소나 링크에서 온 저장소 경로를 푼다. 퍼센트 인코딩을 풀고 정규화한다.
 * 앞이 `/` 거나 root 밖(`..`)으로 나가면 `null` 이다. ADR 파일 이름 대부분이 한글이라 인코딩을 풀어야 목록과 맞는다.
 */
export function normalizeRepoPath(raw: string | string[]): string | null {
  const joined = Array.isArray(raw) ? raw.join("/") : raw;
  let decoded: string;
  try {
    decoded = decodeURIComponent(joined);
  } catch {
    return null;
  }
  if (decoded === "" || decoded.startsWith("/")) return null;
  const normalized = posix.normalize(decoded);
  if (normalized === ".." || normalized.startsWith("../")) return null;
  return normalized;
}

/** 목록에 든 문서만 읽는다. 목록 밖이면 `null`. 읽기 실패는 던진다. */
export async function readDoc(
  raw: string | string[],
): Promise<{ path: string; markdown: string } | null> {
  const repoPath = normalizeRepoPath(raw);
  const root = devDocsRoot();
  const catalog = await getCatalog();
  if (!repoPath || !root || !catalog?.paths.has(repoPath)) return null;
  return {
    path: repoPath,
    markdown: await readFile(docPath(root, repoPath), "utf8"),
  };
}

/** `## 로드맵` 줄부터 다음 `## ` 앞까지다. 코드 펜스 안의 `## ` 은 경계로 보지 않는다. */
export function extractRoadmap(markdown: string): string | null {
  const lines = markdown.split(/\r?\n/);
  let fence: string | null = null;
  let start = -1;
  for (let i = 0; i < lines.length; i++) {
    const marker = /^\s*(`{3,}|~{3,})/.exec(lines[i])?.[1];
    if (fence) {
      if (marker && marker[0] === fence[0] && marker.length >= fence.length)
        fence = null;
      continue;
    }
    if (marker) {
      fence = marker;
      continue;
    }
    if (/^##\s/.test(lines[i])) {
      if (start >= 0) return lines.slice(start, i).join("\n").trimEnd();
      if (/^##\s+로드맵\s*$/.test(lines[i])) start = i;
    }
  }
  return start >= 0 ? lines.slice(start).join("\n").trimEnd() : null;
}

export async function readRoadmap(): Promise<string | null> {
  const prd = await readDoc("docs/prd.md");
  return prd ? extractRoadmap(prd.markdown) : null;
}
