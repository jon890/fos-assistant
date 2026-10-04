/**
 * Control Plane 이 밖에 내보이는 공개 식별자(UUID 문자열)인지 본다.
 *
 * <p>표의 번호가 아니다. 번호는 Control Plane 안에만 두고 주소와 API 에는 이 식별자만 쓴다(ADR-025).
 * 대화와 알림이 같은 모양을 쓴다.
 */
const PUBLIC_ID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isPublicId(value: string): boolean {
  return PUBLIC_ID.test(value);
}

/** 대화를 가리키는 공개 식별자인지 본다. */
export function isConversationId(value: string): boolean {
  return isPublicId(value);
}
