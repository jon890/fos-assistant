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

/** 마이크로 단위 정수를 통화 금액으로 보인다. 한 번의 실행이 1센트 아래라서 네 자리까지 적는다. */
export function formatAmount(micros: number, currency: string | null): string {
  const amount = (micros / 1_000_000).toLocaleString("ko-KR", {
    minimumFractionDigits: 4,
    maximumFractionDigits: 4,
  });
  return `${amount} ${currency ?? "USD"}`;
}

/** 값이 없는 금액과 무료인 금액을 구분한다. */
export function formatCost(micros: number | null, currency: string | null): string {
  return micros === null ? "가격 없음" : formatAmount(micros, currency);
}

/** 오늘 실행은 시각만, 지난 실행은 날짜와 시각을 함께 보인다. */
export function formatWhen(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";

  const now = new Date();
  const isToday =
    date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
  return new Intl.DateTimeFormat("ko-KR", {
    ...(isToday ? {} : { month: "2-digit", day: "2-digit" }),
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}
