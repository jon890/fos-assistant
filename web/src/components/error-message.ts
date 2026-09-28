/** Control Plane 의 오류 코드를 사용자가 행동할 수 있는 문구로 바꾼다. */
const MESSAGES: Record<string, string> = {
  AGENT_NOT_FOUND: "에이전트가 없거나 이 계정에서 사용할 수 없어요.",
  AGENT_DISABLED: "이 에이전트는 지금 사용할 수 없어요.",
  AGENT_MODEL_UNKNOWN: "모델 목록을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  HERMES_BINDING_MISSING: "사용할 수 있는 AI 계정이 없어요. 관리자에게 문의해 주세요.",
  HERMES_BINDING_DISABLED: "연결된 AI 계정이 사용 중지 상태예요.",
  HERMES_PROFILE_KEY_MISSING: "이 에이전트를 사용할 수 없어요. 관리자에게 문의해 주세요.",
  HERMES_PROFILE_EXISTS: "이미 사용 중인 이름이에요. 다른 이름을 입력해 주세요.",
  HERMES_PROVISION_FAILED: "사용자를 추가하지 못했어요. 다시 시도해 주세요.",
  PERSON_EMAIL_TAKEN: "이 이메일은 이미 등록되어 있어요.",
  PERSON_PROFILE_TAKEN: "이미 사용 중인 이름이에요. 다른 이름을 입력해 주세요.",
  PERSON_NOT_FOUND: "등록되지 않은 사용자예요. 화면을 새로고침해 확인해 주세요.",
  HERMES_RUN_TIMEOUT: "응답이 제한 시간 안에 끝나지 않았어요. 잠시 뒤 다시 보내 주세요.",
  HERMES_UNAVAILABLE: "연결할 수 없어요. 잠시 뒤 다시 시도해 주세요.",
  HERMES_BUSY: "지금 요청이 많아요. 잠시 뒤 다시 보내 주세요.",
  HERMES_RUN_FAILED: "실행을 마치지 못했어요. 사용량 화면에서 기록을 확인해 주세요.",
  EXECUTION_NOT_RUNNING: "이미 끝난 답이에요.",
  STREAM_INTERRUPTED: "응답 연결이 끊겼어요. 실행은 계속될 수 있으니 잠시 뒤 대화 이력을 다시 확인해 주세요.",
  CONVERSATION_NOT_FOUND: "대화를 찾지 못했어요. 대화 목록으로 돌아가 다시 골라 주세요.",
  MESSAGE_NOT_LATEST: "그사이 대화가 바뀌었어요. 최신 대화를 다시 불러왔어요.",
  CONVERSATION_BUSY: "아직 답을 만들고 있어요. 답이 끝난 뒤 다시 눌러 주세요.",
  UNAUTHENTICATED: "로그인이 필요해요.",
  PERSONA_STALE: "그사이 다른 사용자가 이 성격을 고쳤어요. 최신 본문을 다시 불러왔어요.",
  ATTACHMENT_GONE: "보관 기간이 지나 볼 수 없어요.",
};

export function describeError(code: string, fallback: string): string {
  return MESSAGES[code] ?? fallback;
}
