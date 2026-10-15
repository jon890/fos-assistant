/** 금융 줄에서 입력을 바꾼 칸 하나다. 없던 값은 null 이다. */
export type FinancialNormalization = {
  field: string;
  before: string | null;
  after: string;
};

/**
 * 금융 승인 줄의 저장 요약이다. `available` 이 거짓이면 저장한 내용을 열지 못한 줄이라 나머지 칸은 null 이고
 * `normalization` 은 비어 있다. `accountSeq` 는 실제로 전송할 계좌 순번이다.
 */
export type FinancialSummary = {
  available: boolean;
  accountSeq: string | null;
  operation: "CREATE" | "CANCEL" | null;
  symbol: string | null;
  market: string | null;
  currency: string | null;
  side: "BUY" | "SELL" | null;
  quantity: string | null;
  orderAmount: string | null;
  price: string | null;
  orderType: "LIMIT" | "MARKET" | null;
  timeInForce: string | null;
  orderId: string | null;
  normalization: FinancialNormalization[];
  /** 생성 주문의 그 통화 상한과 오늘 남은 한도다. 취소와 읽지 못한 줄은 null 이다. */
  limits: FinancialLimitStatus | null;
};

/** 주문 승인 카드에 보일 상한이다. 판정은 승인할 때 서버가 다시 한다. 값은 십진 글이다. */
export type FinancialLimitStatus = {
  currency: string;
  orderLimit: string;
  quantityLimit: string;
  dailyLimit: string;
  spentToday: string;
  remainingToday: string;
};

/**
 * 주문 상한 변경 승인 줄의 요약이다. `previous` 는 요청할 때, `current` 는 지금 적용하는 상한이다.
 * `available` 이 거짓이면 요약의 모양이 계약과 달라 나머지 칸이 비었고, 그 줄은 승인할 수 없다.
 */
export type LimitChangeSummary = {
  available: boolean;
  name: string;
  currency: string | null;
  label: string;
  previous: string;
  requested: string;
  maximum: string;
  current: string;
};
