/**
 * 대화를 가리키는 공개 식별자(UUID 문자열)인지 본다.
 *
 * <p>대화 표의 번호가 아니다. 번호는 Control Plane 안에만 두고 주소와 API 에는 이 식별자만 쓴다(ADR-025).
 */
const CONVERSATION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isConversationId(value: string): boolean {
  return CONVERSATION_ID.test(value);
}
