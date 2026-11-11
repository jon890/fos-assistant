/** 브라우저와 서버 라우트가 같이 쓰는 오류 문구다. Control Plane 의 오류 원문은 쓰지 않는다. */
export const CONNECTOR_ACTION_ERROR_MESSAGES: Record<string, string> = {
  CONNECTOR_ACTION_NOT_PENDING: "이미 처리된 요청이에요.",
  CONNECTOR_ACTION_NOT_FOUND: "요청을 찾지 못했어요.",
  FINANCIAL_LIMIT_EXCEEDED:
    "주문 상한에 걸려 승인하지 못했어요. 카드에서 남은 한도를 확인해 주세요.",
  FINANCIAL_LIMIT_ABOVE_MAXIMUM: "최대치보다 큰 값은 정할 수 없어요.",
};

const FALLBACK_MESSAGE =
  "요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.";

export function connectorActionErrorMessage(code: string): string {
  return Object.hasOwn(CONNECTOR_ACTION_ERROR_MESSAGES, code)
    ? CONNECTOR_ACTION_ERROR_MESSAGES[code]
    : FALLBACK_MESSAGE;
}
