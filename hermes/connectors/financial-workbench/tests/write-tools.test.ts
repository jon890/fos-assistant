import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { createWorkbenchServer } from "../src/server.ts";
import {
  credentials,
  expectFailure,
  FakeWorkbench,
  json,
  nodeId,
  token,
  tool,
  upstreamText,
  withMcp,
} from "./support.ts";

let fake: FakeWorkbench;
beforeEach(() => {
  fake = new FakeWorkbench();
});
afterEach(() => fake.stop());

const server = () =>
  createWorkbenchServer({
    baseUrl: fake.url,
    env: credentials,
    timeoutMs: 500,
  });

const tradeId = "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b";

// 토스증권 주문 하나를 도구 설명의 대응대로 옮긴 값이다. 수는 토스증권의 decimal 글 그대로다.
const tossTrade = {
  externalId: "20261024-000123",
  side: "buy",
  market: "us",
  ticker: "flnc",
  name: "Fluence Energy",
  quantity: "10",
  price: "12.30",
  currency: "usd",
  amount: "123.00",
  commission: "0.1",
  tax: "0.02",
  executedAt: "2026-10-24T23:31:05+09:00",
};

const sentTrade = {
  broker: "TOSS",
  externalId: "20261024-000123",
  side: "BUY",
  market: "US",
  ticker: "FLNC",
  name: "Fluence Energy",
  quantity: 10,
  price: 12.3,
  currency: "USD",
  amount: 123,
  fee: 0.12,
  executedAt: "2026-10-24T23:31:05+09:00",
};

const proposal = {
  kind: "LINK_TRADE_DECISION",
  payload: { tradeIds: [tradeId], decisionId: nodeId },
  rationale: " 10월 24일 FLNC 매수는 같은 날의 결정을 실행한 것이다. ",
  evidence: { tradeIds: [tradeId] },
  dedupeKey: `link:20261024-000123:${nodeId}`,
};

const sentProposal = {
  ...proposal,
  rationale: "10월 24일 FLNC 매수는 같은 날의 결정을 실행한 것이다.",
  evidence: { nodeIds: [], tradeIds: [tradeId], urls: [] },
};

describe("체결 기록", () => {
  test("토스증권 칸을 계약의 칸으로 옮겨 POST 하고 결과를 그대로 돌려준다", async () => {
    const results = {
      results: [
        { index: 0, status: "created", trade: { id: tradeId } },
        { index: 1, status: "existing", trade: { id: tradeId } },
      ],
    };
    fake.onPost("/api/v1/trades", results);
    await withMcp(server(), async (client) => {
      const { result, body } = await tool(client, "record_trades", {
        trades: [tossTrade, tossTrade],
      });
      expect(result.isError).toBeFalsy();
      expect(body).toEqual(results);
    });
    const [request] = fake.seen("/api/v1/trades");
    expect(request!.method).toBe("POST");
    expect(request!.headers.get("authorization")).toBe(`Bearer ${token}`);
    expect(request!.headers.get("content-type")).toBe("application/json");
    expect(JSON.parse(request!.body)).toEqual({
      trades: [sentTrade, sentTrade],
    });
  });

  test("수수료나 세금 하나만 있거나 fee 를 직접 주면 그 값을 fee 로 보낸다", async () => {
    fake.onPost("/api/v1/trades", { results: [] });
    const { commission, tax, ...bare } = tossTrade;
    await withMcp(server(), async (client) => {
      await tool(client, "record_trades", {
        trades: [
          { ...bare, tax: "0.02" },
          { ...bare, fee: 1.5, broker: "other" },
          bare,
        ],
      });
    });
    const sent = JSON.parse(fake.seen("/api/v1/trades")[0]!.body).trades;
    expect(sent[0].fee).toBe(0.02);
    expect(sent[1].fee).toBe(1.5);
    expect(sent[1].broker).toBe("OTHER");
    expect("fee" in sent[2]).toBe(false);
  });

  test("선택 칸의 null 과 빈 글은 없는 값으로 본다", async () => {
    fake.onPost("/api/v1/trades", { results: [] });
    await withMcp(server(), async (client) => {
      const { result } = await tool(client, "record_trades", {
        trades: [
          { ...tossTrade, commission: null, name: "  ", broker: null },
          { ...tossTrade, commission: null, tax: null, fee: null },
        ],
      });
      expect(result.isError).toBeFalsy();
    });
    const sent = JSON.parse(fake.seen("/api/v1/trades")[0]!.body).trades;
    expect(sent[0].fee).toBe(0.02);
    expect(sent[0].broker).toBe("TOSS");
    expect("name" in sent[0]).toBe(false);
    expect("fee" in sent[1]).toBe(false);
  });

  test("계약 밖의 칸이나 틀린 값은 부르지 않고 거절한다", async () => {
    const wrong: Array<Record<string, unknown>> = [
      { ...tossTrade, accountNumber: "123-45" },
      { ...tossTrade, quantity: "0" },
      { ...tossTrade, price: "-1" },
      { ...tossTrade, amount: "1e3" },
      { ...tossTrade, quantity: "0.12345678901234567" },
      { ...tossTrade, executedAt: "2026-10-24T23:31:05" },
      { ...tossTrade, executedAt: "2026-13-40T23:31:05Z" },
      { ...tossTrade, side: "HOLD" },
      { ...tossTrade, market: "LSE" },
      { ...tossTrade, currency: "dollar" },
      { ...tossTrade, externalId: " " },
      { ...tossTrade, fee: "1" },
      { ...tossTrade, broker: "KIWOOM" },
    ];
    await withMcp(server(), async (client) => {
      for (const trade of wrong)
        await expectFailure(
          client,
          { code: "WORKBENCH_INVALID_INPUT" },
          "record_trades",
          { trades: [trade] },
        );
      await expectFailure(
        client,
        { code: "WORKBENCH_INVALID_INPUT" },
        "record_trades",
        { trades: [] },
      );
      await expectFailure(
        client,
        { code: "WORKBENCH_INVALID_INPUT" },
        "record_trades",
        { trades: Array(51).fill(tossTrade) },
      );
    });
    expect(fake.requests).toHaveLength(0);
  });

  test("본문이 64KiB 를 넘으면 부르지 않는다", async () => {
    // 2000자 한글 까닭 20건은 UTF-8 로 120KB 쯤이다.
    const long = { ...proposal, rationale: "가".repeat(2000) };
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_PAYLOAD_TOO_LARGE" },
        "submit_proposals",
        { proposals: Array(20).fill(long) },
      );
    });
    expect(fake.requests).toHaveLength(0);
  });

  test("내 체결 조회는 계약의 쿼리로 부른다", async () => {
    fake.on("/api/v1/trades", { content: [] });
    await withMcp(server(), async (client) => {
      await tool(client, "list_my_trades");
      await tool(client, "list_my_trades", {
        ticker: " FLNC ",
        from: "2026-10-01T00:00:00+09:00",
        to: "2026-11-01T00:00:00Z",
        page: 1,
        size: 50,
      });
      for (const args of [
        { from: "2026-10-01" },
        { ticker: "A".repeat(33) },
        { size: 51 },
      ])
        await expectFailure(
          client,
          { code: "WORKBENCH_INVALID_INPUT" },
          "list_my_trades",
          args,
        );
    });
    const [first, second] = fake.seen("/api/v1/trades");
    expect(fake.seen("/api/v1/trades")).toHaveLength(2);
    expect(Object.fromEntries(first!.query)).toEqual({ page: "0", size: "20" });
    expect(Object.fromEntries(second!.query)).toEqual({
      ticker: "FLNC",
      from: "2026-10-01T00:00:00+09:00",
      to: "2026-11-01T00:00:00Z",
      page: "1",
      size: "50",
    });
  });
});

describe("제안", () => {
  test("제안을 다듬어 POST 하고 결과를 그대로 돌려준다", async () => {
    const results = {
      results: [
        {
          index: 0,
          status: "created",
          proposal: { id: nodeId, status: "PENDING" },
        },
      ],
    };
    fake.onPost("/api/v1/proposals", results);
    await withMcp(server(), async (client) => {
      const { body } = await tool(client, "submit_proposals", {
        proposals: [
          proposal,
          { ...proposal, kind: "study_note", evidence: undefined },
        ],
      });
      expect(body).toEqual(results);
    });
    const { evidence, ...noEvidence } = sentProposal;
    expect(JSON.parse(fake.seen("/api/v1/proposals")[0]!.body)).toEqual({
      proposals: [sentProposal, { ...noEvidence, kind: "STUDY_NOTE" }],
    });
  });

  test("dedupeKey 가 없거나 계약 밖의 값이면 부르지 않고 거절한다", async () => {
    const { dedupeKey, ...noKey } = proposal;
    const wrong: Array<Record<string, unknown>> = [
      noKey,
      { ...proposal, dedupeKey: "k".repeat(101) },
      { ...proposal, kind: "DELETE_NODE" },
      { ...proposal, rationale: "가".repeat(2001) },
      { ...proposal, evidence: { nodeIds: ["not-a-uuid"] } },
      { ...proposal, evidence: { urls: ["javascript:alert(1)"] } },
      {
        ...proposal,
        evidence: { urls: Array(11).fill("https://example.com") },
      },
      { ...proposal, evidence: { other: [] } },
      { ...proposal, status: "ACCEPTED" },
    ];
    await withMcp(server(), async (client) => {
      for (const item of wrong)
        await expectFailure(
          client,
          { code: "WORKBENCH_INVALID_INPUT" },
          "submit_proposals",
          { proposals: [item] },
        );
      await expectFailure(
        client,
        { code: "WORKBENCH_INVALID_INPUT" },
        "submit_proposals",
        { proposals: Array(21).fill(proposal) },
      );
    });
    expect(fake.requests).toHaveLength(0);
  });

  test("내 제안 조회는 status 를 대문자로 보낸다", async () => {
    fake.on("/api/v1/proposals", { content: [] });
    await withMcp(server(), async (client) => {
      await tool(client, "list_my_proposals", { status: "rejected" });
      await expectFailure(
        client,
        { code: "WORKBENCH_INVALID_INPUT" },
        "list_my_proposals",
        { status: "DONE" },
      );
    });
    const requests = fake.seen("/api/v1/proposals");
    expect(requests).toHaveLength(1);
    expect(Object.fromEntries(requests[0]!.query)).toEqual({
      status: "REJECTED",
      page: "0",
      size: "20",
    });
  });
});

describe("쓰기 오류", () => {
  test("조회만 토큰의 403 은 범위 부족 코드로, 다른 403 은 권한 코드로 끝난다", async () => {
    fake.onPost(
      "/api/v1/trades",
      { code: "INSUFFICIENT_SCOPE", message: upstreamText },
      403,
    );
    fake.onPost(
      "/api/v1/proposals",
      { code: "FORBIDDEN", message: upstreamText },
      403,
    );
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_INSUFFICIENT_SCOPE" },
        "record_trades",
        { trades: [tossTrade] },
      );
      await expectFailure(
        client,
        { code: "WORKBENCH_FORBIDDEN" },
        "submit_proposals",
        { proposals: [proposal] },
      );
    });
  });

  test("400 은 몇 번째 항목과 필드 경로, 규칙 이름만 전하고 사유 글은 싣지 않는다", async () => {
    fake.onPost(
      "/api/v1/trades",
      {
        code: "VALIDATION_FAILED",
        message: upstreamText,
        details: {
          fieldErrors: {
            "trades[1].quantity": upstreamText,
            "trades[1].price": upstreamText,
            [`bad key ${upstreamText}`]: upstreamText,
          },
        },
      },
      400,
    );
    fake.onPost(
      "/api/v1/proposals",
      {
        code: "ONTOLOGY_VIOLATION",
        message: upstreamText,
        details: { index: 3, rule: "decision-type" },
      },
      400,
    );
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        {
          code: "WORKBENCH_INVALID_INPUT",
          item_index: 1,
          fields: ["trades[1].quantity", "trades[1].price"],
        },
        "record_trades",
        { trades: [tossTrade, tossTrade] },
      );
      await expectFailure(
        client,
        {
          code: "WORKBENCH_INVALID_INPUT",
          item_index: 3,
          rule: "decision-type",
        },
        "submit_proposals",
        { proposals: [proposal] },
      );
    });
  });

  test("409 PROPOSAL_LIMIT 는 상한과 대기 수를, 다른 409 는 항목 번호를 전한다", async () => {
    fake.onPost(
      "/api/v1/proposals",
      {
        code: "PROPOSAL_LIMIT",
        message: upstreamText,
        details: { max: 200, pending: 199 },
      },
      409,
    );
    fake.onPost(
      "/api/v1/trades",
      { code: "ARCHIVED", message: upstreamText, details: { index: 0 } },
      409,
    );
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_PROPOSAL_LIMIT", max_pending: 200, pending: 199 },
        "submit_proposals",
        { proposals: [proposal] },
      );
      await expectFailure(
        client,
        { code: "WORKBENCH_CONFLICT", item_index: 0 },
        "record_trades",
        { trades: [tossTrade] },
      );
    });
  });

  test("404 는 항목 번호가 있으면 없는 대상, 없으면 기준 주소 오류다", async () => {
    fake.onPost(
      "/api/v1/proposals",
      { code: "NOT_FOUND", message: upstreamText, details: { index: 2 } },
      404,
    );
    fake.onPost(
      "/api/v1/trades",
      { code: "NOT_FOUND", message: upstreamText },
      404,
    );
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_NOT_FOUND", item_index: 2 },
        "submit_proposals",
        { proposals: [proposal] },
      );
      await expectFailure(
        client,
        { code: "WORKBENCH_NOT_CONFIGURED" },
        "record_trades",
        { trades: [tossTrade] },
      );
    });
  });

  test("413 과 쓰기 한도의 429 를 그대로 전하고 다시 부르지 않는다", async () => {
    fake.onPost(
      "/api/v1/trades",
      { code: "PAYLOAD_TOO_LARGE", message: upstreamText },
      413,
    );
    fake.onPost(
      "/api/v1/proposals",
      {
        code: "RATE_LIMITED",
        message: upstreamText,
        details: { retryAfterSeconds: 30 },
      },
      429,
      { "retry-after": "30" },
    );
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_PAYLOAD_TOO_LARGE" },
        "record_trades",
        { trades: [tossTrade] },
      );
      await expectFailure(
        client,
        { code: "WORKBENCH_RATE_LIMITED", retry_after_seconds: 30 },
        "submit_proposals",
        { proposals: [proposal] },
      );
    });
    expect(fake.seen("/api/v1/trades")).toHaveLength(1);
    expect(fake.seen("/api/v1/proposals")).toHaveLength(1);
  });

  test("보낸 뒤 답을 받지 못하면 결과 모름으로 끝나고 다시 보내지 않는다", async () => {
    fake.routes.set(
      "POST /api/v1/trades",
      () =>
        new Promise<Response>((resolve) =>
          setTimeout(() => resolve(json({ results: [] })), 2_000),
        ),
    );
    fake.routes.set(
      "POST /api/v1/proposals",
      new Response("not json", { status: 200 }),
    );
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_WRITE_UNKNOWN" },
        "record_trades",
        { trades: [tossTrade] },
      );
      await expectFailure(
        client,
        { code: "WORKBENCH_WRITE_UNKNOWN" },
        "submit_proposals",
        { proposals: [proposal] },
      );
    });
    expect(fake.seen("/api/v1/trades")).toHaveLength(1);
  });

  test("워크벤치가 되돌렸다고 밝히지 않은 5xx 는 결과 모름으로 끝난다", async () => {
    fake.routes.set(
      "POST /api/v1/trades",
      new Response(`<html>${upstreamText}</html>`, { status: 502 }),
    );
    fake.onPost(
      "/api/v1/proposals",
      { code: "INTERNAL_ERROR", message: upstreamText },
      500,
    );
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_WRITE_UNKNOWN" },
        "record_trades",
        { trades: [tossTrade] },
      );
      await expectFailure(
        client,
        { code: "WORKBENCH_WRITE_UNKNOWN" },
        "submit_proposals",
        { proposals: [proposal] },
      );
    });
    expect(fake.seen("/api/v1/trades")).toHaveLength(1);
  });

  test("잠금 충돌과 워크벤치가 되돌린 5xx 는 저장하지 않은 실패로 끝난다", async () => {
    fake.onPost(
      "/api/v1/trades",
      { code: "TRANSIENT_CONFLICT", message: upstreamText },
      409,
    );
    fake.onPost("/api/v1/proposals", { code: "QUERY_TIMEOUT" }, 503);
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_UNAVAILABLE" },
        "record_trades",
        { trades: [tossTrade] },
      );
      await expectFailure(
        client,
        { code: "WORKBENCH_UNAVAILABLE" },
        "submit_proposals",
        { proposals: [proposal] },
      );
    });
  });
});
