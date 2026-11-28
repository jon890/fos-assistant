export type FetchLike = (input: string | URL | Request, init?: RequestInit) => Promise<Response>;

/** Hermes가 치환하지 않은 환경 변수는 빈 값으로 읽는다. */
export function configuredValue(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return !trimmed || trimmed.startsWith("${") ? undefined : trimmed;
}

// 사용자에게는 정해진 문구만 보여 주고 서비스 응답 본문과 토큰은 숨긴다.
const messages = {
  CAREER_CONFIG: "프로필 저장소 설정을 확인해 주세요.",
  CAREER_INVALID_RESPONSE:
    "응답 형식을 확인할 수 없습니다. 요청을 반복하지 말고 GitHub 프로필을 다시 읽어 확인해 주세요.",
  CAREER_INVALID_INPUT: "도구 입력을 확인해 주세요.",
  CAREER_GITHUB_NOT_CONFIGURED: "GitHub 연결이 없거나 설정이 틀렸습니다. 연결 설정에서 GitHub token 을 넣거나 GitHub 단추로 연결해 주세요.",
  CAREER_GITHUB_UNAUTHORIZED: "GitHub token 이 거절됐거나 다시 연결해야 합니다. 연결 설정에서 GitHub token 을 다시 넣거나 GitHub 단추로 다시 연결해 주세요.",
  CAREER_GITHUB_FORBIDDEN: "GitHub token 에 프로필 저장소 권한이 없거나 저장소 이름이 틀립니다.",
  CAREER_GITHUB_CONFLICT: "그 사이 프로필 저장소에 다른 커밋이 올라왔습니다. 다시 읽은 뒤 갱신해 주세요.",
  CAREER_GITHUB_STALE_REVIEW:
    "검토한 뒤 프로필 저장소의 기본 branch 나 최신 커밋이 바뀌어 아무것도 쓰지 않았습니다. GitHub 프로필을 다시 읽고 변경안을 맞춘 뒤 새로 승인받아 주세요.",
  CAREER_GITHUB_UNAVAILABLE: "GitHub 이나 연결 서비스가 응답하지 않습니다. 잠시 뒤 다시 시도해 주세요.",
  CAREER_BROWSER_UNREACHABLE:
    "「내 브라우저」 에 닿지 못했습니다. 비서 웹의 「내 브라우저」 가 켜져 있는지 확인해 주세요.",
  CAREER_BROWSER_IN_USE: "「내 브라우저」 를 사람이 직접 쓰고 있습니다. 돌려준 뒤 다시 시도해 주세요.",
  CAREER_WANTED_LOGIN_REQUIRED:
    "원티드 로그인이 필요합니다. 「내 브라우저」 에서 원티드에 로그인한 뒤 이력서를 다시 읽어 주세요.",
  CAREER_WANTED_PAGE_CHANGED:
    "원티드 화면이 예상과 달라 멈췄습니다. 원티드 화면에서 직접 고쳐 주세요.",
  CAREER_WANTED_RESUME_NOT_FOUND: "원티드 이력서를 찾지 못했습니다. 이력서 목록을 다시 읽어 주세요.",
  CAREER_UNKNOWN_TOOL: "지원하지 않는 도구입니다.",
  CAREER_INTERNAL: "커리어 요청을 처리하지 못했습니다. 요청 내용을 확인해 주세요.",
} as const;

export type CareerErrorCode = keyof typeof messages;

export class CareerError extends Error {
  constructor(
    readonly code: CareerErrorCode,
    readonly details?: Record<string, unknown>,
  ) {
    super(messages[code]);
  }
}

export function safeError(error: unknown): { code: string; message: string } {
  if (error instanceof CareerError) return { code: error.code, message: error.message };
  return { code: "CAREER_INTERNAL", message: messages.CAREER_INTERNAL };
}
