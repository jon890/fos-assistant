import {
  describeBlocker,
  type ProactiveCheckBlocker,
} from "@/lib/proactive-check";

/**
 * 사건 감지를 막는 까닭이다. 사건 실행의 준비 판정이 낸 까닭에 연결 쪽 까닭을 더한다. 켜 둔 도구는 까닭이 아니다.
 * `toolsets` 는 toolset 이 걸린 까닭일 때만 차 있다.
 */
export type EventWatchBlocker = {
  code:
    | ProactiveCheckBlocker["code"]
    | "EVENT_TOOL_NOT_ALLOWED"
    | "CONNECTION_NOT_READY"
    | "TOOL_GUARD_MISSING"
    | "EVENT_WATCH_ELSEWHERE";
  toolsets: string[];
};

/** 마지막 확인의 결과다. 켜고 아직 확인하지 않았으면 null 이다. */
export type EventWatchResult =
  "NO_NEW" | "WOKE" | "BUSY" | "BLOCKED" | "DAILY_LIMIT" | "FAILED";

/** 에이전트에 붙은 내 연결 하나의 사건 감지다. 계약은 `docs/features/proactive/design.md` 의 「Control Plane API(PR2)」 다. */
export type EventWatch = {
  connectorId: string;
  /** 플러그인 제목이다. */
  title: string;
  /** 항목 이름이다. 예: 「메일」 */
  noun: string;
  enabled: boolean;
  /** 막는 까닭이 없어 지금 켤 수 있다. */
  available: boolean;
  blockers: EventWatchBlocker[];
  timezone: string;
  lastCheckedAt: string | null;
  lastResult: EventWatchResult | null;
  lastNewCount: number;
  dailyLimitReached: boolean;
  /** 연결 문제로 여러 번 연달아 실패해 다시 연결해야 한다. */
  failing: boolean;
};

export function fetchEventWatches(code: string): Promise<Response> {
  return fetch(`/api/agents/${code}/event-watches`, { cache: "no-store" });
}

export function saveEventWatch(
  code: string,
  connectorId: string,
  input: { enabled: boolean; timezone: string },
): Promise<Response> {
  return fetch(`/api/agents/${code}/event-watches/${connectorId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

/** 막는 까닭 하나를 사용자가 할 일이 드러나는 문장으로 바꾼다. */
export function describeEventWatchBlocker(
  blocker: EventWatchBlocker,
  watch: Pick<EventWatch, "title" | "noun">,
): string {
  switch (blocker.code) {
    case "EVENT_TOOL_NOT_ALLOWED":
      return `${watch.title} 플러그인의 ${watch.noun} 목록 보기가 승인 없이 쓸 수 있는 도구가 아니라서 켤 수 없어요.`;
    case "CONNECTION_NOT_READY":
      return `${watch.title} 연결이 아직 준비되지 않았어요. 위 연결 절에서 반영을 마친 뒤 켤 수 있어요.`;
    case "EVENT_WATCH_ELSEWHERE":
      return `같은 ${watch.title} 연결을 다른 에이전트가 이미 살펴보고 있어요. 연결 하나에는 에이전트 하나만 켤 수 있어요.`;
    case "TOOL_GUARD_MISSING":
      return "이 에이전트가 쓰는 실행 환경에 위험한 도구를 막는 장치가 아직 없어서 켤 수 없어요. 관리자가 반영을 마치면 켤 수 있어요.";
    case "READINESS_UNKNOWN":
      return "지금 준비 상태를 확인하지 못했어요. 켜 둔 것은 끌 수 있어요. 다시 켜려면 화면을 새로 열어 주세요.";
    default:
      return describeBlocker({
        code: blocker.code,
        toolsets: blocker.toolsets,
      });
  }
}

/** 마지막 확인이 어떻게 끝났는지를 한 구절로 바꾼다. */
export function describeEventWatchResult(
  watch: Pick<EventWatch, "lastResult" | "lastNewCount" | "noun">,
): string {
  switch (watch.lastResult) {
    case null:
      return "아직 확인하지 않았어요";
    case "NO_NEW":
      return "새로 들어온 것이 없었어요";
    case "WOKE":
      return `새 ${watch.noun} ${watch.lastNewCount}건을 살펴봤어요`;
    case "BUSY":
      return "다른 대화가 돌고 있어 다음 확인으로 미뤘어요";
    case "BLOCKED":
      return "에이전트나 연결 조건이 맞지 않아 살펴보지 못했어요";
    case "DAILY_LIMIT":
      return "오늘 살펴볼 수 있는 횟수를 다 썼어요";
    case "FAILED":
      return `${watch.noun} 목록을 읽지 못했어요`;
  }
}
