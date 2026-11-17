import { githubFileUrl, REPO_URL } from "./source.ts";

/** 문서 안 링크를 어디로 보낼지다(docs/features/dev-overview/spec.md 의 REQ4, DECISION13). */
export type ResolvedLink =
  | { kind: "anchor"; hash: string }
  | { kind: "internal"; href: string }
  | { kind: "github"; href: string }
  | { kind: "external"; href: string }
  | { kind: "blocked" };

/**
 * POSIX 경로를 정규화한다. 브라우저 부품도 이 파일을 쓰므로 `node:path` 를 쓰지 않는다.
 * root 밖으로 나가면 앞에 `..` 가 남는다.
 */
export function normalizePosix(input: string): string {
  const out: string[] = [];
  for (const part of input.split("/")) {
    if (part === "" || part === ".") continue;
    if (part === ".." && out.length > 0 && out[out.length - 1] !== "..")
      out.pop();
    else out.push(part);
  }
  return out.join("/");
}

function dirnameOf(repoPath: string): string {
  const at = repoPath.lastIndexOf("/");
  return at >= 0 ? repoPath.slice(0, at) : "";
}

function safeDecode(value: string): string | null {
  try {
    return decodeURIComponent(value);
  } catch {
    return null;
  }
}

/**
 * 문서 `fromPath` 안의 링크 `href` 를 푼다.
 * 목록(`known`)에 든 문서는 화면 안, 저장소 안이지만 목록 밖(코드, README, 그림)은 같은 커밋의 GitHub,
 * `http(s)` 와 `mailto` 는 그대로, 그 밖의 scheme 은 링크로 두지 않는다.
 */
export function resolveDocLink(
  href: string,
  fromPath: string,
  known: ReadonlySet<string>,
  commit: string | null,
): ResolvedLink {
  if (href.startsWith("#")) return { kind: "anchor", hash: href.slice(1) };
  const scheme = /^([a-z][a-z0-9+.-]*):/i.exec(href)?.[1]?.toLowerCase();
  if (scheme) {
    return ["http", "https", "mailto"].includes(scheme)
      ? { kind: "external", href }
      : { kind: "blocked" };
  }
  if (href.startsWith("//")) return { kind: "external", href: `https:${href}` };
  const hashAt = href.indexOf("#");
  const rawPath = hashAt >= 0 ? href.slice(0, hashAt) : href;
  const hash = hashAt >= 0 ? href.slice(hashAt + 1) : "";
  const decoded = safeDecode(rawPath.split("?")[0]);
  if (decoded === null) return { kind: "blocked" };
  const target = decoded.startsWith("/")
    ? normalizePosix(decoded)
    : normalizePosix(`${dirnameOf(fromPath)}/${decoded}`);
  if (target === "" || target === ".." || target.startsWith("../"))
    return { kind: "github", href: REPO_URL };
  if (known.has(target)) {
    const encoded = target.split("/").map(encodeURIComponent).join("/");
    return {
      kind: "internal",
      href: `/dev-docs/${encoded}${hash ? `#${hash}` : ""}`,
    };
  }
  return {
    kind: "github",
    href: githubFileUrl(target, commit, hash || undefined),
  };
}
