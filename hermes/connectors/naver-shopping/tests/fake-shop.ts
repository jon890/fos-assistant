/**
 * 주문서 준비와 채우기를 위한 가짜 브라우저와 가짜 claim 창구. 실제 네이버 화면이 아니다.
 *
 * 탭마다 화면 상태(상품, 상품 구매 층, 주문서)를 두고, 상수 식과 함수 선언을 받으면 그 상태를 바꾼다.
 * 받은 HTTP 경로, CDP 명령, claim 을 한 줄로 이은 기록(`log`)에 남겨 테스트가 차례와 화면별 식을 본다.
 */
import { createHash, randomUUID } from "node:crypto";
import {
  CHOOSE_OPTION,
  EXPRESSIONS,
  FILL_FUNCTIONS,
  GLOBAL_OBJECT,
  LAYER_OPEN,
  OPEN_LAYER_OPTION,
  OPEN_OPTION_LISTS,
  ORDER_SHEET_AMOUNT,
  PAGE_PROBE,
  PRESS_BUY,
  PRESS_BUY_NOW,
  PRODUCT_STATE,
  READY_STATE,
  SET_QUANTITY,
} from "../src/expressions.ts";

export const PRODUCT_URL = "https://smartstore.naver.com/shop/products/123";
export const SHEET_URL =
  "https://orders.pay.naver.com/ordersheet/seller/00000000-0000-4000-8000-000000000000";

export type Screen = "blank" | "product" | "layer" | "sheet" | "login";

export type ShopConfig = {
  /** 상품 화면의 상품 칸(`simpleProductForDetailPage`). */
  product?: unknown;
  /** 옵션 드롭다운을 연 뒤의 상품 칸. 없으면 그대로다. */
  productAfterOpen?: unknown;
  /** 상품 주소로 이동했을 때의 화면. 기본은 상품이다. */
  landing?: "product" | "login";
  /** 「구매하기」 뒤. 기본은 층이다. */
  buy?: "layer" | "sheet-same" | "sheet-new" | "nothing";
  /** 층의 옵션 드롭다운을 열려고 하면 새 탭에 주문서가 열린다(층을 잘못 알아본 경우). */
  sheetOnLayerOption?: boolean;
  /** 누르기 전부터 상품 화면에 「바로구매」 단추가 보인다. */
  buyNowOnProduct?: boolean;
  /** 주문서 금액 식에 CDP 오류로 답한다. */
  amountError?: boolean;
  /** 「바로구매」 뒤. 기본은 같은 탭이 주문서로 넘어간다. */
  buyNow?: "same" | "new" | "nothing";
  /** 층 안의 옵션 드롭다운 수와 고를 수 있는 값. */
  layerOptions?: number;
  layerValues?: string[];
  /** 층 안의 수량 칸이 있는가. 기본은 있다. */
  quantityInput?: boolean;
  /** 주문서의 두 금액. */
  amounts?: { total: number | null; summary: number | null };
  /** claim 응답 상태. 기본 200. */
  claimStatus?: number;
};

export type LogLine =
  | { kind: "http"; line: string }
  | {
      kind: "cdp";
      tab: string;
      screen: Screen;
      method: string;
      source?: string;
      args?: unknown[];
    }
  | { kind: "claim"; body: any };

type Tab = { id: string; screen: Screen; href: string; quantity: number };

const TOKEN = "gateway-token-0123456789";

export function startFakeShop(config: ShopConfig = {}) {
  const log: LogLine[] = [];
  const tabs = new Map<string, Tab>();
  let next = 1;
  let optionsOpened = false;
  const userTab: Tab = {
    id: "USER0",
    screen: "blank",
    href: "https://www.naver.com/",
    quantity: 1,
  };
  tabs.set(userTab.id, userTab);
  const newTab = (screen: Screen, href: string) => {
    const tab: Tab = { id: `TAB${next++}`, screen, href, quantity: 1 };
    tabs.set(tab.id, tab);
    return tab;
  };

  const server = Bun.serve<{ tab: string }>({
    port: 0,
    async fetch(request, srv) {
      const url = new URL(request.url);
      if (url.pathname === "/internal/connector-executions/claim") {
        const body = (await request.json()) as any;
        log.push({ kind: "claim", body });
        const status = config.claimStatus ?? 200;
        if (status !== 200) return new Response("{}", { status });
        const payload = JSON.parse(
          Buffer.from(body.ticket.split(".")[0], "base64url").toString(),
        );
        return Response.json({
          v: 1,
          allowed: true,
          ticketId: payload.ticketId,
          expiresAt: payload.expiresAt,
        });
      }
      const rest = url.pathname.replace(`/${TOKEN}`, "");
      log.push({
        kind: "http",
        line: `${request.method} ${rest}${url.search}`,
      });
      const ws = (id: string) =>
        `ws://${url.host}/${TOKEN}/devtools/page/${id}`;
      if (rest.startsWith("/devtools/page/")) {
        const id = rest.slice("/devtools/page/".length);
        return srv.upgrade(request, { data: { tab: id } })
          ? undefined
          : new Response("", { status: 400 });
      }
      if (rest === "/json/new") {
        const tab = newTab("blank", "about:blank");
        return Response.json({ id: tab.id, webSocketDebuggerUrl: ws(tab.id) });
      }
      if (rest === "/json/list")
        return Response.json(
          [...tabs.values()].map((tab) => ({
            id: tab.id,
            type: "page",
            url: tab.href,
            webSocketDebuggerUrl: ws(tab.id),
          })),
        );
      if (rest.startsWith("/json/close/")) {
        tabs.delete(rest.slice("/json/close/".length));
        return new Response("Target is closing");
      }
      return new Response("", { status: 404 });
    },
    websocket: {
      message(socket, raw): void {
        const message = JSON.parse(String(raw));
        const tab = tabs.get(socket.data.tab);
        const reply = (result: unknown): void => {
          socket.send(JSON.stringify({ id: message.id, result }));
        };
        const fail = (): void => {
          socket.send(
            JSON.stringify({ id: message.id, error: { message: "x" } }),
          );
        };
        if (!tab) return fail();
        const params = message.params ?? {};
        const source =
          message.method === "Runtime.evaluate"
            ? params.expression
            : message.method === "Runtime.callFunctionOn"
              ? params.functionDeclaration
              : undefined;
        log.push({
          kind: "cdp",
          tab: tab.id,
          screen: tab.screen,
          method: message.method,
          ...(source !== undefined ? { source } : {}),
          ...(message.method === "Runtime.callFunctionOn"
            ? { args: params.arguments.map((a: { value: unknown }) => a.value) }
            : {}),
        });
        if (message.method === "Page.navigate") {
          if (config.landing === "login") {
            tab.screen = "login";
            tab.href = "https://nid.naver.com/nidlogin.login";
          } else {
            tab.screen = "product";
            tab.href = params.url;
          }
          return reply({ frameId: "f" });
        }
        if (message.method === "Runtime.callFunctionOn") {
          if (!FILL_FUNCTIONS.has(source)) return fail();
          const [value] = params.arguments.map(
            (a: { value: unknown }) => a.value,
          );
          const inLayer = tab.screen === "layer";
          let out: unknown = false;
          if (source === OPEN_LAYER_OPTION) {
            if (config.sheetOnLayerOption) newTab("sheet", SHEET_URL);
            out = inLayer && (value as number) < (config.layerOptions ?? 0);
          } else if (source === CHOOSE_OPTION)
            out =
              inLayer && (config.layerValues ?? []).includes(value as string)
                ? "ok"
                : "missing";
          else if (source === SET_QUANTITY) {
            if (!inLayer || config.quantityInput === false) out = null;
            else {
              tab.quantity = value as number;
              out = tab.quantity;
            }
          }
          return reply({ result: { value: out } });
        }
        if (message.method !== "Runtime.evaluate") return reply({});
        if (source === GLOBAL_OBJECT)
          return reply({ result: { type: "object", objectId: "global-1" } });
        if (!EXPRESSIONS.has(source)) return fail();
        const value = (v: unknown): void => reply({ result: { value: v } });
        if (source === READY_STATE) return value("complete");
        if (source === PAGE_PROBE)
          return value({ href: tab.href, captcha: false });
        if (source === PRODUCT_STATE) {
          if (tab.screen !== "product" && tab.screen !== "layer")
            return value(null);
          const product =
            optionsOpened && config.productAfterOpen !== undefined
              ? config.productAfterOpen
              : config.product;
          return value(JSON.stringify({ present: true, value: product }));
        }
        if (source === OPEN_OPTION_LISTS) {
          optionsOpened = true;
          return value(tab.screen === "product");
        }
        if (source === PRESS_BUY) {
          if (tab.screen !== "product") return value(false);
          const buy = config.buy ?? "layer";
          if (buy === "layer") tab.screen = "layer";
          else if (buy === "sheet-same") {
            tab.screen = "sheet";
            tab.href = SHEET_URL;
          } else if (buy === "sheet-new") newTab("sheet", SHEET_URL);
          return value(true);
        }
        if (source === LAYER_OPEN)
          return value(
            tab.screen === "layer" ||
              (tab.screen === "product" && config.buyNowOnProduct === true),
          );
        if (source === PRESS_BUY_NOW) {
          if (tab.screen !== "layer") return value(false);
          const buyNow = config.buyNow ?? "same";
          if (buyNow === "same") {
            tab.screen = "sheet";
            tab.href = SHEET_URL;
          } else if (buyNow === "new") newTab("sheet", SHEET_URL);
          return value(true);
        }
        if (source === ORDER_SHEET_AMOUNT && config.amountError) return fail();
        if (source === ORDER_SHEET_AMOUNT)
          return value(
            tab.screen === "sheet"
              ? (config.amounts ?? { total: 3300, summary: 3300 })
              : null,
          );
        return value(null);
      },
    },
  });
  const cdpLines = () =>
    log.filter(
      (line): line is Extract<LogLine, { kind: "cdp" }> => line.kind === "cdp",
    );
  return {
    url: `http://127.0.0.1:${server.port}/${TOKEN}`,
    claimUrl: `http://127.0.0.1:${server.port}/internal/connector-executions/claim`,
    log,
    tabs,
    cdpLines,
    /** 그 식이나 함수 선언을 부른 횟수. */
    count: (source: string) =>
      cdpLines().filter((line) => line.source === source).length,
    stop: () => server.stop(true),
  };
}

export type FakeShop = ReturnType<typeof startFakeShop>;

const sha256 = (raw: string) =>
  createHash("sha256").update(raw, "utf8").digest("hex");

/** 승인한 실행이 받는 env 셋. ticket 의 서명은 플러그인이 보지 않으므로 아무 32바이트다. */
export function approvalEnv(
  args: unknown,
  claimUrl: string,
  { tool = "fill_checkout", raw = JSON.stringify(args), ttlMs = 30_000 } = {},
) {
  const issued = Date.now() - 1_000;
  const payload = {
    v: 1,
    ticketId: randomUUID(),
    tool,
    argsSha256: sha256(raw),
    scopeSha256: sha256("{}"),
    issuedAt: new Date(issued).toISOString(),
    expiresAt: new Date(issued + ttlMs).toISOString(),
  };
  const ticket = `${Buffer.from(JSON.stringify(payload)).toString("base64url")}.${Buffer.alloc(32, 7).toString("base64url")}`;
  return {
    FOS_APPROVAL_ARGS_JSON: raw,
    FOS_APPROVAL_TICKET: ticket,
    FOS_APPROVAL_CLAIM_URL: claimUrl,
  };
}

/** 옵션 없는 500원 상품(배송비 2,900원). 2026-11-13 주문서 실측의 상품과 같은 금액이다. */
export const PLAIN_PRODUCT = {
  name: "합성 상품",
  channel: { channelName: "합성몰" },
  salePrice: 500,
  productStatusType: "SALE",
  optionUsable: false,
  productDeliveryInfo: { deliveryFeeType: "PAID", baseFee: 2900 },
};
