// 기존 개인 지식 저장소의 이관(ADR-058)에서 쓰는 순수 함수와 타입이다.
// 파일을 읽고 쓰지 않는다. 입출력은 decisions.ts 와 bundle.ts 가 맡는다.

export type Decision = "PENDING" | "IMPORT" | "SKIP";
export type EntryType = "MEMORY" | "DOCUMENT" | "SOURCE";
export type Retrieval = "ALWAYS" | "SEARCH" | "ARCHIVE";

export type DecisionItem = {
  id: string; // `${namespace}/${source_ref}`
  classification: string;
  verdict: string;
  hold: string | null;
  decision: Decision;
  collection: string;
  entryType: EntryType | null;
  documentKey: string | null;
  title: string;
  sensitive: boolean;
  retrieval: Retrieval;
  conflictResolution: "KEEP_THIS" | null;
  holdCleared: boolean;
};

export type DecisionFile = {
  schemaVersion: 1;
  reportGeneratedAt: string;
  items: DecisionItem[];
};

export type BundleItem = {
  sourceRef: string;
  sourceDate: string | null;
  collection: string;
  entryType: EntryType;
  documentKey: string | null;
  title: string;
  content: string;
  sensitive: boolean;
  retrieval: Retrieval;
};

export type Bundle = { schemaVersion: 1; createdAt: string; items: BundleItem[] };

// 분석기 보고서에서 읽는 칸만 적는다. 여기 없는 칸은 읽지 않는다.
export type ReportItem = {
  namespace: string;
  source_ref: string;
  source_date?: string | null;
  title: string;
  collection: string;
  entry_type?: EntryType | null;
  retrieval: Retrieval;
  sensitivity: "NORMAL" | "SENSITIVE";
  document_key?: string | null;
  classification: string;
  verdict: string;
  hold?: string | null;
};

export type Report = {
  schema_version: number;
  generated_at: string;
  items: ReportItem[];
};

export const MAX_ITEMS_PER_BUNDLE = 100;
export const MAX_CONTENT_CHARS = 12000;
// 화면의 웹 라우트가 2MB 를 넘는 요청을 거절한다. 묶음 밖 칸의 여유를 두고 그 아래로 나눈다.
export const MAX_BUNDLE_BYTES = 1900 * 1024;
export const IDENTITY_COLLECTION = "identity";

const COLLECTION_PATTERN = /^[a-z][a-z0-9-]{0,63}$/;
const DOCUMENT_KEY_PATTERN = /^[a-z0-9][a-z0-9-]{0,127}$/;
const MAX_TITLE_CHARS = 200;
const MAX_SOURCE_REF_CHARS = 512;

export function initialDecisions(report: Report): DecisionFile {
  if (report.schema_version !== 1) {
    throw new Error("UNSUPPORTED_REPORT_VERSION");
  }
  return {
    schemaVersion: 1,
    reportGeneratedAt: report.generated_at,
    items: report.items.map((item) => ({
      id: `${item.namespace}/${item.source_ref}`,
      classification: item.classification,
      verdict: item.verdict,
      hold: item.hold ?? null,
      decision: item.classification === "SKIP" ? "SKIP" : "PENDING",
      collection: item.collection,
      entryType: item.entry_type ?? null,
      documentKey: item.document_key ?? null,
      title: item.title,
      sensitive: item.sensitivity === "SENSITIVE",
      retrieval: item.retrieval,
      conflictResolution: null,
      holdCleared: false,
    })),
  };
}

// 맨 앞의 `---` 줄에서 다음 `---` 줄까지를 떼고 앞뒤 빈 줄을 다듬는다. 닫는 줄이 없으면 그대로 낸다.
export function stripFrontmatter(text: string): string {
  const lines = text.split("\n");
  if (lines[0]?.trimEnd() !== "---") {
    return text;
  }
  for (let i = 1; i < lines.length; i += 1) {
    if (lines[i].trimEnd() === "---") {
      return lines
        .slice(i + 1)
        .join("\n")
        .replace(/^\s*\n/, "")
        .trim();
    }
  }
  return text;
}

// 묶을 수 없는 까닭의 코드를 낸다. 위에서부터 보고 먼저 걸린 것으로 답한다.
export function problemOf(item: DecisionItem): string | null {
  if (item.entryType === null) return "ENTRY_TYPE_REQUIRED";
  if (item.verdict === "CONFLICT" && item.conflictResolution !== "KEEP_THIS") {
    return "CONFLICT_UNRESOLVED";
  }
  if (item.hold !== null && !item.holdCleared) return "HOLD_NOT_CLEARED";
  if (!COLLECTION_PATTERN.test(item.collection)) return "COLLECTION_INVALID";
  if (item.entryType === "DOCUMENT" && !DOCUMENT_KEY_PATTERN.test(item.documentKey ?? "")) {
    return "DOCUMENT_KEY_INVALID";
  }
  if (item.title.trim() === "" || item.title.length > MAX_TITLE_CHARS) return "TITLE_INVALID";
  if (item.sensitive && item.retrieval === "ALWAYS") return "SENSITIVE_ALWAYS";
  if (item.collection === IDENTITY_COLLECTION && (!item.sensitive || item.entryType !== "DOCUMENT")) {
    return "IDENTITY_MUST_BE_SENSITIVE_DOCUMENT";
  }
  if (item.id.length > MAX_SOURCE_REF_CHARS) return "SOURCE_REF_TOO_LONG";
  return null;
}

export type Selection = {
  selected: DecisionItem[];
  skipped: number;
  identityHeld: number;
  problems: { id: string; code: string }[];
  pending: number;
};

export function selectForBundle(
  decisions: DecisionFile,
  identity: "exclude" | "only",
): Selection {
  const result: Selection = { selected: [], skipped: 0, identityHeld: 0, problems: [], pending: 0 };
  const documentKeys = new Set<string>();
  for (const item of decisions.items) {
    if (item.decision === "PENDING") {
      result.pending += 1;
      continue;
    }
    if (item.decision === "SKIP") {
      result.skipped += 1;
      continue;
    }
    const isIdentity = item.collection === IDENTITY_COLLECTION;
    if (identity === "exclude" && isIdentity) {
      result.identityHeld += 1;
      continue;
    }
    if (identity === "only" && !isIdentity) {
      continue;
    }
    const code = problemOf(item);
    if (code !== null) {
      result.problems.push({ id: item.id, code });
      continue;
    }
    if (item.entryType === "DOCUMENT") {
      const key = `${item.collection}/${item.documentKey}`;
      if (documentKeys.has(key)) {
        result.problems.push({ id: item.id, code: "DOCUMENT_KEY_DUPLICATED" });
        continue;
      }
      documentKeys.add(key);
    }
    result.selected.push(item);
  }
  return result;
}

// 종류가 정하는 retrieval 로 고정한다. MEMORY 는 결정 파일의 값이되 ARCHIVE 면 SEARCH 로 둔다.
export function retrievalFor(item: DecisionItem): Retrieval {
  if (item.entryType === "DOCUMENT") return "SEARCH";
  if (item.entryType === "SOURCE") return "ARCHIVE";
  return item.retrieval === "ARCHIVE" ? "SEARCH" : item.retrieval;
}

export function chunk<T>(items: T[], size: number): T[][] {
  const chunks: T[][] = [];
  for (let i = 0; i < items.length; i += size) {
    chunks.push(items.slice(i, i + size));
  }
  return chunks;
}

// 개수와 직렬화한 크기가 모두 상한 안에 들도록 차례를 지켜 나눈다. 항목 하나가 상한을 넘어도 홀로 한 묶음이 된다.
export function chunkBySize<T>(items: T[], maxItems: number, maxBytes: number): T[][] {
  const chunks: T[][] = [];
  let current: T[] = [];
  let bytes = 0;
  for (const item of items) {
    const size = Buffer.byteLength(JSON.stringify(item));
    if (current.length > 0 && (current.length >= maxItems || bytes + size > maxBytes)) {
      chunks.push(current);
      current = [];
      bytes = 0;
    }
    current.push(item);
    bytes += size;
  }
  if (current.length > 0) chunks.push(current);
  return chunks;
}
