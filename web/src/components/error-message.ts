/** Turns a Control Plane error code into something a family member can act on. */
const MESSAGES: Record<string, string> = {
  AGENT_NOT_FOUND: "없는 에이전트이거나 이 계정에서 쓸 수 없는 에이전트다.",
  AGENT_DISABLED: "이 에이전트는 지금 쓰지 않도록 되어 있다.",
  AGENT_MODEL_UNKNOWN: "Hermes에서 모델을 읽지 못했다. profile API 상태를 확인한다.",
  HERMES_BINDING_MISSING: "아직 이 계정에 연결된 AI 계정이 없다. 관리자에게 Hermes profile 연결을 요청한다.",
  HERMES_BINDING_DISABLED: "연결된 AI 계정이 사용 중지 상태다.",
  HERMES_PROFILE_KEY_MISSING: "연결된 profile 의 API key 가 서버에 준비돼 있지 않다.",
  HERMES_PROFILE_EXISTS: "그 이름의 Hermes profile 이 이미 있다. 다른 profile 이름을 쓴다.",
  HERMES_PROVISION_FAILED: "Hermes profile 을 만들지 못했다. 만들던 것은 거뒀으니 같은 이름으로 다시 시도할 수 있다.",
  PERSON_EMAIL_TAKEN: "그 이메일은 이미 목록에 있다.",
  PERSON_PROFILE_TAKEN: "그 profile 이름은 이미 쓰고 있다. 다른 이름을 쓴다.",
  PERSON_NOT_FOUND: "목록에 없는 사람이다. 화면을 새로 고쳐 확인한다.",
  HERMES_RUN_TIMEOUT: "응답이 제한 시간 안에 끝나지 않았다. 잠시 뒤에 다시 보낸다.",
  HERMES_UNAVAILABLE: "Hermes 런타임에 연결하지 못했다.",
  HERMES_BUSY: "지금 붐빈다. 잠시 뒤에 다시 보낸다.",
  HERMES_RUN_FAILED: "실행이 끝나지 못했다. 사용량 화면에서 기록을 확인할 수 있다.",
  EXECUTION_NOT_RUNNING: "이미 끝난 답이다.",
  STREAM_INTERRUPTED: "응답 연결이 끊겼다. 실행은 계속될 수 있으니 잠시 뒤 대화 이력을 다시 확인한다.",
  CONVERSATION_NOT_FOUND: "대화를 찾지 못했다. 대화 목록으로 돌아가 다시 골라 주세요.",
  MESSAGE_NOT_LATEST: "그 사이 대화가 바뀌었다. 최신 대화를 다시 불러왔다.",
  CONVERSATION_BUSY: "아직 답을 만드는 중이다. 끝난 뒤에 다시 누른다.",
  UNAUTHENTICATED: "로그인이 필요하다.",
  PERSONA_STALE: "그 사이 다른 사람이 이 성격을 고쳤다. 최신 본문을 다시 불러왔다.",
  ATTACHMENT_GONE: "보관 기간이 지나 볼 수 없습니다.",
};

export function describeError(code: string, fallback: string): string {
  return MESSAGES[code] ?? fallback;
}
