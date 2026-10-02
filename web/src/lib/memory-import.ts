/** 가져올 파일(묶음)의 모양과 읽는 규칙이다(ADR-058). 순수 함수만 둔다. 요청과 화면은 모른다. */

export type ImportBundleItem = {
  sourceRef: string;
  sourceDate: string | null;
  collection: string;
  entryType: string;
  documentKey: string | null;
  title: string;
  content: string;
  sensitive: boolean;
  retrieval: string;
};

export type ImportBundle = { schemaVersion: 1; items: ImportBundleItem[] };

export type ParsedBundle =
  | { ok: true; bundle: ImportBundle }
  | { ok: false; reason: "NOT_JSON" | "WRONG_SHAPE" | "TOO_MANY" | "EMPTY" };

export const MAX_IMPORT_ITEMS = 100;
export const MAX_IMPORT_BYTES = 2 * 1024 * 1024;

/** 가져오기가 끝나면 기억과 문서 절이 목록을 다시 읽게 알리는 창 이벤트다. */
export const MEMORY_IMPORTED_EVENT = "memory-imported";

function text(value: unknown): value is string {
  return typeof value === "string";
}

function optionalText(value: unknown): string | null {
  return text(value) ? value : null;
}

/**
 * 묶음 파일의 글을 읽는다. 통과하면 화면이 쓰는 칸만 골라 담고 `createdAt` 같은 다른 칸은 버린다.
 * 칸의 값이 맞는지는 Control Plane 이 항목마다 판정하므로 여기서는 모양만 본다.
 */
export function parseBundle(raw: string): ParsedBundle {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return { ok: false, reason: "NOT_JSON" };
  }
  if (value === null || typeof value !== "object") {
    return { ok: false, reason: "WRONG_SHAPE" };
  }
  const candidate = value as { schemaVersion?: unknown; items?: unknown };
  if (candidate.schemaVersion !== 1 || !Array.isArray(candidate.items)) {
    return { ok: false, reason: "WRONG_SHAPE" };
  }
  if (candidate.items.length === 0) return { ok: false, reason: "EMPTY" };
  if (candidate.items.length > MAX_IMPORT_ITEMS) {
    return { ok: false, reason: "TOO_MANY" };
  }
  const items: ImportBundleItem[] = [];
  for (const entry of candidate.items as unknown[]) {
    if (entry === null || typeof entry !== "object") {
      return { ok: false, reason: "WRONG_SHAPE" };
    }
    const item = entry as Record<string, unknown>;
    if (!text(item.sourceRef) || !text(item.title) || !text(item.content)) {
      return { ok: false, reason: "WRONG_SHAPE" };
    }
    items.push({
      sourceRef: item.sourceRef,
      sourceDate: optionalText(item.sourceDate),
      collection: text(item.collection) ? item.collection : "",
      entryType: text(item.entryType) ? item.entryType : "",
      documentKey: optionalText(item.documentKey),
      title: item.title,
      content: item.content,
      sensitive: item.sensitive === true,
      retrieval: text(item.retrieval) ? item.retrieval : "",
    });
  }
  return { ok: true, bundle: { schemaVersion: 1, items } };
}
