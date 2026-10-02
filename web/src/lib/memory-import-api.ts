import { memoryRequest, type MemoryApiResult } from "@/lib/memory-api";
import type { ImportBundle } from "@/lib/memory-import";

export type ImportStatus = "NEW" | "DUPLICATE" | "CONFLICT" | "REJECTED";

/** 항목의 결과다. 올린 항목과는 `index` 로 맞추고 출처는 돌려받지 않는다. */
export type ImportOutcome = {
  index: number;
  status: ImportStatus;
  reason: string | null;
  memoryId: number | null;
};

export type ImportResult = {
  newCount: number;
  duplicateCount: number;
  conflictCount: number;
  rejectedCount: number;
  items: ImportOutcome[];
};

/** 저장하지 않고 항목마다 대조한 결과를 받는다. */
export function previewImport(
  bundle: ImportBundle,
): Promise<MemoryApiResult<ImportResult>> {
  return memoryRequest(
    "/api/memory-imports/preview",
    "가져올 파일을 확인하지 못했어요.",
    { method: "POST", body: bundle },
  );
}

/** 같은 묶음을 보내 새 항목만 저장한다. */
export function commitImport(
  bundle: ImportBundle,
): Promise<MemoryApiResult<ImportResult>> {
  return memoryRequest("/api/memory-imports", "가져오지 못했어요.", {
    method: "POST",
    body: bundle,
  });
}
