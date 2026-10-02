import { describeFailure } from "@/components/error-message";

/** 목록의 문서다. 본문은 싣지 않는다. 본문은 사용자가 문서를 열 때 따로 받는다. */
export type MemoryDocument = {
  id: number;
  collection: string;
  documentKey: string;
  title: string;
  sensitive: boolean;
  revision: number;
  updatedAt: string;
};

/** 문서 하나다. 열었을 때만 받으므로 본문은 화면의 상태에서만 산다. */
export type MemoryDocumentDetail = MemoryDocument & { content: string };

export type MemoryCollectionOption = { key: string; displayName: string };

export type NewMemoryDocument = {
  collection: string;
  documentKey: string;
  title: string;
  content: string;
  sensitive: boolean;
};

export type DocumentChange = {
  content: string;
  sensitive: boolean;
  /** 화면이 읽은 판 번호다. 그사이 판이 올랐으면 거절된다. */
  expectedRevision: number;
};

/** 실패하면 사용자에게 보일 문구와 오류 코드를 함께 돌려준다. 코드가 없으면 요청이 닿지 못한 것이다. */
export type DocumentResult<T> =
  { ok: true; data: T } | { ok: false; message: string; code: string | null };

async function failure(response: Response): Promise<DocumentResult<never>> {
  // describeFailure 가 본문을 읽으므로 코드를 먼저 복제본에서 꺼낸다.
  const payload = (await response
    .clone()
    .json()
    .catch(() => null)) as { code?: unknown } | null;
  return {
    ok: false,
    message: await describeFailure(response),
    code: typeof payload?.code === "string" ? payload.code : null,
  };
}

async function request<T>(
  path: string,
  fallback: string,
  init?: { method: string; body?: unknown },
): Promise<DocumentResult<T>> {
  try {
    const response = await fetch(path, {
      method: init?.method ?? "GET",
      headers:
        init?.body === undefined
          ? undefined
          : { "Content-Type": "application/json" },
      body: init?.body === undefined ? undefined : JSON.stringify(init.body),
      cache: "no-store",
    });
    if (!response.ok) return await failure(response);
    // 지우기는 본문 없이 성공한다.
    const text = await response.text();
    return { ok: true, data: (text === "" ? null : JSON.parse(text)) as T };
  } catch {
    return { ok: false, message: fallback, code: null };
  }
}

export function listDocuments(): Promise<DocumentResult<MemoryDocument[]>> {
  return request("/api/memory-documents", "문서 목록을 읽지 못했어요.");
}

export function openDocument(
  id: number,
): Promise<DocumentResult<MemoryDocumentDetail>> {
  return request(`/api/memory-documents/${id}`, "문서를 열지 못했어요.");
}

export function createDocument(
  body: NewMemoryDocument,
): Promise<DocumentResult<MemoryDocumentDetail>> {
  return request("/api/memory-documents", "문서를 저장하지 못했어요.", {
    method: "POST",
    body,
  });
}

export function updateDocument(
  id: number,
  body: DocumentChange,
): Promise<DocumentResult<MemoryDocumentDetail>> {
  return request(`/api/memory-documents/${id}`, "문서를 고치지 못했어요.", {
    method: "PUT",
    body,
  });
}

/** 문서도 `Memory` 의 한 줄이라 지우는 길은 기억과 같다. */
export function deleteDocument(id: number): Promise<DocumentResult<null>> {
  return request(`/api/memories/${id}`, "문서를 지우지 못했어요.", {
    method: "DELETE",
  });
}
