import { expect, spyOn, test } from "bun:test";
import {
  clock,
  clearRateLimits,
  observeRateLimit,
  queueRequest,
  withDeadline,
} from "../src/rate-limit.ts";
import { Tossinvest } from "../src/client.ts";
import { FakeToss, credentials, json, apiError } from "./support.ts";

test("실제 Clock은 정수 벽시계의 다음 눈금에서 1초 대기를 끝내지 않는다", async () => {
  let precise = Math.ceil(performance.timeOrigin) + 10000.75;
  const wall = spyOn(Date, "now").mockImplementation(() => Math.floor(precise));
  const elapsed = spyOn(performance, "now").mockImplementation(
    () => precise - performance.timeOrigin,
  );
  const sleep = spyOn(Bun, "sleep").mockImplementation(async (ms) => {
    const before = clock.now();
    // 타이머가 정수 눈금으로 일찍 깨는 경우를 재현한다.
    precise = Math.floor(precise + Number(ms));
    // 소수 대기를 버리면 같은 시각으로 깨어 큐가 영원히 다시 잠든다.
    // 실제 타이머의 다음 기상까지 진행하지 못하는 fake는 즉시 실패시킨다.
    if (clock.now() <= before)
      throw new Error(`모의 sleep 뒤 큐 시각이 진전하지 않았다: ${ms}ms`);
  });
  const arrivals: number[] = [];
  try {
    expect(clock.now()).toBe(precise);
    for (let index = 0; index < 2; index++)
      await queueRequest(
        "fractional-production",
        "ACCOUNT",
        precise + 5000,
        async () => {
          arrivals.push(precise);
        },
      );
    expect(arrivals[1]! - arrivals[0]!).toBeGreaterThanOrEqual(1000);
  } finally {
    clearRateLimits();
    wall.mockRestore();
    elapsed.mockRestore();
    sleep.mockRestore();
  }
});

test("소수 Clock과 조기 기상에서도 남은 시간까지 다시 기다린다", async () => {
  let now = Date.now() + 10000.75;
  const waits: number[] = [];
  const timer = {
    now: () => now,
    sleep: async (ms: number) => {
      waits.push(ms);
      now += ms - (waits.length === 1 ? 0.5 : 0);
    },
  };
  const arrivals: number[] = [];
  try {
    for (let index = 0; index < 2; index++)
      await queueRequest(
        "early-wake",
        "ACCOUNT",
        now + 5000,
        async () => {
          arrivals.push(now);
        },
        timer,
      );
    expect(waits).toEqual([1000, 1]);
    expect(arrivals[1]! - arrivals[0]!).toBeGreaterThanOrEqual(1000);
  } finally {
    clearRateLimits();
  }
});

test("1초와 같은 deadline은 sleep과 HTTP 없이 즉시 실패한다", async () => {
  let now = Date.now() + 10000.75;
  const waits: number[] = [];
  const controlledClock = {
    now: () => now,
    sleep: async (ms: number) => {
      waits.push(ms);
      now += ms;
    },
  };
  let sent = 0;
  try {
    await queueRequest(
      "exact-boundary",
      "ACCOUNT",
      now + 5000,
      async () => {},
      controlledClock,
    );
    await expect(
      queueRequest(
        "exact-boundary",
        "ACCOUNT",
        now + 1000,
        async () => {
          sent++;
        },
        controlledClock,
      ),
    ).rejects.toMatchObject({ code: "TOSSINVEST_NETWORK" });
    expect(waits).toEqual([]);
    expect(sent).toBe(0);
  } finally {
    clearRateLimits();
  }
});

test("deadline을 넘긴 늦은 기상은 HTTP 작업을 시작하지 않는다", async () => {
  let now = Date.now() + 10000.75;
  const timer = {
    now: () => now,
    sleep: async (ms: number) => {
      now += ms + 0.5;
    },
  };
  let sent = 0;
  const work = async () => {
    sent++;
  };
  try {
    await queueRequest("boundary", "ACCOUNT", now + 5000, work, timer);
    await expect(
      queueRequest("boundary", "ACCOUNT", now + 1000.25, work, timer),
    ).rejects.toMatchObject({ code: "TOSSINVEST_NETWORK" });
    expect(sent).toBe(1);
  } finally {
    clearRateLimits();
  }
});

test("응답의 상대 Reset과 Retry-After는 주입 Clock의 같은 deadline 안에 있다", async () => {
  let now = Date.now() + 10000.75;
  const timer = {
    now: () => now,
    sleep: async (ms: number) => {
      now += ms;
    },
  };
  try {
    await queueRequest("headers", "STOCK", now + 5000, async () => {}, timer);
    observeRateLimit(
      "headers",
      "STOCK",
      429,
      new Headers({
        "retry-after": "1.2",
        "x-ratelimit-reset": "1.1",
        "x-ratelimit-limit": "2",
      }),
      timer,
    );
    const started = now;
    await expect(
      queueRequest(
        "headers",
        "STOCK",
        now + 1200,
        async () => {
          throw new Error("전송 금지");
        },
        timer,
      ),
    ).rejects.toMatchObject({ code: "TOSSINVEST_NETWORK" });
    await queueRequest("headers", "STOCK", now + 1201, async () => {}, timer);
    expect(now - started).toBeGreaterThanOrEqual(1200);
  } finally {
    clearRateLimits();
  }
});

test("deadline 타이머가 일찍 깨도 소수 경계까지 다시 검사한다", async () => {
  let now = 10000.75;
  let callback!: () => void;
  const delays: number[] = [];
  const timeout = spyOn(globalThis, "setTimeout").mockImplementation(((
    fn: () => void,
    ms: number,
  ) => {
    callback = fn;
    delays.push(ms);
    return 1;
  }) as typeof setTimeout);
  try {
    const pending = withDeadline(new Promise<never>(() => {}), now + 1, {
      now: () => now,
      sleep: async () => {},
    });
    const rejected = pending.catch((error) => error);
    now += 0.5;
    callback();
    expect(delays).toEqual([1, 1]);
    now += 0.5;
    callback();
    expect(await rejected).toMatchObject({ code: "TOSSINVEST_NETWORK" });
  } finally {
    timeout.mockRestore();
  }
});

test("실제 Clock의 경과 시간은 벽시계 보정에 흔들리지 않고 KST 피크는 벽시계를 따른다", async () => {
  let now = Math.ceil(performance.timeOrigin) + 10000.75;
  let wall = Date.parse("2026-01-02T00:00:00Z");
  const date = spyOn(Date, "now").mockImplementation(() => wall);
  const elapsed = spyOn(performance, "now").mockImplementation(
    () => now - performance.timeOrigin,
  );
  const sleep = spyOn(Bun, "sleep").mockImplementation(async (ms) => {
    const before = clock.now();
    now += Number(ms);
    // 벽시계는 고정돼 있다. 큐가 Date.now로 회귀하면 fake 경과 시간이
    // 흘러도 큐 시각은 멈춰 있으므로 마이크로태스크 재대기를 허용하지 않는다.
    if (clock.now() <= before)
      throw new Error(`모의 sleep 뒤 큐 시각이 진전하지 않았다: ${ms}ms`);
  });
  try {
    const start = clock.now();
    await queueRequest("wall-peak", "ORDER_INFO", now + 5000, async () => {});
    await queueRequest("wall-peak", "ORDER_INFO", now + 5000, async () => {});
    expect(clock.now() - start).toBe(334);
    wall += 600000;
    const afterPeak = clock.now();
    await queueRequest("wall-peak", "ORDER_INFO", now + 5000, async () => {});
    expect(clock.now() - afterPeak).toBe(167);
    wall -= 86400000;
    expect(clock.now()).toBe(afterPeak + 167);
  } finally {
    clearRateLimits();
    date.mockRestore();
    elapsed.mockRestore();
    sleep.mockRestore();
  }
});

test("벽시계가 앞으로 바뀌어도 캐시 토큰의 TTL은 경과 시간으로 유지된다", async () => {
  const fake = new FakeToss();
  let date: ReturnType<typeof spyOn> | undefined;
  try {
    const client = new Tossinvest({ apiBase: fake.url, env: credentials });
    const token = await client.token();
    const corrected = Date.now() + 86400000;
    date = spyOn(Date, "now").mockReturnValue(corrected);
    expect(await client.token()).toBe(token);
    expect(fake.issued).toBe(1);
  } finally {
    date?.mockRestore();
    fake.stop();
  }
});

test("소수 Clock에서 토큰 TTL의 안전 정수 경계를 정확히 판정한다", async () => {
  const now = Math.ceil(performance.timeOrigin / 1000) * 1000 + 0.75;
  const maximumSeconds =
    (BigInt(Number.MAX_SAFE_INTEGER) - BigInt(Math.ceil(now))) / 1000n;
  const elapsed = spyOn(performance, "now").mockReturnValue(
    now - performance.timeOrigin,
  );
  try {
    for (const extra of [0n, 1n]) {
      const fake = new FakeToss();
      fake.on("POST", "/oauth2/token", {
        access_token: "bounded-token",
        token_type: "Bearer",
        expires_in: Number(maximumSeconds + extra),
      });
      try {
        const pending = new Tossinvest({
          apiBase: fake.url,
          env: credentials,
        }).token();
        if (extra === 0n) expect(await pending).toBe("bounded-token");
        else
          await expect(pending).rejects.toMatchObject({
            code: "TOSSINVEST_UNAVAILABLE",
          });
      } finally {
        fake.stop();
      }
    }
  } finally {
    elapsed.mockRestore();
  }
});

test("실제 Bun HTTP에서 401 다음 429도 처음 deadline을 공유한다", async () => {
  // 실제 HTTP 호환 확인과 간격 실측이다. 정수 시계 결함의 결정적 회귀는
  // 위의 제어 시계와 조기 기상 검사가 맡으며 이 검사는 변이에서도 통과할 수 있다.
  const fake = new FakeToss();
  const arrivals: number[] = [];
  fake.sequence(
    "GET",
    "/api/v1/accounts",
    [401, 429].map((status) => () => {
      arrivals.push(performance.now());
      return apiError(
        status,
        status === 401 ? "token-revoked" : "too-many-requests",
      )();
    }),
  );
  try {
    const client = new Tossinvest({
      apiBase: fake.url,
      env: credentials,
      timeoutMs: 1500,
    });
    await expect(client.request("/api/v1/accounts")).rejects.toMatchObject({
      code: "TOSSINVEST_NETWORK",
    });
    expect(fake.issued).toBe(2);
    expect(arrivals).toHaveLength(2);
    expect(arrivals[1]! - arrivals[0]!).toBeGreaterThanOrEqual(1000);
  } finally {
    fake.stop();
  }
});

test("실제 Bun HTTP에서 secret만 다른 client는 예산을 공유하고 origin은 분리된다", async () => {
  const a = new FakeToss();
  const b = new FakeToss();
  const arrivals: number[] = [];
  a.routes.set("GET /api/v1/accounts", () => {
    arrivals.push(performance.now());
    return json({ result: [] });
  });
  b.on("GET", "/api/v1/accounts", { result: [] });
  try {
    const first = new Tossinvest({ apiBase: a.url, env: credentials });
    const changed = new Tossinvest({
      apiBase: a.url,
      env: { ...credentials, TOSSINVEST_CLIENT_SECRET: "different-secret" },
    });
    const other = new Tossinvest({ apiBase: b.url, env: credentials });
    await Promise.all([first.token(), changed.token(), other.token()]);
    await first.request("/api/v1/accounts");
    const waiting = changed.request("/api/v1/accounts");
    await other.request("/api/v1/accounts");
    expect(arrivals).toHaveLength(1);
    await waiting;
    expect(arrivals[1]! - arrivals[0]!).toBeGreaterThanOrEqual(1000);
    expect(a.issued).toBe(2);
    expect(b.issued).toBe(1);
  } finally {
    a.stop();
    b.stop();
  }
});
