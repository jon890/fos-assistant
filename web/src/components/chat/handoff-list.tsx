"use client";

import { Button } from "@/components/ui/button";
import { HANDOFF_TEXT, type Handoff } from "@/lib/handoff";

/**
 * 옆 패널의 「할 일」 탭에 그 대화의 화면 넘기기 카드를 보인다. 플러그인이 사람이 해야 하는 화면에서 멈추면 생긴다.
 *
 * <p>「내 브라우저 열기」 는 남긴 탭을 같은 패널의 「내 브라우저」 탭에 띄운다. 「다 했어요」 는 정해 둔 글을 다음 메시지로 보낸다.
 * 새 turn 이 시작되면 서버가 카드를 닫는다. 입력창에 직접 답해도 같다. 읽기와 상태는 `useConversationHandoffs` 가 갖는다.
 */
export function HandoffList({
  handoffs,
  onOpen,
  onDone,
}: {
  handoffs: Handoff[];
  onOpen: (handoff: Handoff) => void;
  onDone: (handoff: Handoff) => void;
}) {
  if (handoffs.length === 0) return null;
  return (
    <div className="flex flex-col gap-2">
      {handoffs.map((handoff) => (
        <HandoffCard
          key={handoff.handoffId}
          handoff={handoff}
          onOpen={() => onOpen(handoff)}
          onDone={() => onDone(handoff)}
        />
      ))}
    </div>
  );
}

function HandoffCard({
  handoff,
  onOpen,
  onDone,
}: {
  handoff: Handoff;
  onOpen: () => void;
  onDone: () => void;
}) {
  const text = HANDOFF_TEXT[handoff.need];
  return (
    <section
      aria-label="화면 넘기기"
      data-testid="handoff-card"
      className="rounded-lg border border-border bg-card p-3 text-sm"
    >
      <p className="font-medium">{text.title}</p>
      <p className="mt-1 text-muted-foreground">
        {handoffLead(handoff)} 마친 뒤 「다 했어요」를 눌러 주세요.
      </p>
      {handoff.need === "PAYMENT" &&
      handoff.finalAmountKrw !== null &&
      handoff.expectedTotalKrw !== null ? (
        <dl data-testid="handoff-amounts" className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
          <dt className="text-muted-foreground">확인한 최종 결제 금액</dt>
          <dd className="font-medium">{won(handoff.finalAmountKrw)}</dd>
          <dt className="text-muted-foreground">승인한 예상 합계</dt>
          <dd>{won(handoff.expectedTotalKrw)}</dd>
        </dl>
      ) : null}
      <div className="mt-2 flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          data-handoff-open={handoff.handoffId}
          onClick={onOpen}
        >
          내 브라우저 열기
        </Button>
        <Button size="sm" onClick={onDone}>
          다 했어요
        </Button>
      </div>
    </section>
  );
}

/** 단계마다 카드 본문의 첫 문장이다. 결제와 탭 확인은 주문서 채우기의 결과 판정이 띄운다. */
function handoffLead(handoff: Handoff): string {
  switch (handoff.need) {
    case "PAYMENT":
      return `「${handoff.connectorTitle}」 주문서까지 채웠고 결제 단추는 누르지 않았어요. 결제는 「내 브라우저」에서 직접 해 주세요.`;
    case "TAB_CHECK":
      return `「${handoff.connectorTitle}」 주문서를 채운 결과를 확인하지 못해 다시 보내지 않았어요. 「내 브라우저」에 열린 탭을 확인해 주세요.`;
    default:
      return `「${handoff.connectorTitle}」에서 사람이 해야 하는 화면을 만나 멈췄어요. 그 탭을 「내 브라우저」에 열어 두었어요.`;
  }
}

const WON = new Intl.NumberFormat("ko-KR");

function won(value: number): string {
  return `${WON.format(value)}원`;
}
