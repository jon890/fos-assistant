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
};
