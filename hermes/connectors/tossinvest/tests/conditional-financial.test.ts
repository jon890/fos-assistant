import { expect, test } from "bun:test";
import { Tossinvest, clearClientState } from "../src/client.ts";
import { conditionalResult } from "../src/conditional-result.ts";
import { prepareConditionalTrade } from "../src/prepare-conditional.ts";
import { observeRateLimit, queueRequest, clock } from "../src/rate-limit.ts";
import { UNKNOWN } from "../src/trading-body.ts";
import {
  ClaimFixture,
  createConditionalFixtureServer,
} from "./fixtures/conditional-entry.ts";
import {
  credentials,
  json,
  tool,
  withMcp,
  useSkippedWaits,
} from "./support.ts";
import {
  PATHS,
  models,
  originals,
  setup,
  type ConditionalTool,
} from "./conditional-support.ts";

useSkippedWaits();

const INVALID = "TOSSINVEST_INVALID_INPUT";
const IDS: Record<ConditionalTool, string> = {
  create_conditional_order: "createConditionalOrder",
  modify_conditional_order: "modifyConditionalOrder",
  cancel_conditional_order: "cancelConditionalOrder",
};
const sleep = (ms: number) => new Promise((done) => setTimeout(done, ms));
const envelope = (code: string) => ({
  error: { requestId: "r", code, message: "m", data: null },
});
const exchange = (status: number, data: unknown, empty = false) => ({
  status,
  ok: status >= 200 && status < 300,
  data,
  empty,
  retryAfterMs: 0,
});
const financial = (fake: ReturnType<typeof setup>["fake"]) =>
  fake.requests.filter(
    (r) =>
      r.method === "DELETE" ||
      (r.method === "POST" && r.path.startsWith("/api/v1/conditional-orders")),
  ).length;

// 기능 문서 「일회 실행과 HTTP 예산」 표를 소스와 따로 옮겨 적은 기대값이다.
const ALL = ["C", "M", "X"];
const TABLE: Array<[string[], number, string[]]> = [
  [ALL, 400, ["invalid-request", "account-header-required"]],
  [["C", "M"], 400, ["confirm-high-value-required"]],
  [["C"], 404, ["stock-not-found"]],
  [["M", "X"], 404, ["conditional-order-not-found"]],
  [ALL, 404, ["account-not-found", "edge-blocked"]],
  [
    ALL,
    401,
    [
      "invalid-token",
      "edge-blocked",
      "expired-token",
      "token-revoked",
      "login-user-not-found",
    ],
  ],
  [ALL, 403, ["edge-blocked", "ip-not-allowed", "forbidden"]],
  [ALL, 414, ["edge-blocked"]],
  [["C", "M"], 415, ["unsupported-content-type"]],
  [["C", "M"], 422, ["duplicate-conditional-order", "condition-already-met"]],
  [["C"], 422, ["idempotency-key-conflict"]],
];
const LETTER: Record<string, string> = {
  createConditionalOrder: "C",
  modifyConditionalOrder: "M",
  cancelConditionalOrder: "X",
};

test("CASE-REJECTION: 표의 operation+status+code 만 FAILED 이고 나머지는 UNKNOWN 이다", () => {
  let failed = 0;
  let unknown = 0;
  for (const id of Object.values(IDS)) {
    const statuses = new Set([
      ...TABLE.map(([, status]) => status),
      409,
      422,
      429,
      500,
      503,
    ]);
    const codes = new Set([
      ...TABLE.flatMap(([, , list]) => list),
      "insufficient-buying-power",
      "request-in-progress",
      "unknown-code",
    ]);
    for (const status of statuses)
      for (const code of codes) {
        const allowed = TABLE.some(
          ([ops, s, list]) =>
            ops.includes(LETTER[id]!) && s === status && list.includes(code),
        );
        const call = () =>
          conditionalResult(id, exchange(status, envelope(code)));
        expect(call).toThrow(allowed ? INVALID : UNKNOWN);
        if (allowed) failed++;
        else unknown++;
        // 같은 status·code 라도 envelope 가 불량이면 UNKNOWN 이다.
        for (const bad of [
          { error: { code, message: "m" } },
          { error: { requestId: "r", code, message: "m", data: [] } },
          undefined,
        ])
          expect(() => conditionalResult(id, exchange(status, bad))).toThrow(
            UNKNOWN,
          );
      }
  }
  // 성공 응답의 모양도 operation 별로 엄격하다.
  expect(
    conditionalResult(
      IDS.cancel_conditional_order,
      exchange(204, undefined, true),
    ),
  ).toEqual({});
  for (const [id, response] of [
    [
      IDS.cancel_conditional_order,
      exchange(200, { result: { conditionalOrderId: "x" } }),
    ],
    [IDS.cancel_conditional_order, exchange(204, undefined, false)],
    [IDS.create_conditional_order, exchange(204, undefined, true)],
    [IDS.create_conditional_order, exchange(200, { result: { orderId: "x" } })],
    [
      IDS.modify_conditional_order,
      exchange(200, { result: { conditionalOrderId: "" } }),
    ],
    [
      IDS.modify_conditional_order,
      exchange(201, { result: { conditionalOrderId: "x" } }),
    ],
  ] as const)
    expect(() => conditionalResult(id, response)).toThrow(UNKNOWN);
  console.info(`CASE-REJECTION FAILED=${failed} UNKNOWN=${unknown}`);
});

async function prepared(name: ConditionalTool, fake: any, env: any) {
  const result = await prepareConditionalTrade(
    new Tossinvest({ apiBase: fake.url, env }),
    name,
    models()[name],
  );
  return result.executionArgs as Record<string, any>;
}

async function execute(
  name: ConditionalTool,
  fake: any,
  env: any,
  args: any,
  options: {
    duration?: number;
    raw?: string;
    claims?: ClaimFixture;
    timeoutMs?: number;
  } = {},
) {
  const claims = options.claims ?? new ClaimFixture();
  const authority = claims.authority(name, args, options.duration, options.raw);
  let body: any;
  let isError = false;
  try {
    await withMcp(
      createConditionalFixtureServer({
        apiBase: fake.url,
        env: { ...env, ...authority },
        timeoutMs: options.timeoutMs,
      }),
      async (client) => {
        const response = await tool(client, name, args);
        body = response.body;
        isError = Boolean(response.result.isError);
      },
    );
  } finally {
    if (!options.claims) claims.stop();
  }
  return { body, isError, claims: claims.requests.length };
}

for (const [label, name, route, expected] of [
  [
    "401 공식 거절",
    "create_conditional_order",
    () => json(envelope("invalid-token"), 401),
    INVALID,
  ],
  [
    "401 미지 code",
    "create_conditional_order",
    () => json(envelope("mystery"), 401),
    UNKNOWN,
  ],
  [
    "404 조건주문 없음",
    "cancel_conditional_order",
    () => json(envelope("conditional-order-not-found"), 404),
    INVALID,
  ],
  [
    "409 처리 중",
    "modify_conditional_order",
    () => json(envelope("request-in-progress"), 409),
    UNKNOWN,
  ],
  [
    "429",
    "create_conditional_order",
    () => json(envelope("rate-limited"), 429),
    UNKNOWN,
  ],
  [
    "500",
    "modify_conditional_order",
    () => json(envelope("internal"), 500),
    UNKNOWN,
  ],
  ["불량 200", "create_conditional_order", () => json({ result: {} }), UNKNOWN],
  [
    "취소의 JSON 200",
    "cancel_conditional_order",
    () => json({ result: { conditionalOrderId: "cond-1" } }),
    UNKNOWN,
  ],
  [
    "정정의 같은 ID",
    "modify_conditional_order",
    () => json({ result: { conditionalOrderId: "cond-1" } }),
    UNKNOWN,
  ],
  [
    "생성의 다른 clientOrderId",
    "create_conditional_order",
    () => json({ result: { conditionalOrderId: "n", clientOrderId: "other" } }),
    UNKNOWN,
  ],
] as const) {
  test(`전송 뒤 ${label}: ${expected}, 금융 1회·auth 재발급과 재전송 0회`, async () => {
    const { fake, env } = setup(name);
    try {
      const args = await prepared(name, fake, env);
      const [method, path] = PATHS[name];
      fake.routes.set(`${method} ${path}`, route);
      const issued = fake.issued;
      const result = await execute(name, fake, env, args);
      expect(result.isError).toBe(true);
      expect(result.body).toEqual({ error: { code: expected } });
      expect(result.claims).toBe(1);
      expect(financial(fake)).toBe(1);
      expect(fake.issued).toBe(issued);
    } finally {
      fake.stop();
    }
  });
}

test("전송 뒤 본문 끊김: UNKNOWN, 목적지 POST 1회·재전송 0회", async () => {
  const name = "create_conditional_order";
  const { fake, env } = setup(name);
  // 실제 socket 응답을 받은 뒤 본문 stream 만 끊는다. 목적지에는 POST 한 번이 먼저 도착한다.
  const fetchOriginal = globalThis.fetch;
  try {
    const args = await prepared(name, fake, env);
    fake.on("POST", PATHS[name][1], { result: { conditionalOrderId: "n" } });
    globalThis.fetch = (async (input: any, init: any) => {
      const response = await fetchOriginal(input, init);
      if (!String(input).endsWith(PATHS[name][1])) return response;
      return new Response(
        new ReadableStream({
          pull(controller) {
            controller.error(new TypeError("socket closed"));
          },
        }),
        { status: 200 },
      );
    }) as typeof fetch;
    const result = await execute(name, fake, env, args);
    expect(result.body).toEqual({ error: { code: UNKNOWN } });
    expect(financial(fake)).toBe(1);
  } finally {
    globalThis.fetch = fetchOriginal;
    fake.stop();
  }
});

for (const [label, name, change] of [
  [
    "원문과 다른 인자",
    "create_conditional_order",
    (args: any) => ({
      args: { ...args, quantity: "3" },
      raw: JSON.stringify(args),
    }),
  ],
  [
    "계좌 env 변경",
    "create_conditional_order",
    (args: any) => ({ args, env: { TOSSINVEST_ACCOUNT_SEQ: "7" } }),
  ],
  [
    "만료한 ticket",
    "cancel_conditional_order",
    (args: any) => ({ args, duration: -50 }),
  ],
] as const) {
  test(`claim 전 거절 ${label}: claim 0회·금융 0회`, async () => {
    const { fake, env } = setup(name);
    try {
      const args = await prepared(name, fake, env);
      const changed = change(args) as any;
      const result = await execute(
        name,
        fake,
        { ...env, ...changed.env },
        changed.args,
        {
          raw: changed.raw,
          duration: changed.duration,
        },
      );
      expect(result.body).toEqual({ error: { code: INVALID } });
      expect(result.claims).toBe(0);
      expect(financial(fake)).toBe(0);
    } finally {
      fake.stop();
    }
  });
}

for (const [label, name, model] of [
  ["추가 키", "create_conditional_order", { scope: "x" }],
  ["number decimal", "create_conditional_order", { quantity: 2 }],
  ["true 동의", "create_conditional_order", { confirmHighValueOrder: true }],
  [
    "모델 clientOrderId",
    "create_conditional_order",
    { clientOrderId: "00000000-0000-4000-8000-000000000001" },
  ],
  [
    "모델 expected",
    "modify_conditional_order",
    { expected_conditional_order: originals.modify_conditional_order },
  ],
  ["C0 ID", "cancel_conditional_order", { conditionalOrderId: "a\u0001b" }],
  ["C1 ID", "cancel_conditional_order", { conditionalOrderId: "a\u0085b" }],
  ["Cs ID", "cancel_conditional_order", { conditionalOrderId: "a\ud800b" }],
  ["점 경로", "cancel_conditional_order", { conditionalOrderId: ".." }],
  [
    "PROFIT_RATE 신규 필드",
    "create_conditional_order",
    {
      first: {
        orderSide: "SELL",
        triggerPrice: "1",
        orderPrice: "1",
        targetProfitRate: "1",
      },
    },
  ],
  [
    "OCO 감시가 역순",
    "create_conditional_order",
    { second: { orderSide: "SELL", triggerPrice: "120", orderPrice: "120" } },
  ],
  ["지난 만료일", "create_conditional_order", { expireDate: "2026-01-01" }],
  ["MARKET OCO", "create_conditional_order", { orderType: "MARKET" }],
] as const) {
  test(`prepare 거절 ${label}: 금융 0회`, async () => {
    const { fake, env } = setup(name);
    try {
      await expect(
        prepareConditionalTrade(
          new Tossinvest({ apiBase: fake.url, env }),
          name,
          {
            ...models()[name],
            ...model,
          },
        ),
      ).rejects.toThrow(INVALID);
      expect(financial(fake)).toBe(0);
    } finally {
      fake.stop();
    }
  });
}

test("prepare 는 KR 소수 수량과 OTO first 체결 원주문의 취소를 받는다", async () => {
  const { fake, env } = setup("cancel_conditional_order");
  try {
    const client = new Tossinvest({ apiBase: fake.url, env });
    const cancel = await prepareConditionalTrade(
      client,
      "cancel_conditional_order",
      {
        conditionalOrderId: "cond-1",
      },
    );
    expect(cancel.summary.quantity).toBe("0.5");
    expect(cancel.summary.expireDate).toBeNull();
    expect(
      Object.hasOwn(
        cancel.executionArgs.expected_conditional_order,
        "expireDate",
      ),
    ).toBe(false);
    expect(cancel.summary.first.status).toBe("ORDERED");
  } finally {
    fake.stop();
  }
});

for (const [label, name, after] of [
  [
    "원주문 상태 변화",
    "modify_conditional_order",
    (fake: any) =>
      fake.on("GET", "/api/v1/conditional-orders/cond-1", {
        result: { ...originals.modify_conditional_order, status: "PAUSED" },
      }),
  ],
  [
    "원주문 null→부재",
    "cancel_conditional_order",
    (fake: any) => {
      const { second, ...rest } = originals.cancel_conditional_order;
      fake.on("GET", "/api/v1/conditional-orders/cond-1", {
        result: { ...rest, second: { ...second, triggeredOrderId: undefined } },
      });
    },
  ],
  [
    "OCO 현재가 이탈",
    "create_conditional_order",
    (fake: any) =>
      fake.on("GET", "/api/v1/prices", {
        result: [
          { symbol: "ABC", timestamp: null, lastPrice: "110", currency: "USD" },
        ],
      }),
  ],
] as const) {
  test(`claim 뒤 ${label}: claim 1회·금융 0회`, async () => {
    const { fake, env } = setup(name);
    try {
      const args = await prepared(name, fake, env);
      after(fake);
      const result = await execute(name, fake, env, args);
      expect(result.body).toEqual({ error: { code: INVALID } });
      expect(result.claims).toBe(1);
      expect(financial(fake)).toBe(0);
    } finally {
      fake.stop();
    }
  });
}

for (const stage of ["GET", "auth", "quota"] as const) {
  test(`CASE-DEADLINE claim 뒤 ${stage} 에서 만료: 금융 0회`, async () => {
    const name =
      stage === "GET" ? "modify_conditional_order" : "create_conditional_order";
    const { fake, env } = setup(name);
    try {
      const args = await prepared(name, fake, env);
      const original = fake.routes.get(
        stage === "GET"
          ? "GET /api/v1/conditional-orders/cond-1"
          : "POST /oauth2/token",
      )!;
      if (stage === "GET")
        fake.routes.set(
          "GET /api/v1/conditional-orders/cond-1",
          async (request) => {
            await sleep(900);
            return typeof original === "function"
              ? original(request)
              : original.clone();
          },
        );
      if (stage === "auth") {
        clearClientState(fake.url);
        fake.routes.set("POST /oauth2/token", async (request) => {
          await sleep(900);
          return typeof original === "function"
            ? original(request)
            : original.clone();
        });
      }
      if (stage === "quota") {
        // 같은 identity 의 조건주문 그룹이 429 로 막혀 있다. 대기가 deadline 을 넘으므로 시작하지 않는다.
        const identity = `${fake.url}\n${credentials.TOSSINVEST_CLIENT_ID}`;
        await queueRequest(
          identity,
          "CONDITIONAL_ORDER",
          clock.now() + 1000,
          async () => {},
        );
        observeRateLimit(
          identity,
          "CONDITIONAL_ORDER",
          429,
          new Headers({ "retry-after": "30" }),
        );
      }
      const result = await execute(name, fake, env, args, { duration: 600 });
      expect(result.isError).toBe(true);
      expect(result.body).not.toEqual({ error: { code: UNKNOWN } });
      expect(result.claims).toBe(1);
      expect(financial(fake)).toBe(0);
    } finally {
      fake.stop();
    }
  });
}

for (const stage of ["fetch", "본문 읽기"] as const) {
  test(`CASE-DEADLINE 남은 시간 안에 ${stage} 를 끝내지 못하면 abort 하고 UNKNOWN·재전송 0회`, async () => {
    const name = "create_conditional_order";
    const { fake, env } = setup(name);
    try {
      const args = await prepared(name, fake, env);
      fake.routes.set("POST /api/v1/conditional-orders", async () => {
        if (stage === "fetch") await sleep(2500);
        return new Response(
          new ReadableStream({
            async start(controller) {
              await sleep(stage === "fetch" ? 0 : 2500);
              controller.enqueue(
                new TextEncoder().encode(
                  '{"result":{"conditionalOrderId":"n"}}',
                ),
              );
              controller.close();
            },
          }),
          { status: 200, headers: { "content-type": "application/json" } },
        );
      });
      const started = performance.now();
      const result = await execute(name, fake, env, args, {
        duration: 700,
        timeoutMs: 4000,
      });
      const elapsed = performance.now() - started;
      expect(result.body).toEqual({ error: { code: UNKNOWN } });
      expect(financial(fake)).toBe(1);
      // 요청 제한 4초가 아니라 ticket 남은 시간에 끊는다.
      expect(elapsed).toBeLessThan(2000);
      console.info(
        `CASE-DEADLINE ${stage}: ${Math.round(elapsed)}ms UNKNOWN 금융POST=1`,
      );
    } finally {
      fake.stop();
    }
  });
}

for (const [label, change] of [
  [
    "cold auth 지연",
    (fake: any) => {
      clearClientState(fake.url);
      const original = fake.routes.get("POST /oauth2/token");
      fake.routes.set("POST /oauth2/token", async (request: any) => {
        await sleep(1200);
        return original(request);
      });
    },
  ],
  [
    "cold auth 429",
    (fake: any) => {
      clearClientState(fake.url);
      fake.on("POST", "/oauth2/token", { error: "slow_down" }, 429);
    },
  ],
  [
    "READ 429 두 번",
    (fake: any) =>
      fake.on("GET", "/api/v1/stocks", envelope("rate-limited"), 429),
  ],
  [
    "1 MiB 초과 응답",
    (fake: any) =>
      fake.routes.set(
        "GET /api/v1/accounts",
        () =>
          new Response(" ".repeat(1024 * 1024 + 1), {
            headers: { "content-type": "application/json" },
          }),
      ),
  ],
] as const) {
  test(`CASE-PREPARE-BUDGET ${label}: prepare 실패·금융 0회`, async () => {
    const { fake, env } = setup("create_conditional_order");
    try {
      change(fake);
      await expect(
        prepareConditionalTrade(
          new Tossinvest({ apiBase: fake.url, env, timeoutMs: 1000 }),
          "create_conditional_order",
          models().create_conditional_order,
        ),
      ).rejects.toThrow();
      expect(financial(fake)).toBe(0);
    } finally {
      fake.stop();
    }
  });
}
