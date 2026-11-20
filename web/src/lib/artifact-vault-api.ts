/** 「내 파일」(보관함)의 서버 라우트를 부른다. 실패를 문구로 바꾸는 일은 부르는 쪽이 한다. */

/** 「내 파일」 의 종류다. */
export type VaultKind = "IMAGE" | "DOCUMENT" | "TABLE";

/**
 * 「내 파일」 의 파일 하나다. `vaultedAt` 은 ISO-8601 문자열이다.
 *
 * 묶음이면 `path` 는 고른 HTML 이고 `byteSize` 는 묶음 전체, `fileCount` 는 그 HTML 을 센 파일 수다.
 */
export type VaultItem = {
  id: number;
  path: string;
  byteSize: number;
  fileCount: number;
  vaultedAt: string;
  /** 비서가 붙인 제목이다. 없으면 null 이고 화면이 파일 이름을 쓴다. 옛 서버 응답에는 없다 */
  title?: string | null;
  /** 한 줄 설명이다. 없으면 null 이다 */
  description?: string | null;
  /** `IMAGE`, `DOCUMENT`, `TABLE` 가운데 하나다. 받는 형식이 아닌 옛 보관본이면 null 이다 */
  kind?: VaultKind | null;
  /** 파일이 나온 대화의 공개 식별자다. 대화를 지웠으면 null 이다 */
  conversationId?: string | null;
  /** 그 대화의 제목이다. 대화를 지웠으면 null 이다 */
  conversationTitle?: string | null;
};

/** `GET /api/artifact-vault` 의 응답이다. 한 쪽과 용량이다. */
export type VaultList = {
  items: VaultItem[];
  /** 다음 쪽을 읽을 때 `before` 로 줄 값이다. 없으면 null 이다 */
  nextCursor: number | null;
  usedBytes: number;
  quotaBytes: number;
  /** 남은 용량이 파일 하나 상한보다 작다 */
  full: boolean;
};

/** 목록 한 쪽의 조건이다. 빈 값은 보내지 않는다. */
export type VaultQuery = {
  kind?: VaultKind | null;
  q?: string;
  conversation?: string | null;
  before?: number | null;
};

/** 이 화면에서만 뜻이 정해지는 오류 코드의 문구다. */
export const VAULT_ERRORS: Record<string, string> = {
  ARTIFACT_VAULT_UNSUPPORTED:
    "보관할 수 없는 결과물이에요. 받지 않는 형식(글꼴 등)이나 외부 주소, data 주소를 부르거나, 부르는 파일이 없거나, 크기 한도(파일 하나 25MB, 묶음 100MB, 1,000개)를 넘었어요. 보관하는 사이 원본이 바뀌었다면 다시 눌러 주세요.",
  ARTIFACT_VAULT_FULL:
    "내 파일 용량이 가득 찼어요. 안 쓰는 파일을 지운 뒤 다시 보관해 주세요.",
  ARTIFACT_VAULT_UNAVAILABLE:
    "지금은 보관하거나 열 수 없어요. 잠시 뒤 다시 시도해 주세요.",
  ARTIFACT_NOT_FOUND: "이미 지웠거나 없는 파일이에요.",
  ARTIFACT_GONE: "보관 기간이 지나 원본이 지워진 결과물이에요.",
};

/** 「내 파일」 목록 한 쪽을 읽는다. 성공하면 본문이 {@link VaultList} 다. */
export function fetchVaultItems(query: VaultQuery = {}): Promise<Response> {
  const params = new URLSearchParams();
  if (query.kind) params.set("kind", query.kind);
  if (query.q && query.q.trim() !== "") params.set("q", query.q.trim());
  if (query.conversation) params.set("conversation", query.conversation);
  if (query.before != null) params.set("before", String(query.before));
  const suffix = params.size > 0 ? `?${params}` : "";
  return fetch(`/api/artifact-vault${suffix}`, { cache: "no-store" });
}

/** 파일 하나를 읽는다. 성공하면 본문이 {@link VaultItem} 이다. */
export function fetchVaultItem(id: number): Promise<Response> {
  return fetch(`/api/artifact-vault/${id}`, { cache: "no-store" });
}

/** 글 파일의 본문을 받는다. 미리보기가 글자로 그린다. */
export function fetchVaultContent(id: number): Promise<Response> {
  return fetch(vaultContentUrl(id), { cache: "no-store" });
}

/** 대화 결과물 하나를 보관한다. 성공하면 본문이 {@link VaultItem} 이다. */
export function vaultArtifact(
  conversationId: string,
  path: string,
): Promise<Response> {
  return fetch("/api/artifact-vault", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ conversationId, path }),
  });
}

/** 결과물 하나의 자동 보관 상태다. `ChatDtos.ArtifactView.autoVault` 와 같은 값이다. 대상이 아니면 null 이다. */
export type AutoVaultState =
  "PENDING" | "RUNNING" | "DONE" | "SKIPPED" | "FAILED";

/** `POST /api/artifact-vault/auto/retry` 의 응답이다. `error` 는 실패일 때의 오류 코드다. */
export type AutoVaultStatus = {
  state: AutoVaultState | null;
  error: string | null;
};

/** 자동 보관이 실패한 결과물을 다시 보관한다. 성공하면 본문이 {@link AutoVaultStatus} 다. */
export function retryAutoVault(
  conversationId: string,
  messageId: number,
  path: string,
): Promise<Response> {
  return fetch("/api/artifact-vault/auto/retry", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ conversationId, messageId, path }),
  });
}

export function deleteVaultItem(id: number): Promise<Response> {
  return fetch(`/api/artifact-vault/${id}`, { method: "DELETE" });
}

/** 보관본 뿌리 파일의 본문 주소다. `download` 면 내려받기다. */
export function vaultContentUrl(id: number, download = false): string {
  return `/api/artifact-vault/${id}/content${download ? "?download=1" : ""}`;
}

/**
 * 보관본 안의 파일 주소다. 묶음 HTML 을 이 주소로 열면 HTML 이 상대 주소로 부르는 사진과 스타일도 같은 보관본 안에서 열린다.
 */
export function vaultFileUrl(id: number, path: string): string {
  return `/api/artifact-vault/${id}/files/${path
    .split("/")
    .map(encodeURIComponent)
    .join("/")}`;
}

/** 묶음 전체를 zip 으로 내려받는 주소다. */
export function vaultArchiveUrl(id: number): string {
  return `/api/artifact-vault/${id}/archive`;
}

/** 사진 보관본인지 경로의 확장자로 가린다. */
export function isVaultImage(path: string): boolean {
  return /\.(png|jpe?g|gif|webp)$/i.test(path);
}
