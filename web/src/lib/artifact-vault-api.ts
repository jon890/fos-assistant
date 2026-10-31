/** 보관한 결과물의 서버 라우트를 부른다. 실패를 문구로 바꾸는 일은 부르는 쪽이 한다. */

/** 보관한 결과물 하나다. `vaultedAt` 은 ISO-8601 문자열이다. */
export type VaultItem = {
  id: number;
  path: string;
  byteSize: number;
  vaultedAt: string;
};

/** `GET /api/artifact-vault` 의 응답이다. */
export type VaultList = { items: VaultItem[] };

/** 이 화면에서만 뜻이 정해지는 오류 코드의 문구다. */
export const VAULT_ERRORS: Record<string, string> = {
  ARTIFACT_VAULT_UNSUPPORTED:
    "HTML 이나 CSS 만 보관할 수 있어요. 사진이나 다른 파일을 부르는 결과물은 아직 보관하지 못해요.",
  ARTIFACT_VAULT_FULL:
    "보관 공간이 가득 찼어요. 보관한 결과물을 지운 뒤 다시 보관해 주세요.",
  ARTIFACT_VAULT_UNAVAILABLE:
    "지금은 보관하거나 열 수 없어요. 잠시 뒤 다시 시도해 주세요.",
  ARTIFACT_NOT_FOUND: "이미 지웠거나 없는 결과물이에요.",
  ARTIFACT_GONE: "보관 기간이 지나 원본이 지워진 결과물이에요.",
};

export function fetchVaultItems(): Promise<Response> {
  return fetch("/api/artifact-vault", { cache: "no-store" });
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

export function deleteVaultItem(id: number): Promise<Response> {
  return fetch(`/api/artifact-vault/${id}`, { method: "DELETE" });
}

/** 보관본 본문 주소다. `download` 면 내려받기다. */
export function vaultContentUrl(id: number, download = false): string {
  return `/api/artifact-vault/${id}/content${download ? "?download=1" : ""}`;
}
