/** 관리자 「품질」 화면이 주고받는 모양과 서버 라우트 호출이다. 칸의 뜻은 `docs/features/agent-quality/design.md` 의 「관리자 API」 가 갖는다. */

/** 확인 줄의 공개 식별자 형식이다. 서버 라우트가 경로에 넣기 전에 본다. */
export const PUBLIC_ID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 기능 하나의 결과다. 대화와 실행 번호는 실패한 기능에만 30일 동안 있다. */
export type QualityFeatureResult = {
  feature: string;
  status: "PASSED" | "FAILED" | "TIMED_OUT";
  reasonCode: string | null;
  elapsedMs: number;
  conversationPublicId: string | null;
  executionIds: number[];
};

/**
 * 배포 뒤 확인 한 번이다. 도는 동안 `finishedAt` 과 `elapsedMs` 는 null 이다.
 * `environmentError` 는 통과하지 못한 기능이 모두 평가 비서의 Control Plane 연결이 없던 환경 오류라는 뜻이다. 규칙 미달과 섞이면 거짓이다.
 */
export type QualityCheck = {
  publicId: string;
  trigger: "PIPELINE" | "ADMIN";
  status: "RUNNING" | "PASSED" | "FAILED";
  startedAt: string;
  finishedAt: string | null;
  elapsedMs: number | null;
  itemCount: number;
  modelCallCount: number;
  results: QualityFeatureResult[];
  environmentError: boolean;
};

export type QualityCheckPage = {
  items: QualityCheck[];
  nextCursor: string | null;
};

/** 평가 전용 사용자의 준비 상태다. `missing` 이 비었으면 준비됐다. */
export type EvaluationUserStatus = {
  ready: boolean;
  hermesProfile: string | null;
  missing: string[];
};

/** 기능 이름이다. 알림 본문(`DeployCheckRuns.featureLabel`)과 같은 이름을 쓴다. */
export const FEATURE_LABELS: Record<string, string> = {
  SEARCH: "검색과 출처",
  DELEGATION: "위임",
  FILE_PUT: "내 파일 넣기",
  IMAGE: "그림 만들기",
  MEMORY_READ: "기억 읽기",
  TASK_FIRE: "예약 작업 발화",
  PHOTO_INSPECT: "원본 사진 조회",
  TOOL_DISCOVERY: "도구 찾기",
};

/** 통과하지 못한 까닭이다. 모르는 코드는 코드를 그대로 보인다. 관리자 화면이라 내부 코드를 보여도 된다. */
export const REASON_LABELS: Record<string, string> = {
  TIMED_OUT: "기능 마감(10분)을 넘겼어요",
  HERMES_TIMEOUT: "Hermes 를 기다리다 시간이 다 됐어요",
  RUN_FAILED: "실행이 실패했어요",
  APPROVAL_REQUESTED: "승인 카드가 떴어요",
  BUSY: "실행 한도나 대화 잠금에 걸렸어요",
  INTERRUPTED: "서버가 다시 떠 끊겼어요",
  NO_SEARCH: "웹 검색이 끝나지 않았어요",
  NO_SOURCES: "답 끝 출처에 링크가 없어요",
  NO_CHILD: "위임 실행이 없어요",
  CHILD_FAILED: "위임 실행이 실패했어요",
  NOT_DELIVERED: "위임 결과가 대화에 전해지지 않았어요",
  NO_VAULT_ROW: "파일 공간에 파일이 생기지 않았어요",
  NO_IMAGE: "답에 그림이 붙지 않았어요",
  FACT_MISSING: "미리 넣은 기억이 답에 없어요",
  TASK_NOT_SUCCEEDED: "예약 작업 발화가 성공하지 못했어요",
  NO_INSPECT: "원본 사진 조회 도구가 끝나지 않았어요",
  MARKER_MISSING: "사진의 글자가 답에 없어요",
  NO_TOOL:
    "도구 이름을 적지 않은 질문에서 Control Plane 도구를 찾아 부르지 않았어요",
  ENVIRONMENT:
    "평가 비서의 Control Plane 연결이 없었어요. 연결 문제(배포 직후라면 Control Plane 회귀일 수 있음)부터 보세요",
};

/** 평가 사용자 준비에서 빠진 것이다. */
export const MISSING_LABELS: Record<string, string> = {
  USER: "평가 사용자가 없어요",
  PRIMARY_AGENT: "기본 비서가 없어요",
  DELEGATE_AGENT: "위임 대상 에이전트가 없어요",
  MEMORY: "미리 넣는 기억이 없어요",
  POLICY_REGISTRATION_REQUIRED:
    "실행 공간과 사진 정책에 평가 profile 등록이 필요해요",
  TOOLS_UNREADABLE: "기본 비서의 도구 목록을 읽지 못했어요",
};

export function fetchQualityChecks(cursor?: string): Promise<Response> {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : "";
  return fetch(`/api/admin/quality/checks${query}`, { cache: "no-store" });
}

export function startQualityCheck(): Promise<Response> {
  return fetch("/api/admin/quality/checks", { method: "POST" });
}

export function retryQualityCheck(publicId: string): Promise<Response> {
  return fetch(`/api/admin/quality/checks/${publicId}/retry`, {
    method: "POST",
  });
}

export function fetchEvaluationUser(): Promise<Response> {
  return fetch("/api/admin/quality/evaluation-user", { cache: "no-store" });
}

/** `hermesProfile` 은 운영이 실행 공간과 첨부 정책에 등록한 profile 이름이다. 이미 준비된 사용자가 있으면 서버가 무시한다. */
export function prepareEvaluationUser(
  hermesProfile: string,
): Promise<Response> {
  return fetch("/api/admin/quality/evaluation-user", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ hermesProfile: hermesProfile.trim() }),
  });
}

/** 평가 전용 사용자의 실행 트리다. 다른 사용자의 실행이면 404 다. */
export function fetchQualityExecutionTree(
  executionId: number,
): Promise<Response> {
  return fetch(`/api/admin/quality/executions/${executionId}/tree`, {
    cache: "no-store",
  });
}
