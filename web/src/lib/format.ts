/** 이름이 없는 사건은 목표나 preview로 구분하고, 긴 본문은 코드 포인트 단위로 줄인다. */
export function subagentLabel(
  name: string | null | undefined,
  goal: string | null | undefined,
): string {
  const label = name?.trim() || goal?.trim() || "도우미";
  const codePoints = Array.from(label);
  return codePoints.length > 80
    ? `${codePoints.slice(0, 79).join("")}…`
    : label;
}

/** 에이전트 행이 없어 이름을 알 수 없는 대화와 실행은 지운 에이전트로 그린다. */
export function agentLabel(name: string | null): string {
  return name ?? "지운 에이전트";
}

/** 토큰 수를 자릿수 구분이 있는 문자열로 바꾼다. */
export function formatTokens(tokens: number | null): string {
  return tokens === null ? "-" : tokens.toLocaleString("ko-KR");
}

/** 밀리초를 짧고 읽기 쉬운 소요 시간으로 바꾼다. */
export function formatDuration(milliseconds: number): string {
  if (milliseconds < 1_000) return `${milliseconds.toLocaleString("ko-KR")}ms`;
  return `${(milliseconds / 1_000).toLocaleString("ko-KR", {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  })}초`;
}

/** 긴 작업의 흐른 시간을 초 단위로 보인다. */
export function formatElapsed(milliseconds: number): string {
  const seconds = Math.max(0, Math.floor(milliseconds / 1_000));
  const minutes = Math.floor(seconds / 60);
  return minutes === 0 ? `${seconds}초` : `${minutes}분 ${seconds % 60}초`;
}

/** 대화의 줄에 붙이는 걸린 시간이다. 1초가 안 되면 그리지 않도록 `null` 을 낸다. */
export function formatSeconds(milliseconds: number): string | null {
  return milliseconds < 1_000 ? null : formatElapsed(milliseconds);
}

/**
 * 실행 하나의 걸린 시간을 역할에 맞춰 보인다.
 *
 * <p>관리자는 밀리초까지 본다. 그 밖의 사용자에게는 초 단위로 보이고, 1초가 안 되면 「1초 미만」 이다.
 */
export function formatDurationFor(
  milliseconds: number,
  isAdmin: boolean,
): string {
  return isAdmin
    ? formatDuration(milliseconds)
    : (formatSeconds(milliseconds) ?? "1초 미만");
}

/** 마이크로 단위 정수를 통화 금액으로 보인다. 한 번의 실행이 1센트 아래라서 네 자리까지 적는다. */
export function formatAmount(micros: number, currency: string | null): string {
  const amount = (micros / 1_000_000).toLocaleString("ko-KR", {
    minimumFractionDigits: 4,
    maximumFractionDigits: 4,
  });
  return `${amount} ${currency ?? "USD"}`;
}

/** 값이 없는 금액과 무료인 금액을 구분한다. */
export function formatCost(
  micros: number | null,
  currency: string | null,
): string {
  return micros === null ? "가격 없음" : formatAmount(micros, currency);
}

/** 오늘 실행은 시각만, 지난 실행은 날짜와 시각을 함께 보인다. */
export function formatWhen(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";

  const now = new Date();
  const isToday =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate();
  return new Intl.DateTimeFormat("ko-KR", {
    ...(isToday ? {} : { month: "2-digit", day: "2-digit" }),
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/**
 * `now` 를 기준으로 한 상대 시각이다. 7일이 넘으면 「9월 20일」 처럼 날짜로 적는다.
 *
 * <p>`now` 를 받는 것은 서버에서 그린 글과 브라우저에서 다시 그린 글이 같아야 하기 때문이다.
 * 앞날짜는 「방금」 으로 둔다. 날짜는 `Asia/Seoul` 로 적는다.
 */
export function formatRelative(value: string, now: Date): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";

  const elapsed = now.getTime() - date.getTime();
  if (elapsed < MINUTE_MS) return "방금";
  if (elapsed < HOUR_MS) return `${Math.floor(elapsed / MINUTE_MS)}분 전`;
  if (elapsed < DAY_MS) return `${Math.floor(elapsed / HOUR_MS)}시간 전`;
  if (elapsed < 7 * DAY_MS) return `${Math.floor(elapsed / DAY_MS)}일 전`;
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    month: "long",
    day: "numeric",
  }).format(date);
}

/** 마우스를 올렸을 때 보이는 전체 시각이다. `Asia/Seoul` 로 적는다. */
export function formatFullTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}
