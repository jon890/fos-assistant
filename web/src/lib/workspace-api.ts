/** 파일 공간의 서버 라우트를 부른다. 실패를 문구로 바꾸는 일은 부르는 쪽이 한다. 응답 모양은 `docs/code-architecture.md` 가 갖는다. */
import { fileUrl, type WorkspaceEntryKind } from "@/lib/workspace-file";

/** 이 공간을 함께 쓰는 에이전트 하나다. `shared` 는 그룹에 공개했는지다. */
export type WorkspaceAgent = { code: string; name: string; shared: boolean };

/** `GET /api/workspace` 의 응답이다. `available` 이 거짓이면 목록과 본문을 읽을 수 없다. */
export type WorkspaceStatus = {
  available: boolean;
  deletable: boolean;
  exists: boolean;
  runningExecutions: number;
  agents: WorkspaceAgent[];
};

/** 목록의 한 줄이다. `size` 는 `FILE` 만 채우고, `modifiedAt` 은 ISO-8601 문자열이다. */
export type WorkspaceEntry = {
  name: string;
  kind: WorkspaceEntryKind;
  size: number | null;
  modifiedAt: string;
  readable: boolean;
  openable: boolean;
};

/** `GET /api/workspace/entries` 의 응답이다. */
export type WorkspaceListing = {
  path: string;
  entries: WorkspaceEntry[];
  truncated: boolean;
};

/** `DELETE /api/workspace/entries` 의 응답이다. `entries` 는 지운 항목 수, `bytes` 는 지운 일반 파일의 크기 합이다. */
export type WorkspaceDeletion = {
  kind: WorkspaceEntryKind;
  entries: number;
  bytes: number;
};

export function fetchWorkspaceStatus(): Promise<Response> {
  return fetch("/api/workspace", { cache: "no-store" });
}

export function fetchWorkspaceEntries(path: string): Promise<Response> {
  const query = path === "" ? "" : `?${new URLSearchParams({ path })}`;
  return fetch(`/api/workspace/entries${query}`, { cache: "no-store" });
}

/** 경로 하나를 지운다. 성공하면 본문이 {@link WorkspaceDeletion} 이다. 경로는 주소 조각이 아니라 `path` 인자로 보낸다. */
export function deleteWorkspaceEntry(path: string): Promise<Response> {
  return fetch(`/api/workspace/entries?${new URLSearchParams({ path })}`, {
    method: "DELETE",
  });
}

/** 미리보기 본문을 받는다. 글과 표처럼 화면이 직접 읽어 그리는 종류에 쓴다. */
export function fetchWorkspaceFile(path: string): Promise<Response> {
  return fetch(fileUrl(path), { cache: "no-store" });
}
