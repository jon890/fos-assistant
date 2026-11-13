import { Notice } from "@/components/ui/notice";
import type { CheckoutSummary } from "@/lib/checkout-summary";

const WON = new Intl.NumberFormat("ko-KR");
const won = (value: number | null) =>
  value === null ? "" : `${WON.format(value)}원`;

function Row({
  label,
  value,
  testId,
}: {
  label: string;
  value: string;
  testId?: string;
}) {
  return (
    <div className="min-w-0" data-testid={testId}>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="whitespace-pre-wrap break-all">{value}</dd>
    </div>
  );
}

/**
 * 주문서 준비 승인 카드의 본문이다(ADR-20261113 / purchase-checkout). 모델이 낸 인자가 아니라 Control Plane 이 저장해 검증한 요약을
 * 글자 그대로 보인다. 배송지와 결제 수단 칸은 없다(spec DECISION8). 배송비는 무료 조건을 반영한 기본 배송비라 지역 추가 배송비가
 * 있든 없든 「지역 추가 배송비 별도」 를 적는다(spec DECISION29). 오늘 남은 구매 한도는 읽을 때 계산한 값이고 승인할 때 서버가 다시 본다.
 * 요약을 열지 못한 줄은 부른 쪽이 이 블록을 그리지 않는다.
 */
export function CheckoutSummaryView({ summary }: { summary: CheckoutSummary }) {
  const over =
    summary.remainingTodayKrw !== null &&
    summary.expectedTotalKrw !== null &&
    summary.expectedTotalKrw > summary.remainingTodayKrw;
  return (
    <div data-testid="approval-checkout" className="flex flex-col gap-2">
      <dl className="grid gap-2 rounded-md bg-muted px-3 py-2 text-sm sm:grid-cols-2">
        <Row label="상품" value={summary.title ?? ""} />
        <Row label="판매처" value={summary.store ?? ""} />
        {summary.options.map((option, index) => (
          <Row
            key={`${option.name}:${index}`}
            label={`옵션 · ${option.name}`}
            value={option.value}
          />
        ))}
        <Row label="수량" value={`${summary.quantity ?? ""}개`} />
        <Row label="상품 금액" value={won(summary.itemAmountKrw)} />
        <Row
          label="배송비"
          value={`${won(summary.shippingFeeKrw)} (지역 추가 배송비 별도)`}
          testId="approval-checkout-shipping"
        />
        <Row
          label="예상 합계"
          value={won(summary.expectedTotalKrw)}
          testId="approval-checkout-total"
        />
        <Row
          label="오늘 남은 구매 한도"
          value={
            summary.remainingTodayKrw === null
              ? "읽지 못했어요"
              : won(Math.max(0, summary.remainingTodayKrw))
          }
          testId="approval-checkout-remaining"
        />
      </dl>
      {summary.url ? (
        <a
          href={summary.url}
          target="_blank"
          rel="noopener noreferrer"
          className="self-start text-sm underline underline-offset-4 hover:text-foreground-soft"
        >
          상품 화면 보기
        </a>
      ) : null}
      <p className="text-xs text-muted-foreground">
        승인하면 「내 브라우저」 에서 주문서까지 채워요. 결제는 주문서 탭에서
        직접 해 주세요. 주문서의 금액이 예상 합계보다 크면 넘기지 않고 알려
        드려요.
      </p>
      {over ? (
        <Notice variant="warning" role="status">
          이 주문은 오늘 남은 구매 한도를 넘어요. 승인하면 거절될 거예요.
        </Notice>
      ) : null}
    </div>
  );
}
