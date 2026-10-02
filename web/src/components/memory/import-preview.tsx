import { Badge } from "@/components/ui/badge";
import type { ImportOutcome, ImportResult } from "@/lib/memory-import-api";
import type { ImportBundle } from "@/lib/memory-import";

const STATUS_TEXT: Record<ImportOutcome["status"], string> = {
  NEW: "새로 가져와요",
  DUPLICATE: "이미 가져왔어요",
  CONFLICT: "같은 이름이 있어요",
  REJECTED: "가져올 수 없어요",
};

const REASON_TEXT: Record<string, string> = {
  DOCUMENT_KEY_TAKEN: "같은 이름의 문서가 이미 있어요",
  TITLE_TAKEN: "같은 제목의 기억이 이미 있어요",
  IDENTITY_HELD: "신원 기록은 아직 가져올 수 없어요",
  UNKNOWN_COLLECTION: "없는 영역이에요",
  CONTENT_TOO_LONG: "내용이 너무 길어요",
};

const KIND_TEXT: Record<string, string> = {
  MEMORY: "기억",
  DOCUMENT: "문서",
  SOURCE: "원문",
};

/** 부딪히거나 가져올 수 없는 항목에만 까닭을 덧붙인다. 새로 가져오는 항목과 이미 가져온 항목은 까닭이 없다. */
function reasonOf(outcome: ImportOutcome): string | null {
  if (outcome.status !== "REJECTED" && outcome.status !== "CONFLICT")
    return null;
  return REASON_TEXT[outcome.reason ?? ""] ?? "항목의 형식이 맞지 않아요";
}

/**
 * 대조 결과를 보인다. 제목과 영역과 종류와 결과만 그리고 본문은 그리지 않는다.
 * 화면을 옆에서 봐도 민감 본문이 보이지 않게 하기 위해서다(ADR-058).
 */
export function ImportPreview({
  bundle,
  result,
  names,
}: {
  bundle: ImportBundle;
  result: ImportResult;
  names: Map<string, string>;
}) {
  return (
    <div className="mb-3">
      <p className="mb-2 text-sm">
        새로 가져와요 {result.newCount}개 · 이미 가져왔어요{" "}
        {result.duplicateCount}개 · 같은 이름이 있어요 {result.conflictCount}개
        · 가져올 수 없어요 {result.rejectedCount}개
      </p>
      <ul className="grid gap-2">
        {result.items.map((outcome) => {
          const item = bundle.items[outcome.index];
          if (!item) return null;
          const reason = reasonOf(outcome);
          return (
            <li
              key={outcome.index}
              className="min-w-0 rounded-md border border-border px-3 py-2 text-sm"
            >
              <div className="flex flex-wrap items-center gap-2">
                <span className="min-w-0 break-words font-medium">
                  {item.title}
                </span>
                {item.sensitive ? <Badge variant="outline">민감</Badge> : null}
              </div>
              <p className="text-muted-foreground">
                {names.get(item.collection) ?? item.collection} ·{" "}
                {KIND_TEXT[item.entryType] ?? item.entryType} ·{" "}
                {STATUS_TEXT[outcome.status]}
              </p>
              {reason ? <p className="text-warning">{reason}</p> : null}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
