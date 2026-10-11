import { expect, test } from "bun:test";
import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { resolve } from "node:path";
import { Tossinvest } from "../src/client.ts";
import { strictJson, sha256 } from "../src/execution-guard.ts";
import { queueRequest } from "../src/rate-limit.ts";
import { createTossinvestServer } from "../src/server.ts";
import { UNKNOWN } from "../src/trading-body.ts";
import {
  ClaimFixture,
  createTradingFixtureServer,
  requireLoopback,
} from "./fixtures/trading-entry.ts";
import { OPERATIONS } from "../src/api-contract.ts";
import {
  FakeToss,
  credentials,
  json,
  sampleOrder,
  withMcp,
  tool,
} from "./support.ts";

const all = new Set(OPERATIONS.map((operation) => operation.id));
const account = "9223372036854775807";
const original = {
  ...sampleOrder("old"),
  symbol: "ABC",
  price: "12",
  quantity: "10",
  orderAmount: null,
};
const expected = {
  orderId: "old",
  symbol: "ABC",
  currency: "USD",
  side: "BUY",
  quantity: "10",
  orderAmount: null,
  price: "12",
  orderType: "LIMIT",
  timeInForce: "DAY",
  status: "PENDING",
  execution: { filledQuantity: "0" },
};
const create = () => ({
  symbol: "ABC",
  side: "BUY",
  orderType: "LIMIT",
  quantity: "2",
  price: "12",
  timeInForce: "DAY",
  clientOrderId: randomUUID(),
  account_seq: account,
  market: "NYSE",
  currency: "USD",
});
function setup() {
  const fake = new FakeToss(all);
  fake.on("POST", "/api/v1/orders", { result: { orderId: "new" } });
  fake.on("GET", "/api/v1/orders/old", { result: original });
  return {
    fake,
    claims: new ClaimFixture(),
    env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: account } as Record<
      string,
      string | undefined
    >,
  };
}
const financialCount = (fake: FakeToss) =>
  fake.seen("POST").filter((request) => request.path !== "/oauth2/token")
    .length + fake.seen("DELETE").length;

for (const raw of [
  '{"a":1,"a":2}',
  '{"a":1,"\\u0061":1}',
  '{"nested":{"a":1,"a":1}}',
  "{}{}",
  '{"a":NaN}',
  '{"a":"\\ud800"}',
  "[[[[[[0]]]]]]",
]) {
  test(`strict 원문 거절 ${raw}`, () =>
    expect(() => strictJson(raw)).toThrow("TOSSINVEST_INVALID_INPUT"));
}
test("strict 원문은 공백과 순서를 보존한 바이트 해시를 쓰며 재직렬화하지 않는다", () => {
  const raw = ' { "b": "02", "a": false }\n';
  expect(strictJson(raw)).toEqual({ b: "02", a: false });
  expect(sha256(raw)).not.toBe(sha256(JSON.stringify(strictJson(raw))));
});

for (const mode of [
  "no-approval",
  "changed-value",
  "numeric",
  "extra-key",
  "missing-key",
  "env-account",
  "duplicate-raw",
  "changed-hash",
  "expired",
  "wrong-tool",
]) {
  test(`SDK ${mode}: 금융 POST/DELETE 0회, 권한 env 폐기`, async () => {
    const { fake, claims, env } = setup();
    try {
      const args: Record<string, any> = create();
      const approved = { ...args };
      if (mode !== "no-approval")
        Object.assign(
          env,
          claims.authority(
            "create_order",
            approved,
            mode === "expired" ? -1 : 59000,
          ),
        );
      if (mode === "changed-value") args.price = "13";
      if (mode === "numeric") args.quantity = 2;
      if (mode === "extra-key") args.unapproved = "extra";
      if (mode === "missing-key") delete args.price;
      if (mode === "env-account") env.TOSSINVEST_ACCOUNT_SEQ = "7";
      if (mode === "duplicate-raw")
        env.FOS_APPROVAL_ARGS_JSON = JSON.stringify(approved).replace(
          '"price":"12"',
          '"price":"12","price":"12"',
        );
      if (mode === "changed-hash")
        env.FOS_APPROVAL_ARGS_JSON = " " + env.FOS_APPROVAL_ARGS_JSON;
      if (mode === "wrong-tool")
        Object.assign(env, claims.authority("modify_order", approved));
      const server = createTradingFixtureServer({ apiBase: fake.url, env });
      expect(
        Object.keys(env).some((name) => name.startsWith("FOS_APPROVAL_")),
      ).toBe(false);
      await withMcp(server, async (client) => {
        const result = await client.callTool({
          name: "create_order",
          arguments: args,
        });
        expect(result.isError).toBe(true);
      });
      expect(financialCount(fake)).toBe(0);
      expect(claims.requests).toHaveLength(0);
    } finally {
      claims.stop();
      fake.stop();
    }
  });
}

for (const mode of [
  "allowed-false",
  "boolean-version",
  "wrong-id",
  "wrong-expiry",
  "extra",
  "redirect",
  "missing",
  "duplicate",
]) {
  test(`claim 응답 ${mode}: 금융 0회, claim redirect/재시도 0회`, async () => {
    const { fake, claims, env } = setup();
    try {
      const args = create();
      const auth = claims.authority("create_order", args);
      const payload = claims.issued.get(auth.FOS_APPROVAL_TICKET);
      claims.respond = () => {
        const response: any = {
          v: 1,
          allowed: true,
          ticketId: payload.ticketId,
          expiresAt: payload.expiresAt,
        };
        if (mode === "allowed-false") response.allowed = false;
        if (mode === "boolean-version") response.v = true;
        if (mode === "wrong-id") response.ticketId = randomUUID();
        if (mode === "wrong-expiry")
          response.expiresAt = new Date(Date.now() + 50000).toISOString();
        if (mode === "extra") response.extra = "field";
        if (mode === "missing") delete response.ticketId;
        if (mode === "redirect")
          return new Response(null, {
            status: 307,
            headers: { location: fake.url + "/api/v1/orders" },
          });
        if (mode === "duplicate")
          return new Response(
            JSON.stringify(response).replace('"v":1', '"v":1,"v":1'),
          );
        return json(response);
      };
      Object.assign(env, auth);
      await withMcp(
        createTradingFixtureServer({ apiBase: fake.url, env }),
        async (client) => {
          expect(
            (await tool(client, "create_order", args)).result.isError,
          ).toBe(true);
        },
      );
      expect(claims.requests).toHaveLength(1);
      expect(financialCount(fake)).toBe(0);
      expect(fake.seen("GET")).toHaveLength(0);
    } finally {
      claims.stop();
      fake.stop();
    }
  });
}

test("다른 SDK 자식에서도 replay 409면 추가 금융 POST 0회다", async () => {
  const { fake, claims, env } = setup();
  try {
    const args = create();
    const auth = claims.authority("create_order", args);
    for (let attempt = 0; attempt < 2; attempt++) {
      const childEnv = { ...env, ...auth };
      await withMcp(
        createTradingFixtureServer({ apiBase: fake.url, env: childEnv }),
        async (client) => {
          expect(
            Boolean((await tool(client, "create_order", args)).result.isError),
          ).toBe(attempt === 1);
        },
      );
    }
    expect(claims.requests).toHaveLength(2);
    expect(financialCount(fake)).toBe(1);
  } finally {
    claims.stop();
    fake.stop();
  }
});

for (const field of ["price", "quantity", "status", "filledQuantity"]) {
  test(`정정 claim 뒤 원주문 ${field} 변경: 금융 0회`, async () => {
    const { fake, claims, env } = setup();
    try {
      const args = {
        orderId: "old",
        orderType: "LIMIT",
        price: "13",
        account_seq: account,
        market: "NYSE",
        currency: "USD",
        expected_order: expected,
      };
      Object.assign(env, claims.authority("modify_order", args));
      const changed: any = {
        ...original,
        execution: { ...original.execution },
      };
      if (field === "filledQuantity") changed.execution.filledQuantity = "1";
      else changed[field] = field === "status" ? "FILLED" : "11";
      fake.on("GET", "/api/v1/orders/old", { result: changed });
      await withMcp(
        createTradingFixtureServer({ apiBase: fake.url, env }),
        async (client) => {
          expect(
            (await tool(client, "modify_order", args)).result.isError,
          ).toBe(true);
        },
      );
      expect(claims.requests).toHaveLength(1);
      expect(fake.seen("GET", "/api/v1/orders/old")).toHaveLength(1);
      expect(financialCount(fake)).toBe(0);
    } finally {
      claims.stop();
      fake.stop();
    }
  });
}

for (const phase of ["GET", "auth", "quota"]) {
  test(`claim 뒤 ${phase} 대기 중 만료: 금융 queue callback까지 POST 0회`, async () => {
    const { fake, claims, env } = setup();
    let queued: Promise<unknown> | undefined;
    try {
      let args: Record<string, any> = create();
      let name = "create_order";
      if (phase === "GET") {
        name = "modify_order";
        args = {
          orderId: "old",
          orderType: "LIMIT",
          price: "13",
          account_seq: account,
          market: "NYSE",
          currency: "USD",
          expected_order: expected,
        };
        fake.routes.set("GET /api/v1/orders/old", async () => {
          await Bun.sleep(150);
          return json({ result: original });
        });
      } else if (phase === "auth") {
        fake.routes.set("POST /oauth2/token", async () => {
          await Bun.sleep(150);
          return json({
            access_token: "fake-access-token-delayed",
            token_type: "Bearer",
            expires_in: 3600,
          });
        });
      } else {
        await new Tossinvest({ apiBase: fake.url, env }).token();
        queued = queueRequest(
          `${fake.url}\n${credentials.TOSSINVEST_CLIENT_ID}`,
          "ORDER",
          Date.now() + 2000,
          () => Bun.sleep(150),
        );
      }
      Object.assign(env, claims.authority(name, args, 80));
      await withMcp(
        createTradingFixtureServer({ apiBase: fake.url, env }),
        async (client) => {
          expect((await tool(client, name, args)).result.isError).toBe(true);
        },
      );
      await queued;
      expect(claims.requests).toHaveLength(1);
      expect(financialCount(fake)).toBe(0);
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
      expect(fake.seen("GET")).toHaveLength(phase === "GET" ? 1 : 0);
    } finally {
      await queued;
      claims.stop();
      fake.stop();
    }
  });
}

for (const failure of [
  "429",
  "500",
  "timeout",
  "malformed",
  "missing-result",
  "invalid-client-id",
  "bad-error",
  "unknown-code",
  "processing",
  "wrong-status",
  "redirect",
  "read-failure",
]) {
  test(`${failure}: 금융 POST 1회 UNKNOWN, auth 재발급과 재전송 0회`, async () => {
    const { fake, claims, env } = setup();
    const fetchOriginal = globalThis.fetch;
    try {
      const args = create();
      Object.assign(env, claims.authority("create_order", args));
      if (failure === "429" || failure === "500")
        fake.fail("POST", "/api/v1/orders", Number(failure), "internal-error");
      else if (failure === "timeout")
        fake.routes.set("POST /api/v1/orders", async () => {
          await Bun.sleep(150);
          return json({ result: { orderId: "late" } });
        });
      else if (failure === "malformed")
        fake.routes.set("POST /api/v1/orders", new Response("not json"));
      else if (failure === "missing-result")
        fake.on("POST", "/api/v1/orders", {});
      else if (failure === "invalid-client-id")
        fake.on("POST", "/api/v1/orders", {
          result: { orderId: "new", clientOrderId: 1 },
        });
      else if (failure === "bad-error")
        fake.on(
          "POST",
          "/api/v1/orders",
          { error: { code: "invalid-request" } },
          400,
        );
      else if (failure === "unknown-code")
        fake.fail("POST", "/api/v1/orders", 400, "new-code");
      else if (failure === "processing")
        fake.fail("POST", "/api/v1/orders", 409, "request-in-progress");
      else if (failure === "wrong-status")
        fake.fail("POST", "/api/v1/orders", 422, "invalid-request");
      else if (failure === "redirect")
        fake.routes.set(
          "POST /api/v1/orders",
          new Response(null, {
            status: 307,
            headers: { location: fake.url + "/api/v1/orders" },
          }),
        );
      else
        globalThis.fetch = (async (input: any, init: any) => {
          const response = await fetchOriginal(input, init);
          if (String(input) === fake.url + "/api/v1/orders")
            return {
              status: 200,
              ok: true,
              headers: new Headers(),
              body: new ReadableStream({
                pull(controller) {
                  controller.error(new Error("synthetic-read-failure"));
                },
              }),
            } as Response;
          return response;
        }) as typeof fetch;
      await withMcp(
        createTradingFixtureServer({ apiBase: fake.url, env, timeoutMs: 60 }),
        async (client) => {
          const answer = await tool(client, "create_order", args);
          expect(answer.body).toEqual({ error: { code: UNKNOWN } });
          expect(answer.result.isError).toBe(true);
        },
      );
      expect(claims.requests).toHaveLength(1);
      expect(financialCount(fake)).toBe(1);
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
    } finally {
      globalThis.fetch = fetchOriginal;
      claims.stop();
      fake.stop();
    }
  });
}

test("토큰 실패는 금융 전송 전 FAILED이며 auth와 금융 counter를 구분한다", async () => {
  const { fake, claims, env } = setup();
  try {
    const args = create();
    Object.assign(env, claims.authority("create_order", args));
    fake.on("POST", "/oauth2/token", { error: "invalid_client" }, 401);
    await withMcp(
      createTradingFixtureServer({ apiBase: fake.url, env }),
      async (client) => {
        expect((await tool(client, "create_order", args)).body).toEqual({
          error: { code: "TOSSINVEST_UNAUTHORIZED" },
        });
      },
    );
    expect(claims.requests).toHaveLength(1);
    expect(financialCount(fake)).toBe(0);
  } finally {
    claims.stop();
    fake.stop();
  }
});

test("DELETE 단일전송 transport의 합성 204·불량204: 조건주문 도구 등록 0개", async () => {
  const { fake, env, claims } = setup();
  try {
    const path = "/api/v1/conditional-orders/synthetic";
    fake.routes.set(`DELETE ${path}`, new Response(null, { status: 204 }));
    const client = new Tossinvest({ apiBase: fake.url, env });
    expect(await client.financial("DELETE", path, undefined, () => {})).toEqual(
      {},
    );
    expect(fake.seen("DELETE", path)).toHaveLength(1);
    expect(fake.seen("DELETE", path)[0]!.body).toBe("");
    // 불량204는 실제 socket 응답의 본문 읽기를 대체한다. 목적지에는 DELETE 한 번이 먼저 도착한다.
    const fetchOriginal = globalThis.fetch;
    try {
      globalThis.fetch = (async (input: any, init: any) => {
        const response = await fetchOriginal(input, init);
        if (String(input).endsWith(path))
          return {
            status: 204,
            ok: true,
            headers: new Headers(),
            body: new Response("bad").body,
          } as Response;
        return response;
      }) as typeof fetch;
      await expect(
        client.financial("DELETE", path, undefined, () => {}),
      ).rejects.toThrow(UNKNOWN);
    } finally {
      globalThis.fetch = fetchOriginal;
    }
    expect(fake.seen("DELETE", path)).toHaveLength(2);
    await withMcp(
      createTradingFixtureServer({ apiBase: fake.url, env }),
      async (sdk) => {
        expect(
          (await sdk.listTools()).tools.some((item) =>
            item.name.includes("conditional"),
          ),
        ).toBe(false);
      },
    );
  } finally {
    claims.stop();
    fake.stop();
  }
});

test("운영 SDK·manifest의 READ 합집합과 금융 비활성을 유지한다", async () => {
  const { fake, env, claims } = setup();
  try {
    const manifest = JSON.parse(
      await readFile(resolve(import.meta.dir, "../connector.json"), "utf8"),
    );
    expect(manifest.execution_guard).toBeUndefined();
    expect(manifest.errors.TOSSINVEST_OUTCOME_UNKNOWN).toBe("outcome_unknown");
    expect(
      Object.values(manifest.tools).every(
        (policy: any) => policy.risk === "READ",
      ),
    ).toBe(true);
    await withMcp(
      createTossinvestServer({ apiBase: fake.url, env }),
      async (sdk) => {
        expect(
          (await sdk.listTools()).tools.map((item) => item.name).sort(),
        ).toEqual(Object.keys(manifest.tools).sort());
      },
    );
    expect(fake.requests).toHaveLength(0);
    const root = resolve(import.meta.dir, "../../../..");
    const dtos = await readFile(
      joinRoot(
        root,
        "backend/src/main/java/com/bifos/assistant/connector/presentation/execution/ConnectorExecutionClaimDtos.java",
      ),
      "utf8",
    );
    expect(dtos).toContain(
      "record ClaimResponse(int v, boolean allowed, UUID ticketId, Instant expiresAt)",
    );
    const mvc = await readFile(
      joinRoot(
        root,
        "backend/src/test/java/com/bifos/assistant/connector/ConnectorExecutionClaimMvcTest.java",
      ),
      "utf8",
    );
    expect(mvc).toContain(".isEqualTo(409)");
    expect(mvc).toContain(".isEqualTo(400)");
    expect(mvc).toContain(".isEqualTo(401)");
    const codec = await readFile(
      joinRoot(
        root,
        "backend/src/main/java/com/bifos/assistant/connector/application/execution/ConnectorExecutionClaimCodec.java",
      ),
      "utf8",
    );
    expect(codec).toContain('Set.of("ticket", "tool", "argsSha256", "scope")');
    const claimsSource = await readFile(
      joinRoot(
        root,
        "backend/src/main/java/com/bifos/assistant/connector/application/execution/ConnectorExecutionClaims.java",
      ),
      "utf8",
    );
    expect(claimsSource).toContain("row.consumeTicket");
    expect(claimsSource).toContain("new ConnectorExecutionClaimResult(1, true");
    // 근거 source를 직접 읽는 protocol 대조이며 실제 DB 소비의 시험으로 세지 않는다.
  } finally {
    claims.stop();
    fake.stop();
  }
});
const joinRoot = (root: string, file: string) => resolve(root, file);

test("테스트 목적지와 claim의 실제 서비스 egress를 거절한다", () => {
  expect(() => requireLoopback("https://openapi.tossinvest.com/")).toThrow(
    "TEST_NON_LOOPBACK_REJECTED",
  );
  expect(() => requireLoopback("http://localhost/")).toThrow(
    "TEST_NON_LOOPBACK_REJECTED",
  );
  expect(() =>
    createTradingFixtureServer({ apiBase: "https://openapi.tossinvest.com/" }),
  ).toThrow("TEST_NON_LOOPBACK_REJECTED");
});
