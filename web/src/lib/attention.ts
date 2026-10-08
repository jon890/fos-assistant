/**
 * 지금 화면이 읽는 `GET /api/v1/attention` 응답의 모양과, 그 응답을 화면 글로 바꾸는 순수 함수다.
 *
 * <p>칸은 `docs/backend/attention.md` 의 「API」 가 정한다. 문구는 `docs/frontend/now.md` 의 「카드」 와 「이유 문구」 표 그대로다.
 * 이유 문구를 서버가 보내지 않고 여기서 정하는 것은 모델이 쓴 글로 이유를 만들지 않기 위해서다.
 *
 * <p>단위 테스트가 `node --test` 로 직접 읽으므로 다른 모듈을 import 하지 않는다.
 */

export type AttentionCardKey =
  "failures" | "needs_me" | "delegated" | "continue" | "reports";

export type AttentionLevel = "NOW" | "LATER";

export type AttentionWhy = {
  trigger: string;
  signals: string[];
  confidence: string;
  sources: { source: string; ref: string; asOf: string | null }[];
};

export type AttentionItem = {
  /** 제어와 사건을 보낼 때 그대로 돌려보내는 값이다. 잘라 식별자를 얻지 않는다 */
  itemKey: string;
  stateKey: string;
  attention: AttentionLevel;
  /** 화면 안에서만 보이므로 값이 하나다. 화면은 이 칸으로 가르지 않는다 */
  channel: "IN_APP";
  /** 모델이 쓴 글일 수 있다. 평문으로만 그린다 */
  title: string;
  conversationId: string | null;
  agentName: string | null;
  at: string;
  why: AttentionWhy;
  execution: { id: number; status: string } | null;
  actionId: string | null;
  followUp: {
    id: string;
    dueAt: string | null;
    waiting: boolean;
    proposed: boolean;
    agentProposed: boolean;
  } | null;
  /** 「보고」 카드의 다섯 칸 요약이다. 그 밖의 카드에서는 null 이다. */
  report: AttentionReport | null;
  /** 먼저 다룰 문제 항목의 판정과 제안한 다음 행동이다. 모델이 쓴 글이라 평문으로만 그린다. 그 밖의 항목에서는 null 이다. */
  problem: {
    decisionId: number;
    level: string;
    action: string | null;
  } | null;
};

export type AttentionReport = {
  checkId: number;
  agentCode: string;
  changed: string[];
  done: string[];
  evidence: string[];
  needsApproval: string[];
  next: string[];
};

export type AttentionCard = {
  key: AttentionCardKey;
  status: "OK" | "UNAVAILABLE";
  /** 상한으로 자르기 전에 서버가 센 이 카드의 `NOW` 항목 수다 */
  nowCount: number;
  moreCount: number;
  items: AttentionItem[];
};

export type AttentionView = {
  readAt: string;
  nowCount: number;
  cards: AttentionCard[];
};

/**
 * `trigger` 마다 문구 표다. 위쪽 줄이 먼저다. `signals` 가 `null` 인 줄이 그 `trigger` 의 「없음」 줄이다.
 *
 * <p>`only` 가 참인 줄은 `signals` 가 그 하나뿐일 때만 맞는다(표의 「`WAITING` 만」).
 */
const REASONS: Record<
  string,
  readonly { signal: string | null; only?: boolean; text: string }[]
> = {
  EXECUTION_FAILED: [
    {
      signal: "NOT_RETRIED",
      text: "답을 만들지 못했고 아직 다시 보내지 않았어요",
    },
    { signal: null, text: "답을 만들지 못했어요" },
  ],
  DELIVERY_FAILED: [
    {
      signal: "DELIVERY_NOT_DONE",
      text: "결과는 도착했는데 정리한 답을 만들지 못했어요",
    },
    { signal: null, text: "결과를 정리한 답을 만들지 못했어요" },
  ],
  APPROVAL_PENDING: [
    { signal: "EXPIRES_SOON", text: "승인을 기다리고 있어요. 곧 만료돼요" },
    { signal: null, text: "승인을 기다리고 있어요" },
  ],
  MEMORY_PROPOSED: [
    { signal: null, text: "에이전트가 기억할 것을 제안했어요" },
  ],
  FOLLOW_UP_PROPOSED: [{ signal: null, text: "에이전트가 할 일로 제안했어요" }],
  FOLLOW_UP_OPEN: [
    { signal: "DUE_SOON", text: "기한이 다가왔어요" },
    { signal: "OVERDUE", text: "기한이 지났어요" },
    { signal: "LINKED_UPDATE", text: "연결한 대화에 결과가 도착했어요" },
    { signal: "WAITING", only: true, text: "기다리는 중이에요" },
    { signal: null, text: "챙기고 있는 할 일이에요" },
  ],
  DELEGATION_RUNNING: [
    { signal: "LONG_RUNNING", text: "맡긴 일이 오래 걸리고 있어요" },
    { signal: null, text: "맡긴 일이 진행 중이에요" },
  ],
  DELEGATION_FINISHED: [{ signal: null, text: "맡긴 일이 끝났어요" }],
  CONVERSATION_RECENT: [{ signal: null, text: "최근에 나눈 대화예요" }],
  PROBLEM_SURFACED: [
    { signal: null, text: "에이전트가 먼저 다룰 문제로 골랐어요" },
  ],
  PROACTIVE_REPORT_TRIGGER: [{ signal: null, text: "새 보고가 있어요" }],
};

/**
 * 항목의 이유 한 줄이다. `signals` 가 여럿이면 표의 위쪽 줄을, 표에 없는 조합은 그 `trigger` 의 「없음」 줄을 쓴다.
 *
 * @returns 모르는 `trigger` 면 빈 글
 */
export function reasonText(why: AttentionWhy): string {
  const rows = REASONS[why.trigger];
  if (!rows) return "";
  const row = rows.find((candidate) => {
    if (candidate.signal === null) return true;
    if (candidate.only)
      return why.signals.length === 1 && why.signals[0] === candidate.signal;
    return why.signals.includes(candidate.signal);
  });
  return row?.text ?? "";
}

const CARD_TITLES: Record<AttentionCardKey, string> = {
  failures: "실패",
  needs_me: "내 차례",
  delegated: "맡긴 일",
  continue: "이어서 하기",
  reports: "보고",
};

const CARD_EMPTY_TEXTS: Record<AttentionCardKey, string> = {
  failures: "실패한 일이 없어요",
  needs_me: "확인할 것이 없어요",
  delegated: "맡긴 일이 없어요",
  continue: "최근 대화가 없어요",
  reports: "새 보고가 없어요",
};

export function cardTitle(key: AttentionCardKey): string {
  return CARD_TITLES[key];
}

export function cardEmptyText(key: AttentionCardKey): string {
  return CARD_EMPTY_TEXTS[key];
}

/** 다섯 카드가 모두 읽혔고 모두 비었는가. 하나라도 읽지 못했으면 거짓이다 */
export function allCardsEmpty(cards: AttentionCard[]): boolean {
  return cards.every((card) => card.status === "OK" && card.items.length === 0);
}

/**
 * 제목 링크가 갈 원래 기록이다. `docs/backend/attention.md` 의 「카드의 단추와 승인 경계」 를 따른다.
 *
 * @returns 갈 곳이 없으면 `null`
 */
export function itemHref(item: AttentionItem): string | null {
  switch (item.why.trigger) {
    case "EXECUTION_FAILED":
    case "DELIVERY_FAILED":
    case "APPROVAL_PENDING":
    case "CONVERSATION_RECENT":
    case "FOLLOW_UP_PROPOSED":
    case "FOLLOW_UP_OPEN":
    case "PROBLEM_SURFACED":
      return item.conversationId === null
        ? null
        : `/chat/${item.conversationId}`;
    case "MEMORY_PROPOSED":
      return "/memory";
    case "DELEGATION_RUNNING":
    case "DELEGATION_FINISHED":
      return item.execution === null
        ? null
        : `/executions/${item.execution.id}`;
    default:
      return null;
  }
}

/** 할 일이 어디서 왔는지다. 에이전트가 제안했으면 「대화에서」, 사람이 더했으면 「직접 더함」. 할 일이 아니면 `null` */
export function originText(item: AttentionItem): string | null {
  if (item.followUp === null) return null;
  return item.followUp.agentProposed ? "대화에서" : "직접 더함";
}

/**
 * 상한을 넘어 빠진 항목의 안내다. 실패와 맡긴 일만 실행 기록으로 보낸다.
 * 내 차례는 여러 종류가 섞여 보낼 곳이 없고, 이어서 하기의 나머지 대화는 사이드바 목록에 있다.
 *
 * @returns `moreCount` 가 0 이면 `null`
 */
export function moreText(
  card: AttentionCard,
): { text: string; href: string | null } | null {
  if (card.moreCount <= 0) return null;
  const href =
    card.key === "failures" || card.key === "delegated"
      ? "/usage?tab=executions"
      : null;
  return { text: `${card.moreCount}개 더 있어요`, href };
}

/**
 * 지금 그리는 항목에 없는 열쇠를 제어 표에서 뺀다. 숨긴 항목이 다시 읽은 응답에서 빠졌다가 같은 열쇠로 돌아와도
 * 옛 제어가 남아 카드 머리의 수를 틀리게 하지 않게 하려는 것이다.
 *
 * @returns 뺄 열쇠가 없으면 받은 표 그대로
 */
export function pruneControls<T>(
  controls: ReadonlyMap<string, T>,
  items: readonly AttentionItem[],
): ReadonlyMap<string, T> {
  const present = new Set(items.map((item) => item.itemKey));
  const kept = [...controls].filter(([itemKey]) => present.has(itemKey));
  return kept.length === controls.size ? controls : new Map(kept);
}

/**
 * 카드 머리에 그릴 수다. 서버가 상한 전에 센 `nowCount` 에서, 지금 그리는 `NOW` 항목 가운데 이 화면에서 제어한 것만큼 뺀다.
 * 다시 읽은 응답에서 빠진 항목은 서버의 수에서도 빠졌으므로 다시 빼지 않는다.
 */
export function visibleNowCount(
  card: AttentionCard,
  controls: ReadonlyMap<string, unknown>,
): number {
  const controlled = card.items.filter(
    (item) => item.attention === "NOW" && controls.has(item.itemKey),
  ).length;
  return Math.max(0, card.nowCount - controlled);
}

/** 관리자 지표의 비율이다. 전체가 0 이면 나눌 수 없어 「-」 다 */
export function ratioText(part: number, whole: number): string {
  return whole === 0 ? "-" : `${Math.round((part * 100) / whole)}%`;
}

/** 사이드바 「지금 볼 것」 링크의 접근성 이름이다 */
export function nowLinkLabel(nowCount: number): string {
  return nowCount > 0 ? `지금 볼 것 ${nowCount}건` : "지금 볼 것";
}

/**
 * 가족이 사는 시간대(`Asia/Seoul`)와 UTC 의 차이다. 한국은 일광 절약 시간이 없어 고정으로 더하고 뺀다.
 * 브라우저의 시간대를 따르지 않는다. 같은 화면이 어느 기기에서도 같은 기한을 보내게 하려는 것이다.
 */
const SEOUL_OFFSET_MS = 9 * 60 * 60 * 1000;

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * 미루기의 기한이다. 「내일 아침」 은 서울 기준 다음 날 09:00, 「일주일 뒤」 는 지금에서 7일 뒤 같은 시각이다.
 *
 * @returns `toISOString()` 글
 */
export function snoozeUntil(kind: "tomorrow" | "week", now: Date): string {
  if (kind === "week")
    return new Date(now.getTime() + 7 * DAY_MS).toISOString();
  const seoul = new Date(now.getTime() + SEOUL_OFFSET_MS);
  const nextMorning = Date.UTC(
    seoul.getUTCFullYear(),
    seoul.getUTCMonth(),
    seoul.getUTCDate() + 1,
    9,
  );
  return new Date(nextMorning - SEOUL_OFFSET_MS).toISOString();
}

const SEOUL_INPUT = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/;

/**
 * `datetime-local` 입력의 `YYYY-MM-DDTHH:mm` 을 서울의 그 시각으로 읽는다.
 *
 * @returns `toISOString()` 글. 빈 글이거나 형식이 틀리거나 없는 날짜면 `null`
 */
export function seoulInputToIso(value: string): string | null {
  const match = SEOUL_INPUT.exec(value);
  if (!match) return null;
  const [year, month, day, hour, minute] = match.slice(1).map(Number);
  const wall = Date.UTC(year, month - 1, day, hour, minute);
  const parsed = new Date(wall);
  // Date.UTC 는 2월 30일 같은 값을 다음 달로 넘긴다. 넘겼으면 없는 날짜로 본다.
  if (
    parsed.getUTCFullYear() !== year ||
    parsed.getUTCMonth() !== month - 1 ||
    parsed.getUTCDate() !== day ||
    parsed.getUTCHours() !== hour ||
    parsed.getUTCMinutes() !== minute
  ) {
    return null;
  }
  return new Date(wall - SEOUL_OFFSET_MS).toISOString();
}

/**
 * `seoulInputToIso` 의 반대다. 서울의 벽시계로 `datetime-local` 입력값을 만든다.
 *
 * @returns `null` 이거나 읽지 못하는 시각이면 빈 글
 */
export function isoToSeoulInput(iso: string | null): string {
  if (iso === null) return "";
  const time = new Date(iso).getTime();
  if (Number.isNaN(time)) return "";
  return new Date(time + SEOUL_OFFSET_MS).toISOString().slice(0, 16);
}

export type ItemAction = {
  kind:
    | "link"
    | "accept"
    | "reject"
    | "edit"
    | "done"
    | "drop"
    | "react-accept"
    | "react-dismiss";
  label: string;
};

/**
 * 항목의 단추다. `docs/frontend/now.md` 의 「동작」 표를 `trigger` 로 고른다.
 *
 * <p>결과 전달 실패도 「대화 열기」 뿐이다. 다시 전달은 그 대화의 알림 줄이 한다.
 * `link` 단추는 갈 곳(`itemHref`)이 없으면 내지 않는다. 할 일 단추는 보낼 식별자(`followUp`)가 없으면 내지 않는다.
 */
export function itemActions(item: AttentionItem): ItemAction[] {
  const link = (label: string): ItemAction[] =>
    itemHref(item) === null ? [] : [{ kind: "link", label }];
  switch (item.why.trigger) {
    case "EXECUTION_FAILED":
    case "DELIVERY_FAILED":
      return link("대화 열기");
    case "APPROVAL_PENDING":
      return link("대화에서 보기");
    case "MEMORY_PROPOSED":
      return link("기억에서 보기");
    case "FOLLOW_UP_PROPOSED":
      if (item.followUp === null) return [];
      return [
        { kind: "accept", label: "받아들이기" },
        { kind: "reject", label: "거절" },
        { kind: "edit", label: "고치기" },
      ];
    case "FOLLOW_UP_OPEN":
      if (item.followUp === null) return [];
      return [
        { kind: "done", label: "끝냄" },
        { kind: "drop", label: "그만둠" },
        { kind: "edit", label: "고치기" },
      ];
    case "DELEGATION_RUNNING":
    case "DELEGATION_FINISHED":
      return link("작업 과정 보기");
    case "PROBLEM_SURFACED":
      if (item.problem === null) return [];
      return [
        ...link("점검 대화에서 보기"),
        { kind: "react-accept", label: "받아들임" },
        { kind: "react-dismiss", label: "관심 없음" },
      ];
    default:
      return [];
  }
}
