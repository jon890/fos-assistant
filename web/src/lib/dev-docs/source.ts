/**
 * 「개발 문서」 화면이 읽는 문서 폴더와 커밋 값이다(docs/features/dev-overview/design.md 의 「문서를 담는 자리」).
 *
 * 둘 다 서버가 요청을 받을 때 환경 변수에서 읽는다. `process.cwd()` 나 번들 위치로 짐작하지 않는다.
 * standalone 서버는 시작할 때 작업 폴더를 바꾸고, 동적 경로는 Next.js 파일 추적을 넓혀 빌드 결과를 키운다.
 * 이 파일은 `fs` 를 쓰지 않아 브라우저 부품도 주소 함수를 import 할 수 있다.
 */

/** 공개 저장소의 주소다. 운영 정보가 아니다. */
export const REPO_URL = "https://github.com/jon890/fos-assistant";

/** 문서 폴더의 root 다. 저장소와 같은 상대 경로로 `docs/`, `backend/docs/` 같은 폴더가 있다. 없으면 `null`. */
export function devDocsRoot(): string | null {
  return process.env.DEV_DOCS_ROOT?.trim() || null;
}

/** 웹 이미지를 만든 커밋이다. 40자리 16진수가 아니면 없는 것으로 본다. */
export function sourceCommit(): string | null {
  const value = process.env.SOURCE_COMMIT ?? "";
  return /^[0-9a-f]{40}$/.test(value) ? value : null;
}

/** 저장소 경로를 같은 커밋(없으면 `main`)의 GitHub 파일 주소로 바꾼다. */
export function githubFileUrl(
  repoPath: string,
  commit: string | null,
  hash?: string,
): string {
  const encoded = repoPath.split("/").map(encodeURIComponent).join("/");
  const anchor = hash ? `#${hash}` : "";
  return `${REPO_URL}/blob/${commit ?? "main"}/${encoded}${anchor}`;
}

export function githubCommitUrl(commit: string): string {
  return `${REPO_URL}/commit/${commit}`;
}
