import { TossinvestError } from "./errors.ts";

/** canonical의 19개 그룹이다. 개요의 MARKET_INDICATOR_PRICE는 별도 operation이 없다. */
export const GROUP_LIMITS: Readonly<Record<string, number>> = {
  AUTH: 5,
  ACCOUNT: 1,
  STOCK_ALL: 1,
  ASSET: 5,
  STOCK: 5,
  RANKING: 5,
  SECTOR: 5,
  SECTOR_RANKING: 5,
  ORDER_HISTORY: 5,
  CONDITIONAL_ORDER: 5,
  MARKET_INFO: 3,
  MARKET_DATA: 15,
  MARKET_DATA_CHART: 20,
  STOCK_TRADING_TREND: 10,
  MARKET_INDICATOR: 10,
  CONDITIONAL_ORDER_HISTORY: 10,
  MARKET_INDICATOR_CHART: 5,
  ORDER: 10,
  ORDER_INFO: 6,
};
export interface Clock {
  /** epoch 밀리초다. 같은 Clock 안의 경과 시간과 deadline을 비교한다. */
  now(): number;
  sleep(ms: number): Promise<void>;
}
/** 벽시계 보정에 흔들리지 않는 소수 밀리초 시각이다. KST 판정만 현재 벽시계를 쓴다. */
export const clock: Clock = {
  now: () => performance.timeOrigin + performance.now(),
  sleep: (ms) => Bun.sleep(ms),
};
type Queue = {
  tail: Promise<void>;
  pending: number;
  endedAt: number;
  blockedUntil: number;
  limit: number;
  limitUntil: number;
};
const queues = new Map<string, Queue>();

export function groupLimit(group: string, now: number): number {
  const kst = new Date(now + 9 * 60 * 60 * 1000);
  return group === "ORDER_INFO" &&
    kst.getUTCHours() === 9 &&
    kst.getUTCMinutes() < 10
    ? 3
    : (GROUP_LIMITS[group] ?? 1);
}
export function clearRateLimits(origin?: string) {
  for (const key of queues.keys())
    if (!origin || key.startsWith(`${origin}\n`)) queues.delete(key);
}
export function rateLimitStateSize() {
  return queues.size;
}

/** 같은 origin/client의 예산을 공유한다. 대기와 HTTP를 하나의 deadline 안에 둔다. */
export async function queueRequest<T>(
  identity: string,
  group: string,
  deadline: number,
  work: () => Promise<T>,
  timer: Clock = clock,
): Promise<T> {
  const now = timer.now();
  for (const [key, q] of queues) {
    if (
      !q.pending &&
      Math.max(q.blockedUntil, q.limitUntil, q.endedAt + 1000) <= now
    )
      queues.delete(key);
  }
  const key = `${identity}\n${group}`;
  let q = queues.get(key);
  if (!q) {
    q = {
      tail: Promise.resolve(),
      pending: 0,
      endedAt: 0,
      blockedUntil: 0,
      limit: Infinity,
      limitUntil: 0,
    };
    queues.set(key, q);
  }
  const state = q;
  state.pending++;
  const pending = state.tail
    .then(async () => {
      while (true) {
        const current = timer.now();
        const limit = Math.min(
          groupLimit(group, timer === clock ? Date.now() : current),
          current < state.limitUntil ? state.limit : Infinity,
        );
        const wait =
          Math.max(
            state.blockedUntil,
            state.endedAt + Math.ceil(1000 / limit),
          ) - current;
        if (current >= deadline || current + Math.max(0, wait) >= deadline)
          throw new TossinvestError("TOSSINVEST_NETWORK");
        if (wait <= 0) break;
        // 정수 밀리초 타이머가 남은 소수 시간을 버리지 않게 올리고, 깬 뒤 다시 검사한다.
        await timer.sleep(Math.ceil(wait));
      }
      try {
        return await work();
      } finally {
        state.endedAt = timer.now();
      }
    })
    .finally(() => {
      state.pending--;
    });
  state.tail = pending.then(
    () => {},
    () => {},
  );
  // 기다리다가 deadline이 끝난 호출도 즉시 반환하며 큐 안의 작업은 HTTP를 시작하지 않는다.
  return withDeadline(pending, deadline, timer);
}

export function observeRateLimit(
  identity: string,
  group: string,
  status: number,
  headers: Headers,
  timer: Clock = clock,
) {
  const q = queues.get(`${identity}\n${group}`);
  if (!q) return;
  const seconds = (name: string) => {
    const raw = headers.get(name);
    const n = raw === null ? NaN : Number(raw);
    return Number.isFinite(n) && n >= 0
      ? Math.min(n * 1000, Number.MAX_SAFE_INTEGER)
      : 0;
  };
  const now = timer.now();
  const reset = seconds("x-ratelimit-reset");
  const limit = Number(headers.get("x-ratelimit-limit"));
  if (Number.isFinite(limit) && limit > 0) {
    q.limit = Math.min(q.limit, limit);
    q.limitUntil = Math.max(q.limitUntil, now + Math.max(reset, 1000));
  }
  if (headers.get("x-ratelimit-remaining") === "0")
    q.blockedUntil = Math.max(q.blockedUntil, now + reset);
  if (status === 429)
    q.blockedUntil = Math.max(
      q.blockedUntil,
      now + Math.max(1000, reset, seconds("retry-after")),
    );
}

export async function withDeadline<T>(
  work: Promise<T>,
  deadline: number,
  source: Clock = clock,
): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      work,
      new Promise<never>((_, reject) => {
        const check = () => {
          const remaining = deadline - source.now();
          if (remaining <= 0) reject(new TossinvestError("TOSSINVEST_NETWORK"));
          else timer = setTimeout(check, Math.ceil(remaining));
        };
        check();
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}
