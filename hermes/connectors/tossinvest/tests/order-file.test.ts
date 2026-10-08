import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { mkdtemp, readdir, readFile, rm, symlink, utimes, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { Tossinvest } from "../src/client.ts";
import { orderQuery } from "../src/orders.ts";
import { writeOrdersFile, type OrderFileTiming } from "../src/order-file.ts";
import { createTossinvestServer } from "../src/server.ts";
import {
  FakeToss,
  apiError,
  credentials,
  expectFailure,
  json,
  tokenPrefix,
  tool,
  upstreamText,
  withMcp,
  type RecordedRequest,
} from "./support.ts";

/** 모두 지어낸 값이다. 실제 계좌와 주문과 관계가 없다. 순번은 파일 어디에도 나오면 안 되므로 주문 값과 겹치지 않는 자리수로 둔다. */
const accountSeq = "4242424242";
const HOUR_MS = 60 * 60 * 1000;
/** 시험이 고정하는 「지금」 이다. 파일 이름의 시각과 24시간 정리의 기준이 된다. */
const fixedNow = new Date("2030-01-02T03:04:05.678Z");

const order = (index: number) => ({
  orderId: `fake-order-${index}`,
  symbol: "AAPL",
  side: "BUY",
  orderType: "LIMIT",
  status: "FILLED",
  price: "190.50",
  quantity: "0.5",
  orderAmount: "95.25",
  currency: "USD",
  orderedAt: "2026-03-02T10:00:00+09:00",
  canceledAt: null,
  execution: {
    filledQuantity: "0.5",
    averageFilledPrice: "190.40",
    filledAmount: "95.20",
    commission: "0.05",
    tax: null,
    filledAt: "2026-03-02T23:31:00+09:00",
    settlementDate: "2026-03-04",
  },
});

const orders = (from: number, count: number) =>
  Array.from({ length: count }, (_, index) => order(from + index));

const FIELDS = [
  "order_id", "symbol", "side", "order_type", "status", "price", "quantity",
  "order_amount", "currency", "filled_quantity", "average_filled_price",
  "filled_amount", "commission", "tax", "ordered_at", "filled_at",
  "canceled_at", "settlement_date",
];

/** 커서마다 다른 쪽을 준다. 목록의 마지막 쪽은 `hasNext` 가 거짓이다. */
function pages(sizes: number[]) {
  return (request: RecordedRequest) => {
    const cursor = request.query.get("cursor");
    const index = cursor === null ? 0 : Number(cursor.replace("fake-cursor-", ""));
    const start = sizes.slice(0, index).reduce((sum, size) => sum + size, 0);
    const last = index === sizes.length - 1;
    return json({
      result: {
        orders: orders(start, sizes[index] ?? 0),
        nextCursor: last ? null : `fake-cursor-${index + 1}`,
        hasNext: !last,
      },
    });
  };
}

let directory: string;
let fake: FakeToss;
let sleeps: number[];
let timing: OrderFileTiming;

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), "tossinvest-orders-"));
  fake = new FakeToss();
  sleeps = [];
  timing = {
    now: () => fixedNow,
    sleep: async (ms) => {
      sleeps.push(ms);
    },
    random: () => "0badf00d",
  };
});

afterEach(async () => {
  fake.stop();
  await rm(directory, { recursive: true, force: true });
});

const env = (outputDir: string | undefined) => ({
  ...credentials,
  TOSSINVEST_ACCOUNT_SEQ: accountSeq,
  TOSSINVEST_OUTPUT_DIR: outputDir,
});

const client = () => new Tossinvest({ apiBase: fake.url, env: env(directory) });

const write = (input: Record<string, string>, dir = directory) =>
  writeOrdersFile(client(), dir, orderQuery(input), timing);

async function expectCode(code: string, work: Promise<unknown>) {
  await expect(work).rejects.toMatchObject({ code });
}

/** 디렉터리에 남은 이름이다. 실패한 뒤에는 `orders-` 도 `.orders-` 도 없어야 한다. */
const ownFiles = async () =>
  (await readdir(directory)).filter((name) => /^\.?orders-/.test(name)).sort();

async function readLines(file: string) {
  const text = await readFile(file, "utf8");
  for (const secret of [...Object.values(credentials), tokenPrefix, upstreamText, accountSeq])
    expect(text).not.toContain(secret);
  expect(text.endsWith("\n")).toBe(true);
  return text.slice(0, -1).split("\n").map((line) => JSON.parse(line) as Record<string, unknown>);
}

describe("list_orders output=file", () => {
  test("CLOSED 세 쪽을 이어 207줄 파일 하나를 쓰고 경로와 건수, 기간, 칸 목록만 돌려준다", async () => {
    fake.routes.set("GET /api/v1/orders", pages([100, 100, 7]));
    const server = createTossinvestServer({ apiBase: fake.url, env: env(directory) });
    let file = "";
    await withMcp(server, async (mcp) => {
      const { body } = await tool(mcp, "list_orders", {
        status: "CLOSED",
        from: "2026-03-01",
        to: "2026-03-31",
        output: "file",
      });
      file = body.file as string;
      expect(body).toEqual({
        file,
        count: 207,
        from: "2026-03-01",
        to: "2026-03-31",
        fields: FIELDS,
      });
    });
    expect(file.startsWith(`${directory}/`)).toBe(true);
    expect(basename(file)).toMatch(/^orders-\d{8}T\d{6}Z-[0-9a-f]{8}\.jsonl$/);
    expect(await ownFiles()).toEqual([basename(file)]);

    const lines = await readLines(file);
    expect(lines).toHaveLength(207);
    expect(lines[0]).toEqual({
      order_id: "fake-order-0",
      symbol: "AAPL",
      side: "BUY",
      order_type: "LIMIT",
      status: "FILLED",
      price: "190.50",
      quantity: "0.5",
      order_amount: "95.25",
      currency: "USD",
      filled_quantity: "0.5",
      average_filled_price: "190.40",
      filled_amount: "95.20",
      commission: "0.05",
      tax: null,
      ordered_at: "2026-03-02T10:00:00+09:00",
      filled_at: "2026-03-02T23:31:00+09:00",
      canceled_at: null,
      settlement_date: "2026-03-04",
    });
    expect(lines[206]!.order_id).toBe("fake-order-206");
    expect(lines.every((line) => typeof line.price === "string")).toBe(true);

    const requests = fake.seen("GET", "/api/v1/orders");
    expect(requests.map((request) => Object.fromEntries(request.query))).toEqual([
      { status: "CLOSED", from: "2026-03-01", to: "2026-03-31", limit: "100" },
      { status: "CLOSED", from: "2026-03-01", to: "2026-03-31", limit: "100", cursor: "fake-cursor-1" },
      { status: "CLOSED", from: "2026-03-01", to: "2026-03-31", limit: "100", cursor: "fake-cursor-2" },
    ]);
    expect(requests.every((request) => request.headers.get("x-tossinvest-account") === accountSeq)).toBe(true);
  });

  test("OPEN 은 limit 과 cursor 없이 한 번 읽어 전량을 쓰고, 기간이 없으면 from 과 to 가 null 이다", async () => {
    fake.on("GET", "/api/v1/orders", {
      result: { orders: orders(0, 150).map((row) => ({ ...row, status: "PENDING" })), hasNext: false },
    });
    const result = await write({ status: "OPEN" });
    expect(result).toMatchObject({ count: 150, from: null, to: null, fields: FIELDS });
    expect(basename(result.file)).toBe("orders-20300102T030405Z-0badf00d.jsonl");
    expect(await readLines(result.file)).toHaveLength(150);
    const requests = fake.seen("GET", "/api/v1/orders");
    expect(requests).toHaveLength(1);
    expect(Object.fromEntries(requests[0]!.query)).toEqual({ status: "OPEN" });
    expect(sleeps).toEqual([]);
  });

  test("쪽 사이에 250ms 를 쉰다", async () => {
    fake.routes.set("GET /api/v1/orders", pages([100, 100, 1]));
    await write({ status: "CLOSED" });
    expect(sleeps).toEqual([250, 250]);
  });

  test("21쪽째가 필요하면 TOSSINVEST_TOO_MANY_ORDERS 이고 파일이 남지 않는다", async () => {
    fake.routes.set("GET /api/v1/orders", () =>
      json({ result: { orders: orders(0, 100), nextCursor: "fake-cursor-more", hasNext: true } }),
    );
    await expectCode("TOSSINVEST_TOO_MANY_ORDERS", write({ status: "CLOSED" }));
    expect(fake.seen("GET", "/api/v1/orders")).toHaveLength(20);
    expect(await ownFiles()).toEqual([]);
  });

  test("20쪽에서 끝나면 2,000건을 쓴다", async () => {
    fake.routes.set("GET /api/v1/orders", pages(Array(20).fill(100)));
    const result = await write({ status: "CLOSED" });
    expect(result.count).toBe(2000);
    expect(await readLines(result.file)).toHaveLength(2000);
  });

  test("같은 최종 이름이 이미 있으면 TOSSINVEST_OUTPUT_UNAVAILABLE 이고 기존 파일을 바꾸지 않는다", async () => {
    const existing = join(directory, "orders-20300102T030405Z-0badf00d.jsonl");
    await writeFile(existing, "keep-this-line\n");
    // 고정한 「지금」 과 같은 시각으로 두어 24시간 정리에 걸리지 않게 한다.
    await utimes(existing, fixedNow.getTime() / 1000, fixedNow.getTime() / 1000);
    fake.on("GET", "/api/v1/orders", { result: { orders: orders(0, 3), hasNext: false } });
    await expectCode("TOSSINVEST_OUTPUT_UNAVAILABLE", write({ status: "CLOSED" }));
    expect(await readFile(existing, "utf8")).toBe("keep-this-line\n");
    expect(await ownFiles()).toEqual([basename(existing)]);
  });

  test("임시 이름 자리에 링크가 있으면 요청 없이 TOSSINVEST_OUTPUT_UNAVAILABLE 이고 링크 대상을 쓰지 않는다", async () => {
    const victim = join(directory, "victim.txt");
    await writeFile(victim, "untouched\n");
    await symlink(victim, join(directory, ".orders-20300102T030405Z-0badf00d.tmp"));
    await expectCode("TOSSINVEST_OUTPUT_UNAVAILABLE", write({ status: "CLOSED" }));
    expect(fake.requests).toHaveLength(0);
    expect(await readFile(victim, "utf8")).toBe("untouched\n");
    expect(await ownFiles()).toEqual([".orders-20300102T030405Z-0badf00d.tmp"]);
  });

  test.each([
    ["없음", undefined],
    ["빈 값", ""],
    ["공백", "   "],
  ])("출력 디렉터리 env 가 %s 이면 요청 없이 TOSSINVEST_OUTPUT_UNAVAILABLE 이다", async (_label, dir) => {
    const server = createTossinvestServer({ apiBase: fake.url, env: env(dir) });
    await withMcp(server, (mcp) =>
      expectFailure(mcp, "TOSSINVEST_OUTPUT_UNAVAILABLE", "list_orders", { status: "CLOSED", output: "file" }),
    );
    expect(fake.requests).toHaveLength(0);
  });

  test("출력 디렉터리가 링크이면 요청 없이 TOSSINVEST_OUTPUT_UNAVAILABLE 이다", async () => {
    const linked = join(directory, "linked");
    await symlink(directory, linked);
    const server = createTossinvestServer({ apiBase: fake.url, env: env(linked) });
    await withMcp(server, (mcp) =>
      expectFailure(mcp, "TOSSINVEST_OUTPUT_UNAVAILABLE", "list_orders", { status: "CLOSED", output: "file" }),
    );
    expect(fake.requests).toHaveLength(0);
    expect(await ownFiles()).toEqual([]);
  });

  test.each([
    ["없는 경로", "missing"],
    ["정규 파일", "plain-file"],
  ])("출력 디렉터리가 %s 이면 요청 없이 TOSSINVEST_OUTPUT_UNAVAILABLE 이다", async (_label, name) => {
    const target = join(directory, name);
    if (name === "plain-file") await writeFile(target, "");
    await expectCode("TOSSINVEST_OUTPUT_UNAVAILABLE", write({ status: "CLOSED" }, target));
    expect(fake.requests).toHaveLength(0);
  });

  test("24시간 지난 자기 파일만 지우고 23시간 된 파일, 다른 이름, 링크는 남긴다", async () => {
    const age = async (name: string, hours: number) => {
      const path = join(directory, name);
      await writeFile(path, "old\n");
      const seconds = (fixedNow.getTime() - hours * HOUR_MS) / 1000;
      await utimes(path, seconds, seconds);
    };
    await age("orders-20291231T000000Z-aaaaaaaa.jsonl", 25);
    await age(".orders-20291231T000000Z-bbbbbbbb.tmp", 25);
    await age("orders-20300101T000000Z-cccccccc.jsonl", 23);
    await age("notes-20291201T000000Z-dddddddd.jsonl", 25);
    await age("orders-keep.txt", 25);
    // 링크는 지금 만들어도 고정한 「지금」(2030년)보다 오래됐다.
    await symlink(join(directory, "orders-keep.txt"), join(directory, "orders-20291130T000000Z-eeeeeeee.jsonl"));

    fake.on("GET", "/api/v1/orders", { result: { orders: orders(0, 1), hasNext: false } });
    const result = await write({ status: "CLOSED" });

    expect((await readdir(directory)).sort()).toEqual(
      [
        "notes-20291201T000000Z-dddddddd.jsonl",
        "orders-20291130T000000Z-eeeeeeee.jsonl",
        "orders-20300101T000000Z-cccccccc.jsonl",
        "orders-keep.txt",
        basename(result.file),
      ].sort(),
    );
    expect(await readFile(join(directory, "orders-20291130T000000Z-eeeeeeee.jsonl"), "utf8")).toBe("old\n");
  });

  test("한 쪽이 429 면 1초 쉬고 한 번 다시 불러 이어 쓴다", async () => {
    const page = pages([100, 5]);
    let secondPageCalls = 0;
    fake.routes.set("GET /api/v1/orders", (request) => {
      if (request.query.get("cursor") === "fake-cursor-1" && secondPageCalls++ === 0)
        return apiError(429, "rate-limited")();
      return page(request);
    });
    const result = await write({ status: "CLOSED" });
    expect(result.count).toBe(105);
    expect(await readLines(result.file)).toHaveLength(105);
    expect(fake.seen("GET", "/api/v1/orders")).toHaveLength(3);
    expect(sleeps).toEqual([250, 1000]);
  });

  test("다시 부른 쪽도 429 면 TOSSINVEST_RATE_LIMITED 이고 파일이 남지 않는다", async () => {
    const page = pages([100, 5]);
    fake.routes.set("GET /api/v1/orders", (request) =>
      request.query.get("cursor") === "fake-cursor-1" ? apiError(429, "rate-limited")() : page(request),
    );
    await expectCode("TOSSINVEST_RATE_LIMITED", write({ status: "CLOSED" }));
    expect(fake.seen("GET", "/api/v1/orders")).toHaveLength(3);
    expect(sleeps).toEqual([250, 1000]);
    expect(await ownFiles()).toEqual([]);
  });

  test("서비스 오류로 중간에 끊기면 그 코드로 끝나고 파일이 남지 않는다", async () => {
    const page = pages([100, 5]);
    fake.routes.set("GET /api/v1/orders", (request) =>
      request.query.get("cursor") === "fake-cursor-1" ? apiError(500, "internal")() : page(request),
    );
    await expectCode("TOSSINVEST_UNAVAILABLE", write({ status: "CLOSED" }));
    expect(await ownFiles()).toEqual([]);
  });

  test("output 이 file 이 아니면 요청 없이 TOSSINVEST_INVALID_INPUT 이다", async () => {
    const server = createTossinvestServer({ apiBase: fake.url, env: env(directory) });
    await withMcp(server, (mcp) =>
      expectFailure(mcp, "TOSSINVEST_INVALID_INPUT", "list_orders", { status: "CLOSED", output: "csv" }),
    );
    expect(fake.requests).toHaveLength(0);
    expect(await ownFiles()).toEqual([]);
  });

  test("output 이 빈 글이면 지금처럼 목록을 돌려주고 파일을 쓰지 않는다", async () => {
    fake.on("GET", "/api/v1/orders", { result: { orders: orders(0, 2), hasNext: false } });
    const server = createTossinvestServer({ apiBase: fake.url, env: env(directory) });
    await withMcp(server, async (mcp) => {
      const { body } = await tool(mcp, "list_orders", { status: "CLOSED", output: "" });
      expect(body).toMatchObject({ has_more: false });
      expect(body.orders).toHaveLength(2);
    });
    expect(await ownFiles()).toEqual([]);
  });
});
