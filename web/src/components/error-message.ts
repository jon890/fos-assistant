/** Control Plane 의 오류 코드를 사용자가 행동할 수 있는 문구로 바꾼다. */
const MESSAGES: Record<string, string> = {
  AGENT_NOT_FOUND: "에이전트가 없거나 이 계정에서 사용할 수 없어요.",
  AGENT_DISABLED: "이 에이전트는 지금 사용할 수 없어요.",
  HERMES_BINDING_MISSING:
    "사용할 수 있는 AI 계정이 없어요. 관리자에게 문의해 주세요.",
  HERMES_BINDING_DISABLED: "연결된 AI 계정이 사용 중지 상태예요.",
  HERMES_PROFILE_KEY_MISSING:
    "이 에이전트를 사용할 수 없어요. 관리자에게 문의해 주세요.",
  HERMES_PROFILE_EXISTS:
    "이미 사용 중인 이름이에요. 다른 이름을 입력해 주세요.",
  HERMES_PROVISION_FAILED: "사용자를 추가하지 못했어요. 다시 시도해 주세요.",
  PERSON_EMAIL_TAKEN: "이 이메일은 이미 등록되어 있어요.",
  PERSON_PROFILE_TAKEN: "이미 사용 중인 이름이에요. 다른 이름을 입력해 주세요.",
  PERSON_NOT_FOUND:
    "등록되지 않은 사용자예요. 화면을 새로고침해 확인해 주세요.",
  HERMES_RUN_TIMEOUT:
    "응답이 제한 시간 안에 끝나지 않았어요. 잠시 뒤 다시 보내 주세요.",
  HERMES_UNAVAILABLE: "연결할 수 없어요. 잠시 뒤 다시 시도해 주세요.",
  HERMES_BUSY: "지금 요청이 많아요. 잠시 뒤 다시 보내 주세요.",
  HERMES_RUN_FAILED:
    "실행을 마치지 못했어요. 사용량 화면에서 기록을 확인해 주세요.",
  PROVIDER_BLOCKED:
    "이 모델은 지금 쓸 수 없어요. 다른 모델을 골라 다시 보내 주세요.",
  MODEL_HIDDEN:
    "이 모델은 지금 쓸 수 없어요. 입력창의 설정에서 다른 모델을 골라 주세요.",
  EXECUTION_NOT_RUNNING: "이미 끝난 답이에요.",
  STREAM_INTERRUPTED:
    "응답 연결이 끊겼어요. 실행은 계속될 수 있으니 잠시 뒤 대화 이력을 다시 확인해 주세요.",
  CONVERSATION_NOT_FOUND:
    "대화를 찾지 못했어요. 대화 목록으로 돌아가 다시 골라 주세요.",
  MESSAGE_NOT_LATEST: "그사이 대화가 바뀌었어요. 최신 대화를 다시 불러왔어요.",
  CONVERSATION_BUSY: "아직 답을 만들고 있어요. 답이 끝난 뒤 다시 눌러 주세요.",
  USER_BUSY:
    "진행 중인 작업이 많아요. 진행 중인 작업이 끝난 뒤 다시 보내 주세요.",
  PENDING_QUEUE_FULL:
    "대기 중인 메시지가 가득 찼어요. 답이 끝난 뒤 보내 주세요.",
  PENDING_MESSAGE_NOT_FOUND: "이미 보낸 메시지예요.",
  DELIVERY_NOT_FOUND: "다시 전할 결과를 찾지 못했어요.",
  DELIVERY_NOT_RETRYABLE: "지금은 이 결과를 다시 전할 수 없어요.",
  UNAUTHENTICATED: "로그인이 필요해요.",
  ACCESS_REVOKED: "사용이 중지된 계정이에요. 관리자에게 문의해 주세요.",
  PERSONA_STALE:
    "그사이 다른 사용자가 이 성격을 고쳤어요. 최신 본문을 다시 불러왔어요.",
  AGENT_TOOLS_REQUIRE_PRIVATE:
    "그룹 공개 에이전트에는 셸, 파일, 지난 대화 검색 도구를 켤 수 없어요.",
  AGENT_TOOLS_NOT_APPLIED:
    "도구 설정을 적용하지 못했어요. 현재 목록을 다시 읽었어요.",
  AGENT_BUSY: "다른 설정 변경이 끝날 때까지 기다린 뒤 다시 시도해 주세요.",
  SKILL_NAME_TAKEN: "같은 이름의 기본 스킬이 있어요.",
  SKILL_NOT_FOUND:
    "스킬을 찾지 못했어요. 이미 지워졌는지 목록에서 확인해 주세요.",
  SKILL_COMMAND_UNKNOWN:
    "이 스킬을 이 에이전트에서 쓸 수 없어요. 스킬이 꺼졌거나 지워졌는지 확인해 주세요.",
  ATTACHMENT_GONE: "보관 기간이 지나 볼 수 없어요.",
  MEMORY_SENSITIVE_NOT_EDITABLE: "민감한 항목은 여기서 고칠 수 없어요.",
  MEMORY_DOCUMENT_EXISTS:
    "같은 이름의 문서가 이미 있어요. 다른 이름을 입력해 주세요.",
  MEMORY_REVISION_CONFLICT:
    "그사이 문서가 바뀌었어요. 문서를 다시 열어 주세요.",
  MEMORY_ENCRYPTION_UNAVAILABLE:
    "민감한 문서를 지금 저장하거나 열 수 없어요. 관리자에게 문의해 주세요.",
  MEMORY_IMPORT_RETRY:
    "가져오는 사이에 기록이 바뀌었어요. 파일을 다시 올려 주세요.",
  MEMORY_IMPORT_TOO_LARGE: "가져올 파일이 너무 커요. 나눠서 올려 주세요.",
  SERVICE_TOKEN_NOT_FOUND:
    "토큰을 찾지 못했어요. 화면을 새로고침해 확인해 주세요.",
  NOTIFICATION_NOT_FOUND: "이미 지워졌거나 없는 알림이에요.",
  PROACTIVE_CHECK_UNAVAILABLE:
    "지금은 이 에이전트로 살펴볼 수 없어요. 에이전트 화면에서 까닭을 확인해 주세요.",
};

export function describeError(code: string, fallback: string): string {
  return MESSAGES[code] ?? fallback;
}

/**
 * 실패한 응답의 본문을 읽어 사용자에게 보일 문구로 바꾼다.
 *
 * <p>`overrides` 는 그 화면에서만 뜻이 정해지는 코드의 문구다. 여기 없는 코드는 공용 문구를 쓰고, 공용 문구도 없으면
 * 본문의 message 를 그대로 보인다. 본문이 JSON 이 아니거나 code 가 없으면 서버 내부 오류로 본다.
 */
export async function describeFailure(
  response: Response,
  overrides: Record<string, string> = {},
): Promise<string> {
  try {
    const payload = (await response.json()) as {
      code?: string;
      message?: string;
    };
    if (typeof payload.code !== "string")
      throw new Error("code 가 없는 오류 응답");
    return (
      overrides[payload.code] ??
      describeError(
        payload.code,
        payload.message ?? "요청을 처리하지 못했어요.",
      )
    );
  } catch {
    return describeError("INTERNAL_ERROR", "요청을 처리하지 못했어요.");
  }
}

/** 관리 화면에는 연결과 profile 문제의 원인을 구분해 보여준다. */
const ADMIN_MESSAGES: Record<string, string> = {
  HERMES_BINDING_MISSING:
    "이 계정에 연결된 Hermes profile이 없어요. 연결 설정을 확인해 주세요.",
  HERMES_PROFILE_KEY_MISSING:
    "연결된 profile의 API key가 서버에 준비되지 않았어요.",
  HERMES_PROFILE_EXISTS:
    "같은 이름의 Hermes profile이 이미 있어요. 다른 profile 이름을 입력해 주세요.",
  HERMES_PROVISION_FAILED:
    "Hermes profile을 만들지 못했어요. 생성 중 만든 항목은 정리됐으니 다시 시도해 주세요.",
  PERSON_PROFILE_TAKEN:
    "이 profile 이름을 이미 쓰고 있어요. 다른 이름을 입력해 주세요.",
  HERMES_UNAVAILABLE:
    "Hermes 런타임에 연결하지 못했어요. 연결 상태를 확인해 주세요.",
};

export function describeAdminError(code: string, fallback: string): string {
  return ADMIN_MESSAGES[code] ?? describeError(code, fallback);
}
