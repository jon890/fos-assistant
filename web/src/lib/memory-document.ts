import { memoryRequest, type MemoryApiResult } from "@/lib/memory-api";

export type DocumentResult<T> = MemoryApiResult<T>;

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

export function listDocuments(): Promise<DocumentResult<MemoryDocument[]>> {
  return memoryRequest("/api/memory-documents", "문서 목록을 읽지 못했어요.");
}

export function openDocument(
  id: number,
): Promise<DocumentResult<MemoryDocumentDetail>> {
  return memoryRequest(`/api/memory-documents/${id}`, "문서를 열지 못했어요.");
}

export function createDocument(
  body: NewMemoryDocument,
): Promise<DocumentResult<MemoryDocumentDetail>> {
  return memoryRequest("/api/memory-documents", "문서를 저장하지 못했어요.", {
    method: "POST",
    body,
  });
}

export function updateDocument(
  id: number,
  body: DocumentChange,
): Promise<DocumentResult<MemoryDocumentDetail>> {
  return memoryRequest(
    `/api/memory-documents/${id}`,
    "문서를 고치지 못했어요.",
    {
      method: "PUT",
      body,
    },
  );
}

/** 문서도 `Memory` 의 한 줄이라 지우는 길은 기억과 같다. */
export function deleteDocument(id: number): Promise<DocumentResult<null>> {
  return memoryRequest(`/api/memories/${id}`, "문서를 지우지 못했어요.", {
    method: "DELETE",
  });
}
