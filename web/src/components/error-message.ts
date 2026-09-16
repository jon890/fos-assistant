/** Turns a Control Plane error code into something a family member can act on. */
const MESSAGES: Record<string, string> = {
  HERMES_BINDING_MISSING: "아직 이 계정에 연결된 AI 계정이 없다. 관리자에게 Hermes profile 연결을 요청한다.",
  HERMES_BINDING_DISABLED: "연결된 AI 계정이 사용 중지 상태다.",
  HERMES_PROFILE_KEY_MISSING: "연결된 profile 의 API key 가 서버에 준비돼 있지 않다.",
  HERMES_RUN_TIMEOUT: "응답이 제한 시간 안에 끝나지 않았다. 잠시 뒤에 다시 보낸다.",
  HERMES_UNAVAILABLE: "Hermes 런타임에 연결하지 못했다.",
  HERMES_RUN_FAILED: "실행이 끝나지 못했다. 사용량 화면에서 기록을 확인할 수 있다.",
  UNAUTHENTICATED: "로그인이 필요하다.",
};

export function describeError(code: string, fallback: string): string {
  return MESSAGES[code] ?? fallback;
}
