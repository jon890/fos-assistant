import { toolsetText } from "@/lib/toolset-label";

/** 살펴보기를 시작하지 못하게 막는 까닭이다. `toolsets` 는 `TOOLSETS_NOT_ALLOWED` 일 때만 차 있다. */
export type ProactiveCheckBlocker = {
  code:
    | "DISABLED"
    | "AGENT_NOT_SUPPORTED"
    | "SKILL_MISSING"
    | "TOOLSETS_NOT_ALLOWED"
    | "ISOLATED_EXECUTION_REQUIRED"
    | "READINESS_UNKNOWN";
  toolsets: string[];
};

/** 사용자가 그 에이전트로 연 마지막 살펴보기다. 돌고 있으면 `finishedAt` 이 null 이다. */
export type ProactiveCheckLastCheck = {
  status: "RUNNING" | "SUCCEEDED" | "FAILED" | "STOPPED";
  /** 성공했을 때만 있다. */
  outcome: "FINDINGS" | "NOTHING_NEW" | "INVALID_RESULT" | null;
  /** 결과를 읽지 못한 까닭이다. `outcome` 이 `INVALID_RESULT` 일 때만 있고, 까닭을 남기기 전에 끝난 살펴보기는 null 이다. */
  invalidReason:
    | "EMPTY_ANSWER"
    | "NO_BLOCK"
    | "NOT_JSON"
    | "BAD_VERSION"
    | "BAD_OUTCOME"
    | null;
  /** 모델을 부르지 않고 끝낸 예약 실행의 까닭이다. */
  skippedReason?: "NO_CHANGE" | "UNREAD_REPORT" | "QUIET_HOURS" | null;
  startedAt: string;
  finishedAt: string | null;
};

export type ProactiveCheckStatus = {
  available: boolean;
  blockers: ProactiveCheckBlocker[];
  /** 요청자의 점검 대화다. 아직 없으면 null 이다. */
  conversationId: string | null;
  lastCheck: ProactiveCheckLastCheck | null;
};

/** 매일 깨우기 설정이다. 시각은 해당 시간대의 `HH:mm` 형식이다. */
export type ProactiveCheckSchedule = {
  enabled: boolean;
  time: string;
  timezone: string;
  nextRunAt: string | null;
  /** 매일 깨우기가 마지막으로 연 살펴보기다. */
  lastCheck: ProactiveCheckLastCheck | null;
  /** 이 에이전트가 매일 깨우기를 쓸 수 있는지다. */
  schedulingAvailable: boolean;
  blockers: ProactiveCheckBlocker[];
};

/** 매일 루프 설정이다. `available` 은 설치가 루프를 여는지이고, `snoozedUntil` 이 있으면 그때까지 쉰다. */
export type ProactiveLoopSetting = {
  available: boolean;
  enabled: boolean;
  snoozedUntil: string | null;
};

export function fetchProactiveCheckStatus(code: string): Promise<Response> {
  return fetch(`/api/agents/${code}/proactive-check`, { cache: "no-store" });
}

export function fetchProactiveCheckSchedule(code: string): Promise<Response> {
  return fetch(`/api/agents/${code}/proactive-check/schedule`, {
    cache: "no-store",
  });
}

export function saveProactiveCheckSchedule(
  code: string,
  input: Pick<ProactiveCheckSchedule, "enabled" | "time" | "timezone">,
): Promise<Response> {
  return fetch(`/api/agents/${code}/proactive-check/schedule`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

export function fetchProactiveLoopSetting(code: string): Promise<Response> {
  return fetch(`/api/agents/${code}/proactive-check/loop`, {
    cache: "no-store",
  });
}

export function saveProactiveLoopSetting(
  code: string,
  input: Pick<ProactiveLoopSetting, "enabled" | "snoozedUntil">,
): Promise<Response> {
  return fetch(`/api/agents/${code}/proactive-check/loop`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

/** 살펴보기를 시작한다. 202 면 본문에 점검 대화의 공개 식별자가 있다. */
export function startProactiveCheck(code: string): Promise<Response> {
  return fetch(`/api/agents/${code}/proactive-check/runs`, { method: "POST" });
}

/** 시작 요청에서만 뜻이 정해지는 오류 코드의 문구다. 나머지는 공용 문구를 쓴다. */
export const START_FAILURES: Record<string, string> = {
  USER_BUSY: "진행 중인 작업이 끝난 뒤 다시 눌러 주세요.",
};

/** 막는 까닭 하나를 사용자가 할 일이 드러나는 문장으로 바꾼다. */
export function describeBlocker(blocker: ProactiveCheckBlocker): string {
  switch (blocker.code) {
    case "DISABLED":
      return "지금은 먼저 살펴보기를 쓸 수 없어요.";
    case "READINESS_UNKNOWN":
      return "지금 준비 상태를 확인하지 못했어요. 매일 깨우기는 끌 수 있어요. 다시 켜려면 설정 화면을 새로 열어 주세요.";
    case "AGENT_NOT_SUPPORTED":
      return "이 에이전트는 먼저 살펴보기를 하지 않아요.";
    case "SKILL_MISSING":
      return "이 에이전트에 proactive-check 스킬이 없거나 꺼져 있어요. 에이전트를 준비하는 사람이 그 스킬을 설치하고 켜야 해요.";
    case "TOOLSETS_NOT_ALLOWED": {
      const names = blocker.toolsets
        .map(
          (name) => toolsetText(name, { label: name, description: "" }).label,
        )
        .join(", ");
      return `${names} 도구가 켜져 있어서 살펴볼 수 없어요. 위 도구 절에서 꺼 주세요.`;
    }
    case "ISOLATED_EXECUTION_REQUIRED":
      return "격리된 실행 공간이 준비되기 전에는 매일 깨우기를 켤 수 없어요.";
  }
}

/** 사람이 곁에 없는 매일 깨우기를 막는 까닭이다. */
export function describeScheduleBlocker(
  blocker: ProactiveCheckBlocker,
): string {
  if (blocker.code === "ISOLATED_EXECUTION_REQUIRED") {
    const names = blocker.toolsets
      .map((name) => toolsetText(name, { label: name, description: "" }).label)
      .join(", ");
    return `${names} 도구가 켜져 있어요. 격리된 실행 공간이 준비되기 전에는 매일 깨우기를 켤 수 없어요.`;
  }
  return describeBlocker(blocker);
}

/**
 * 마지막 살펴보기가 어떻게 끝났는지를 한 구절로 바꾼다. 결과를 읽지 못했으면 점검 대화의 알림 줄과 같은 말을 쓴다.
 * 답이 비었을 때만 형식 탓으로 말하지 않는다.
 */
export function describeLastCheck(check: ProactiveCheckLastCheck): string {
  switch (check.skippedReason) {
    case "NO_CHANGE":
      return "바뀐 것이 없어 건너뛰었어요";
    case "UNREAD_REPORT":
      return "지난 보고를 아직 열지 않았어요";
    case "QUIET_HOURS":
      return "조용한 시간이라 건너뛰었어요";
  }
  switch (check.status) {
    case "RUNNING":
      return "지금 살펴보는 중이에요";
    case "FAILED":
      return "끝내지 못했어요";
    case "STOPPED":
      return "멈췄어요";
    case "SUCCEEDED":
      switch (check.outcome) {
        case "FINDINGS":
          return "새로 알릴 것이 있었어요";
        case "NOTHING_NEW":
          return "새로 알릴 것이 없었어요";
        default:
          return check.invalidReason === "EMPTY_ANSWER"
            ? "답을 받지 못했어요"
            : "결과 형식이 맞지 않아 정리하지 못했어요";
      }
  }
}
