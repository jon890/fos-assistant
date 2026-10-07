import { Badge } from "@/components/ui/badge";
import { formatRelative } from "@/lib/format";
import { memorySourceLabel } from "@/lib/memory-source";
import type { Memory } from "./memory-list";

/** 기억 한 줄의 제목 아래 짧은 표시다. 범위, 남긴 쪽, 바뀐 시각과 주의할 상태를 보인다. */
export function MemoryMeta({
  memory,
  readAt,
}: {
  memory: Memory;
  readAt: string;
}) {
  const changedAt = memory.updatedAt ?? memory.createdAt;
  return (
    <>
      <Badge variant="outline">
        {memory.scope === "GROUP" ? "그룹" : "나에 대해"}
      </Badge>
      <span data-testid="memory-source">{memorySourceLabel(memory)}</span>
      {changedAt ? (
        <span>
          <span aria-hidden>· </span>
          {formatRelative(changedAt, new Date(readAt))}
        </span>
      ) : null}
      {memory.sensitive ? <Badge variant="warning">민감</Badge> : null}
      {memory.omittedFromContext ? (
        <Badge variant="destructive" data-testid="memory-omitted">
          길어서 답에 포함되지 않음
        </Badge>
      ) : null}
    </>
  );
}
