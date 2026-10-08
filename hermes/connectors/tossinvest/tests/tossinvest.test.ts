import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createTossinvestServer } from "../src/server.ts";
import {
  FakeToss,
  accountNo,
  apiError,
  credentials,
  expectFailure,
  json,
  tokenPrefix,
  tool,
  withMcp,
} from "./support.ts";

const manifest = JSON.parse(
  readFileSync(join(import.meta.dir, "../connector.json"), "utf8"),
);

const accountRows = {
  result: [
    { accountNo, accountSeq: 7, accountType: "BROKERAGE" },
    { accountNo: "99900001234", accountSeq: "8", accountType: "FUTURE_TYPE" },
  ],
};
const priceRows = {
  result: [
    { symbol: "005930", timestamp: "2026-01-02T00:00:00Z", lastPrice: "70000", currency: "KRW" },
    { symbol: "AAPL", timestamp: "2026-01-02T00:00:01Z", lastPrice: "190.5", currency: "USD" },
  ],
};
const stockRows = {
  result: [
    { symbol: "005930", name: "가상전자" },
    { symbol: "AAPL", name: "Fake Apple" },
  ],
};

function setup(options: Record<string, unknown> = {}) {
  const fake = new FakeToss();
  const server = createTossinvestServer({
    apiBase: fake.url,
    env: credentials,
    ...options,
  });
  return { fake, server };
}

describe("토스증권 커넥터 계약", () => {
  test("manifest 의 도구 선언과 서버의 도구 목록이 같고 모두 읽기 전용이다", async () => {
    await withMcp(createTossinvestServer({ env: {} }), async (client) => {
      const tools = (await client.listTools()).tools;
      expect(new Set(tools.map((item) => item.name))).toEqual(
        new Set(Object.keys(manifest.tools)),
      );
      for (const item of tools) {
        expect(item.annotations?.readOnlyHint).toBe(true);
        expect(item.description?.trim().length).toBeGreaterThan(20);
      }
    });
  });

  test("manifest 는 확인 도구와 선택지 도구로 list_accounts 를 쓴다", () => {
    expect(manifest.id).toBe("tossinvest");
    expect(manifest.verify).toEqual({ tool: "list_accounts" });
    expect(manifest.single_binding).toBe(true);
    expect(manifest.sandbox_required).toBe(true);
    const account = manifest.fields.find(
      (field: { key: string }) => field.key === "account",
    );
    expect(account.options).toMatchObject({
      tool: "list_accounts",
      items: "accounts",
      value: "account_seq",
      label: "label",
    });
  });
});

describe("list_accounts", () => {
  test("form 으로 토큰을 받고 Bearer 로 계좌를 읽어 끝 네 자리만 돌려준다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/accounts", accountRows);
    try {
      await withMcp(server, async (client) => {
        expect((await tool(client, "list_accounts")).body).toEqual({
          accounts: [
            { account_seq: "7", account_type: "BROKERAGE", label: "종합매매 ****8901" },
            { account_seq: "8", account_type: "FUTURE_TYPE", label: "기타 ****1234" },
          ],
        });
      });
      const token = fake.seen("POST", "/oauth2/token")[0]!;
      expect(token.headers.get("content-type")).toContain(
        "application/x-www-form-urlencoded",
      );
      expect(new URLSearchParams(token.body).get("grant_type")).toBe(
        "client_credentials",
      );
      expect(new URLSearchParams(token.body).get("client_id")).toBe(
        credentials.TOSSINVEST_CLIENT_ID,
      );
      const accounts = fake.seen("GET", "/api/v1/accounts")[0]!;
      expect(accounts.headers.get("authorization")).toBe(
        `Bearer ${tokenPrefix}1`,
      );
      expect(accounts.headers.get("x-tossinvest-account")).toBeNull();
    } finally {
      fake.stop();
    }
  });

  test("순번이 숫자 1자리에서 10자리가 아닌 계좌는 결과에서 뺀다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/accounts", {
      result: [
        { accountNo, accountSeq: "99999999999", accountType: "BROKERAGE" },
        { accountNo, accountSeq: "seq-7", accountType: "BROKERAGE" },
        { accountNo, accountSeq: "", accountType: "BROKERAGE" },
        { accountNo, accountSeq: 1234567890, accountType: "BROKERAGE" },
      ],
    });
    try {
      await withMcp(server, async (client) => {
        expect((await tool(client, "list_accounts")).body).toEqual({
          accounts: [
            { account_seq: "1234567890", account_type: "BROKERAGE", label: "종합매매 ****8901" },
          ],
        });
      });
    } finally {
      fake.stop();
    }
  });

  test("accountType 이 글이 아니면 account_type 은 null 이고 이름표는 기타다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/accounts", {
      result: [{ accountNo, accountSeq: 7, accountType: { kind: "BROKERAGE" } }],
    });
    try {
      await withMcp(server, async (client) => {
        expect((await tool(client, "list_accounts")).body).toEqual({
          accounts: [{ account_seq: "7", account_type: null, label: "기타 ****8901" }],
        });
      });
    } finally {
      fake.stop();
    }
  });

  test("client 값이 비어 있으면 요청 없이 거절한다", async () => {
    const { fake, server } = setup({ env: { TOSSINVEST_CLIENT_ID: "only-id" } });
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "TOSSINVEST_UNAUTHORIZED", "list_accounts"),
      );
      expect(fake.requests).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });
});

describe("get_quotes", () => {
  test("prices 와 stocks 를 같은 symbols 로 부르고 이름을 잇는다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/prices", priceRows);
    fake.on("GET", "/api/v1/stocks", stockRows);
    try {
      await withMcp(server, async (client) => {
        expect(
          (await tool(client, "get_quotes", { symbols: " 005930 , AAPL,, " })).body,
        ).toEqual({
          quotes: [
            { symbol: "005930", name: "가상전자", last_price: "70000", currency: "KRW", timestamp: "2026-01-02T00:00:00Z" },
            { symbol: "AAPL", name: "Fake Apple", last_price: "190.5", currency: "USD", timestamp: "2026-01-02T00:00:01Z" },
          ],
        });
      });
      for (const path of ["/api/v1/prices", "/api/v1/stocks"]) {
        const request = fake.seen("GET", path)[0]!;
        expect(request.query.get("symbols")).toBe("005930,AAPL");
        expect(request.headers.get("x-tossinvest-account")).toBeNull();
      }
    } finally {
      fake.stop();
    }
  });

  test("객체나 배열인 값은 null 이고 64자를 넘는 글은 잘린다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/prices", {
      result: [
        { symbol: "AAA", lastPrice: { raw: 1 }, currency: ["KRW"], timestamp: "t".repeat(80) },
      ],
    });
    fake.on("GET", "/api/v1/stocks", { result: [] });
    try {
      await withMcp(server, async (client) => {
        expect((await tool(client, "get_quotes", { symbols: "AAA" })).body).toEqual({
          quotes: [
            { symbol: "AAA", name: null, last_price: null, currency: null, timestamp: "t".repeat(64) },
          ],
        });
      });
    } finally {
      fake.stop();
    }
  });

  test("이름이 없는 종목은 null 이고 100자를 넘는 이름은 잘린다", async () => {
    const { fake, server } = setup();
    const longName = "가".repeat(150);
    fake.on("GET", "/api/v1/prices", {
      result: [
        { symbol: "AAA", lastPrice: 1, currency: "KRW", timestamp: "t" },
        { symbol: "BBB", lastPrice: 2, currency: "KRW", timestamp: "t" },
      ],
    });
    fake.on("GET", "/api/v1/stocks", { result: [{ symbol: "AAA", name: longName }] });
    try {
      await withMcp(server, async (client) => {
        const { body } = await tool(client, "get_quotes", { symbols: "AAA,BBB" });
        const quotes = body.quotes as Array<{ symbol: string; name: string | null }>;
        expect(quotes[0]!.name).toBe("가".repeat(100));
        expect(quotes[1]!.name).toBeNull();
      });
    } finally {
      fake.stop();
    }
  });

  test.each([
    ["", "빈 값"],
    [" , ", "쉼표만"],
    ["005930;AAPL", "허용하지 않는 글자"],
    ["A".repeat(13), "13자"],
    [Array.from({ length: 21 }, (_, index) => `S${index}`).join(","), "21개"],
  ])("symbols %j(%s)는 요청 없이 TOSSINVEST_INVALID_INPUT 이다", async (symbols) => {
    const { fake, server } = setup();
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "TOSSINVEST_INVALID_INPUT", "get_quotes", { symbols }),
      );
      expect(fake.requests).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });

  test("20개는 받는다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/prices", { result: [] });
    fake.on("GET", "/api/v1/stocks", { result: [] });
    const symbols = Array.from({ length: 20 }, (_, index) => `S${index}`).join(",");
    try {
      await withMcp(server, async (client) => {
        expect((await tool(client, "get_quotes", { symbols })).body).toEqual({ quotes: [] });
      });
      expect(fake.seen("GET", "/api/v1/prices")[0]!.query.get("symbols")).toBe(symbols);
    } finally {
      fake.stop();
    }
  });
});

describe("토큰", () => {
  test("두 번 불러도 토큰 요청은 한 번이다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/accounts", accountRows);
    try {
      await withMcp(server, async (client) => {
        await tool(client, "list_accounts");
        await tool(client, "list_accounts");
      });
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
      expect(fake.seen("GET", "/api/v1/accounts")).toHaveLength(2);
    } finally {
      fake.stop();
    }
  });

  test("토큰이 없는 상태에서 두 도구를 동시에 불러도 토큰 요청은 한 번이다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/accounts", accountRows);
    fake.on("GET", "/api/v1/prices", priceRows);
    fake.on("GET", "/api/v1/stocks", stockRows);
    try {
      await withMcp(server, async (client) => {
        const [accounts, quotes] = await Promise.all([
          tool(client, "list_accounts"),
          tool(client, "get_quotes", { symbols: "005930,AAPL" }),
        ]);
        expect(accounts.result.isError).toBeUndefined();
        expect(quotes.result.isError).toBeUndefined();
      });
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
      for (const request of fake.seen("GET"))
        expect(request.headers.get("authorization")).toBe(`Bearer ${tokenPrefix}1`);
    } finally {
      fake.stop();
    }
  });

  test("token-revoked 면 토큰을 다시 받아 한 번만 다시 보내고 성공한다", async () => {
    const { fake, server } = setup();
    fake.sequence("GET", "/api/v1/accounts", [
      apiError(401, "token-revoked"),
      () => json(accountRows),
    ]);
    try {
      await withMcp(server, async (client) => {
        const { body } = await tool(client, "list_accounts");
        expect((body.accounts as unknown[]).length).toBe(2);
      });
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(2);
      const calls = fake.seen("GET", "/api/v1/accounts");
      expect(calls.map((call) => call.headers.get("authorization"))).toEqual([
        `Bearer ${tokenPrefix}1`,
        `Bearer ${tokenPrefix}2`,
      ]);
    } finally {
      fake.stop();
    }
  });

  test.each(["token-revoked", "expired-token"])(
    "%s 뒤 다시 받아 보낸 요청이 invalid-token 이면 UNAUTHORIZED 이고 API 요청은 둘이다",
    async (first) => {
      const { fake, server } = setup();
      fake.sequence("GET", "/api/v1/accounts", [
        apiError(401, first),
        apiError(401, "invalid-token"),
      ]);
      try {
        await withMcp(server, (client) =>
          expectFailure(client, "TOSSINVEST_UNAUTHORIZED", "list_accounts"),
        );
        expect(fake.seen("GET", "/api/v1/accounts")).toHaveLength(2);
        expect(fake.seen("POST", "/oauth2/token")).toHaveLength(2);
      } finally {
        fake.stop();
      }
    },
  );

  test.each([
    [{ expires_in: 30 }, "expires_in 이 여유 60초보다 짧다"],
    [{}, "expires_in 이 없다"],
  ])("토큰 응답 %j(%s)이어도 잇단 두 호출은 토큰을 한 번만 받는다", async (extra) => {
    const { fake, server } = setup();
    fake.routes.set("POST /oauth2/token", () => {
      fake.issued += 1;
      return json({ access_token: `${tokenPrefix}${fake.issued}`, token_type: "Bearer", ...extra });
    });
    fake.on("GET", "/api/v1/accounts", accountRows);
    try {
      await withMcp(server, async (client) => {
        await tool(client, "list_accounts");
        await tool(client, "list_accounts");
      });
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
      for (const request of fake.seen("GET", "/api/v1/accounts"))
        expect(request.headers.get("authorization")).toBe(`Bearer ${tokenPrefix}1`);
    } finally {
      fake.stop();
    }
  });

  test("다시 보낸 요청도 token-revoked 면 UNAVAILABLE 이다", async () => {
    const { fake, server } = setup();
    fake.sequence("GET", "/api/v1/accounts", [apiError(401, "token-revoked")]);
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "TOSSINVEST_UNAVAILABLE", "list_accounts"),
      );
      expect(fake.seen("GET", "/api/v1/accounts")).toHaveLength(2);
    } finally {
      fake.stop();
    }
  });

  test("invalid-token 은 다시 받지 않고 UNAUTHORIZED 다", async () => {
    const { fake, server } = setup();
    fake.fail("GET", "/api/v1/accounts", 401, "invalid-token");
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "TOSSINVEST_UNAUTHORIZED", "list_accounts"),
      );
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
      expect(fake.seen("GET", "/api/v1/accounts")).toHaveLength(1);
    } finally {
      fake.stop();
    }
  });

  test.each([
    [401, "invalid_client", "TOSSINVEST_UNAUTHORIZED"],
    [400, "invalid_request", "TOSSINVEST_UNAUTHORIZED"],
    [403, "edge-blocked", "TOSSINVEST_UNAUTHORIZED"],
    [403, "access_denied", "TOSSINVEST_IP_NOT_ALLOWED"],
    [429, "rate_limited", "TOSSINVEST_RATE_LIMITED"],
    [500, "server_error", "TOSSINVEST_UNAVAILABLE"],
  ])("토큰 발급 %i %s 는 %s 다", async (status, error, code) => {
    const { fake, server } = setup();
    fake.on(
      "POST",
      "/oauth2/token",
      { error, error_description: "private upstream description" },
      status,
    );
    try {
      await withMcp(server, (client) => expectFailure(client, code, "list_accounts"));
      expect(fake.seen("GET")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });
});

describe("API 오류", () => {
  test.each([
    [403, "ip-not-allowed", "TOSSINVEST_IP_NOT_ALLOWED"],
    [400, "account-not-found", "TOSSINVEST_ACCOUNT_NOT_FOUND"],
    [404, "account-not-found", "TOSSINVEST_ACCOUNT_NOT_FOUND"],
    [403, "forbidden", "TOSSINVEST_FORBIDDEN"],
    [400, "bad-request", "TOSSINVEST_INVALID_INPUT"],
    [404, "stock-not-found", "TOSSINVEST_INVALID_INPUT"],
    [404, "not-found", "TOSSINVEST_UNAVAILABLE"],
    [429, "too-many-requests", "TOSSINVEST_RATE_LIMITED"],
    [500, "internal", "TOSSINVEST_UNAVAILABLE"],
  ])("API %i %s 는 %s 이고 서비스의 message 를 싣지 않는다", async (status, code, expected) => {
    const { fake, server } = setup();
    fake.fail("GET", "/api/v1/accounts", status, code);
    try {
      await withMcp(server, (client) => expectFailure(client, expected, "list_accounts"));
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
    } finally {
      fake.stop();
    }
  });

  test("JSON 이 아닌 응답은 UNAVAILABLE 이다", async () => {
    const { fake, server } = setup();
    fake.routes.set("GET /api/v1/accounts", new Response("<html>", { status: 200 }));
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "TOSSINVEST_UNAVAILABLE", "list_accounts"),
      );
    } finally {
      fake.stop();
    }
  });

  test("1MB 를 넘는 응답은 UNAVAILABLE 이다", async () => {
    const { fake, server } = setup();
    fake.routes.set(
      "GET /api/v1/accounts",
      () => json({ result: [], padding: "x".repeat(1024 * 1024 + 1) }),
    );
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "TOSSINVEST_UNAVAILABLE", "list_accounts"),
      );
    } finally {
      fake.stop();
    }
  });

  test("머리만 보내고 본문을 제한 시간 안에 끝내지 않는 서비스도 NETWORK 다", async () => {
    const { fake, server } = setup({ timeoutMs: 200 });
    fake.routes.set(
      "GET /api/v1/accounts",
      () =>
        new Response(
          new ReadableStream({
            start(controller) {
              controller.enqueue(new TextEncoder().encode("{"));
            },
          }),
          { status: 200, headers: { "content-type": "application/json" } },
        ),
    );
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "TOSSINVEST_NETWORK", "list_accounts"),
      );
    } finally {
      fake.stop();
    }
  });

  test("응답하지 않는 서비스는 제한 시간 뒤 NETWORK 다", async () => {
    const { fake, server } = setup({ timeoutMs: 200 });
    fake.routes.set("GET /api/v1/accounts", () => new Promise<Response>(() => {}));
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "TOSSINVEST_NETWORK", "list_accounts"),
      );
    } finally {
      fake.stop();
    }
  });

  test("닿지 않는 주소는 NETWORK 다", async () => {
    const fake = new FakeToss();
    const unreachable = fake.url;
    fake.server.stop(true);
    const server = createTossinvestServer({ apiBase: unreachable, env: credentials, timeoutMs: 500 });
    await withMcp(server, (client) =>
      expectFailure(client, "TOSSINVEST_NETWORK", "list_accounts"),
    );
  });
});
