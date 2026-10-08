/** 예약 작업 화면이 쓰는 모양과 시각 변환이다. 서버는 5필드 cron 과 `ONCE` 만 안다. */

import type { ModelTierCode } from "./model-tiers";

export type TaskState = "ACTIVE" | "PAUSED" | "ARCHIVED";
export type ConversationMode = "NEW_PER_RUN" | "SINGLE";
export type MissedPolicy = "RUN_ONCE" | "SKIP";
export type NotifyPolicy = "ALWAYS" | "ON_FAILURE" | "NEVER";
export type TaskRunStatus =
  "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "SKIPPED";

export type ScheduleView = {
  type: "CRON" | "ONCE";
  cron: string | null;
  /** 시간대 없는 날짜와 시각이다. `timeZone` 으로 해석한다 */
  fireAt: string | null;
  timeZone: string;
};

export type TaskView = {
  id: string;
  title: string;
  agentCode: string | null;
  agentName: string | null;
  instruction: string;
  state: TaskState;
  schedule: ScheduleView;
  nextFireAt: string | null;
  lastFiredAt: string | null;
  conversationMode: ConversationMode;
  missedPolicy: MissedPolicy;
  notify: NotifyPolicy;
  modelTier: ModelTierCode | null;
  createdAt: string;
};

export type TaskRunView = {
  id: string;
  scheduledFor: string;
  status: TaskRunStatus;
  reason: string | null;
  conversationId: string | null;
  startedAt: string | null;
  finishedAt: string | null;
};

export type ScheduleRequest = {
  type: "CRON" | "ONCE";
  cron?: string;
  fireAt?: string;
  timeZone: string;
};

export type TaskRequest = {
  title: string;
  agentCode: string;
  instruction: string;
  schedule: ScheduleRequest;
  conversationMode: ConversationMode;
  missedPolicy: MissedPolicy;
  notify: NotifyPolicy;
  modelTier: ModelTierCode | null;
};

/** 화면에서 고르는 시각이다. `weekday` 는 0(일)부터 6(토)까지다 */
export type ScheduleChoice =
  | { kind: "daily"; time: string }
  | { kind: "weekly"; weekday: number; time: string }
  | { kind: "monthly"; day: number; time: string }
  | { kind: "once"; date: string; time: string }
  | { kind: "cron"; cron: string };

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

function splitTime(time: string): { hour: number; minute: number } {
  const [hour, minute] = time.split(":");
  return { hour: Number(hour), minute: Number(minute) };
}

/** 화면에서 고른 시각을 서버가 받는 모양으로 바꾼다. */
export function scheduleRequestOf(
  choice: ScheduleChoice,
  timeZone: string,
): ScheduleRequest {
  if (choice.kind === "cron") {
    return { type: "CRON", cron: choice.cron.trim(), timeZone };
  }
  if (choice.kind === "once") {
    return { type: "ONCE", fireAt: `${choice.date}T${choice.time}`, timeZone };
  }
  const { hour, minute } = splitTime(choice.time);
  const day = choice.kind === "monthly" ? String(choice.day) : "*";
  const weekday = choice.kind === "weekly" ? String(choice.weekday) : "*";
  return {
    type: "CRON",
    cron: `${minute} ${hour} ${day} * ${weekday}`,
    timeZone,
  };
}

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

function isWhole(field: string, max: number, min = 0): boolean {
  return /^\d+$/.test(field) && Number(field) >= min && Number(field) <= max;
}

function onceTimeOf(fireAt: string): string {
  const seconds = fireAt.slice(17, 19);
  return /^[0-5]\d$/.test(seconds) && seconds !== "00"
    ? fireAt.slice(11, 19)
    : fireAt.slice(11, 16);
}

/** 저장된 시각을 화면의 고르기로 되돌린다. 매일, 매주, 매달 모양이 아닌 cron 은 `cron` 이다. */
export function choiceOf(schedule: {
  type: "CRON" | "ONCE";
  cron: string | null;
  fireAt: string | null;
}): ScheduleChoice {
  if (schedule.type === "ONCE" && schedule.fireAt) {
    return {
      kind: "once",
      date: schedule.fireAt.slice(0, 10),
      // 초가 0 이 아니면 초까지 남겨, 화면에서 시각을 바꾸지 않은 저장이 원래 값을 그대로 보낸다
      time: onceTimeOf(schedule.fireAt),
    };
  }
  const cron = schedule.cron ?? "";
  const fields = cron.trim().split(/\s+/);
  if (fields.length === 5) {
    const [minute, hour, day, month, weekday] = fields;
    if (isWhole(minute, 59) && isWhole(hour, 23) && month === "*") {
      const time = `${pad(Number(hour))}:${pad(Number(minute))}`;
      if (day === "*" && weekday === "*") return { kind: "daily", time };
      if (day === "*" && isWhole(weekday, 6)) {
        return { kind: "weekly", weekday: Number(weekday), time };
      }
      if (weekday === "*" && isWhole(day, 31, 1)) {
        return { kind: "monthly", day: Number(day), time };
      }
    }
  }
  return { kind: "cron", cron };
}

/** 시각을 사람 말로 적는다. */
export function describeSchedule(schedule: {
  type: "CRON" | "ONCE";
  cron: string | null;
  fireAt: string | null;
}): string {
  const choice = choiceOf(schedule);
  switch (choice.kind) {
    case "daily":
      return `매일 ${choice.time}`;
    case "weekly":
      return `매주 ${WEEKDAYS[choice.weekday]}요일 ${choice.time}`;
    case "monthly":
      return `매달 ${choice.day}일 ${choice.time}`;
    case "once":
      return `한 번, ${choice.date} ${choice.time}`;
    case "cron":
      return `직접 입력: ${choice.cron}`;
  }
}

export const WEEKDAY_LABELS = WEEKDAYS.map((name) => `${name}요일`);

const REASON_TEXTS: Record<string, string> = {
  MISSED: "서버가 꺼져 있던 동안의 실행이라 건너뛰었어요",
  PAUSED: "작업을 멈춰 건너뛰었어요",
  BUSY: "다른 대화가 오래 돌고 있어 시작하지 못했어요",
  DAILY_LIMIT: "하루 실행 횟수를 다 썼어요",
  AGENT_UNAVAILABLE: "에이전트를 쓸 수 없어요. 작업의 에이전트를 확인해 주세요",
  FAILED: "실행 중에 문제가 생겼어요",
  INTERRUPTED: "서버가 다시 시작돼 실행이 끊겼어요",
  NOTHING_TO_REPORT: "알릴 것이 없어 조용히 끝냈어요",
};

/** 실행이 건너뛰어졌거나 실패한 까닭을 화면 문구로 바꾼다. 보이지 않을 까닭은 null 이다. */
export function runReasonText(reason: string | null): string | null {
  if (reason === null) return null;
  return REASON_TEXTS[reason] ?? null;
}
