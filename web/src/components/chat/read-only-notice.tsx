import Link from "next/link";
import { MessageSquare, Square } from "lucide-react";
import { Button } from "@/components/ui/button";

/** 읽기 전용 대화의 입력창 자리에 보이는 안내다(docs/features/assistant-hub/spec.md 의 REQ5, DECISION4) */
export const READ_ONLY_NOTICE =
  "이 대화는 더 이어 쓸 수 없어요. 비서에게 이어서 물어보세요";

/**
 * 대화의 에이전트가 기본 비서가 아닌 지난 대화는 읽기만 한다. 입력창 대신 안내 한 줄과 비서와 이어지는 대화방(`/`)으로 가는
 * 링크를 보인다. 그 대화에서 예약 turn 이나 결과 전달 turn 이 돌면 멈출 수 있게 중지 단추를 함께 둔다.
 */
export function ReadOnlyNotice({
  canStop,
  onStop,
}: {
  canStop: boolean;
  onStop(): void;
}) {
  return (
    <div className="px-1 pb-3">
      <div
        data-testid="read-only-notice"
        className="mx-auto flex w-full max-w-3xl flex-wrap items-center justify-between gap-2 rounded-2xl border border-input bg-muted/40 px-3 py-2 text-sm"
      >
        <span>{READ_ONLY_NOTICE}</span>
        <span className="flex gap-2">
          {canStop ? (
            <Button size="sm" variant="outline" onClick={onStop}>
              <Square aria-hidden="true" />
              중지
            </Button>
          ) : null}
          <Button asChild size="sm" variant="outline">
            <Link href="/">
              <MessageSquare aria-hidden="true" />
              대화방으로 가기
            </Link>
          </Button>
        </span>
      </div>
    </div>
  );
}
