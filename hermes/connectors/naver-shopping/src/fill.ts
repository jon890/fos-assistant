/**
 * 승인한 주문서 채우기(`fill_checkout`). 순서와 결과는 design.md 「채우기(REQ11, PR3c)」 와 「채우기 결과」 가 갖는다.
 *
 * 원문·ticket 대조 → 상품 탭 → 가격 확인 → claim → 「구매하기」 → 상품 구매 층의 옵션·수량 → 「바로구매」 → 주문서 금액.
 * **결제 단추를 누르는 코드 경로가 없다.** 주문서 탭에서는 준비 상태, 화면 분류, 최종 금액 읽기만 부른다.
 * 「바로구매」(또는 층이 확인되기 전의 「구매하기」)를 보낸 뒤의 실패는 오류가 아니라 `{status: "UNKNOWN", tab}` 이다.
 * 선언된 오류로 돌려주면 승인 줄이 `FAILED` 가 되어 주문서가 열렸을 수 있는 승인이 하루 합계에서 빠진다.
 */
import { verifyApproval, type Authority } from "./approval.ts";
import {
  CHECKOUT_TOOL,
  OPTIONS_MAX,
  priceOnPage,
  QUANTITY_MAX,
} from "./checkout.ts";
import { classifyPage } from "./classify.ts";
import { ToolError, type ErrorCode } from "./errors.ts";
import {
  CHOOSE_OPTION,
  LAYER_OPEN,
  OPEN_LAYER_OPTION,
  ORDER_SHEET_AMOUNT,
  PAGE_PROBE,
  PRESS_BUY,
  PRESS_BUY_NOW,
  SET_QUANTITY,
} from "./expressions.ts";
import { ORDER_SHEET } from "./fields.ts";
import { browserUrl, type Env } from "./session.ts";
import {
  attachPage,
  closeTab,
  listTabs,
  waitReady,
  workInTab,
  type Page,
  type TabOptions,
} from "./tab.ts";
import { classifyUrl } from "./urls.ts";

export type FillInput = {
  checkout: 1;
  url: string;
  optionLabels: string[];
  quantity: number;
  expectedTotalKrw: number;
};

export type FillDeps = {
  work: typeof workInTab;
  tab?: TabOptions;
  /** 층, 주문서, 금액을 다시 묻는 간격. */
  stepMs?: number;
  /** 누른 뒤 층이나 주문서가 보이기를 기다리는 시간. 같은 시간을 금액이 읽히기를 기다리는 데도 쓴다. */
  waitMs?: number;
};
const DEFAULT_DEPS: FillDeps = { work: workInTab };
const DEFAULT_STEP_MS = 250;
const DEFAULT_WAIT_MS = 15_000;

/** claim 뒤 「바로구매」 전에 실패하면 이 코드들만 그대로 내고, 그 밖의 실패는 `NAVER_SHOPPING_CHECKOUT_FAILED` 로 바꾼다. */
const PASSED_AFTER_CLAIM = new Set<ErrorCode>(["NAVER_SHOPPING_CHECKOUT_FAILED"]);

const failed = () => new ToolError("NAVER_SHOPPING_CHECKOUT_FAILED");

/** 주문서 주소인가. 호스트와 `/ordersheet/` 경로로 가린다. */
export function isOrderSheet(href: unknown) {
  try {
    const url = new URL(typeof href === "string" ? href : "");
    return (
      url.protocol === "https:" &&
      url.hostname === ORDER_SHEET.host &&
      url.pathname.startsWith(ORDER_SHEET.pathPrefix)
    );
  } catch {
    return false;
  }
}

/** 실행 원문의 모양. 서버 스키마를 지난 값을 다시 본다. */
function checkFill(input: FillInput) {
  const target = classifyUrl(input.url);
  if (
    input.checkout !== 1 ||
    target?.kind !== "store" ||
    target.url !== input.url ||
    !Array.isArray(input.optionLabels) ||
    input.optionLabels.length > OPTIONS_MAX ||
    !Number.isSafeInteger(input.quantity) ||
    input.quantity < 1 ||
    input.quantity > QUANTITY_MAX ||
    !Number.isSafeInteger(input.expectedTotalKrw) ||
    input.expectedTotalKrw < 0
  )
    throw new ToolError("NAVER_SHOPPING_INVALID_INPUT");
}

export async function fillCheckout(
  env: Env,
  authority: Authority,
  input: FillInput,
  deps: FillDeps = DEFAULT_DEPS,
) {
  const claim = verifyApproval(authority, CHECKOUT_TOOL, input);
  checkFill(input);
  const cdpUrl = browserUrl(env);
  return deps.work(
    cdpUrl,
    input.url,
    (page) => fillOnPage(cdpUrl, page, input, claim, deps),
    deps.tab,
  );
}

type Sheet = { page: Page; own: boolean };

async function fillOnPage(
  cdpUrl: string,
  page: Page,
  input: FillInput,
  claim: () => Promise<void>,
  deps: FillDeps,
) {
  const stepMs = deps.stepMs ?? DEFAULT_STEP_MS;
  const waitMs = deps.waitMs ?? DEFAULT_WAIT_MS;
  const priced = await priceOnPage(page, CHECKOUT_TOOL, {
    url: input.url,
    options: input.optionLabels,
    quantity: input.quantity,
  });
  if (priced.soldOut) throw new ToolError("NAVER_SHOPPING_SOLD_OUT");
  if (priced.expectedTotalKrw > input.expectedTotalKrw)
    throw new ToolError("NAVER_SHOPPING_PRICE_CHANGED");
  await claim();

  // claim 뒤의 단계. `buy` 와 `sent` 에서 난 실패는 결과를 모르는 것이다.
  let phase: "claimed" | "buy" | "layer" | "sent" = "claimed";
  let sheet: Sheet | null = null;
  const unknown = () => {
    const tab = sheet?.page.id ?? page.id;
    if (tab === page.id) page.keep();
    sheet?.page.close();
    return { status: "UNKNOWN" as const, tab };
  };
  try {
    let known = await tabIds(cdpUrl, page);
    phase = "buy";
    if ((await page.evaluate(PRESS_BUY)) !== true) {
      phase = "claimed";
      throw failed();
    }
    const seen = await waitLayerOrSheet(cdpUrl, page, known, stepMs, waitMs);
    if (seen === null) return unknown();
    if (seen !== "layer") sheet = seen;
    else {
      phase = "layer";
      await chooseInLayer(page, input, stepMs, waitMs);
      known = await tabIds(cdpUrl, page);
      phase = "sent";
      if ((await page.evaluate(PRESS_BUY_NOW)) !== true) {
        phase = "layer";
        throw failed();
      }
      sheet = await waitSheet(cdpUrl, page, known, stepMs, waitMs);
      if (sheet === null) return unknown();
    }
    phase = "sent";
    const finalAmount = await readSheet(sheet.page, stepMs, waitMs);
    if (finalAmount === null) return unknown();
    const tab = sheet.page.id;
    sheet.page.close();
    if (finalAmount > input.expectedTotalKrw) {
      // 승인보다 크면 결과 판정이 넘기지 않으므로 주문서 탭을 닫는다. 상품 탭은 `workInTab` 이 닫는다.
      if (!sheet.own) await closeTab(cdpUrl, tab);
    } else if (sheet.own) page.keep();
    return { status: "ORDER_SHEET_READY" as const, finalAmountKrw: finalAmount, tab };
  } catch (error) {
    if (phase === "buy" || phase === "sent") return unknown();
    sheet?.page.close();
    if (error instanceof ToolError && PASSED_AFTER_CLAIM.has(error.code))
      throw error;
    throw failed();
  }
}

/** 지금 탭 목록의 id. 누른 뒤 새로 열린 탭만 주문서 후보로 본다. */
async function tabIds(cdpUrl: string, page: Page) {
  return new Set(
    (await listTabs(cdpUrl, page.remaining())).map((tab) => tab.id),
  );
}

/** 같은 탭이 주문서로 넘어갔거나, 누르기 전에 없던 탭이 주문서면 그 탭. 주문서 화면에는 화면 분류 식만 부른다. */
async function findSheet(
  cdpUrl: string,
  page: Page,
  known: Set<string>,
): Promise<Sheet | null> {
  const probe = (await page.evaluate(PAGE_PROBE).catch(() => null)) as {
    href?: unknown;
  } | null;
  if (isOrderSheet(probe?.href)) return { page, own: true };
  const fresh = (await listTabs(cdpUrl, page.remaining())).find(
    (tab) => !known.has(tab.id) && isOrderSheet(tab.url),
  );
  if (!fresh) return null;
  return { page: await attachPage(cdpUrl, fresh, page.remaining), own: false };
}

/** 「구매하기」 뒤 상품 구매 층이나 주문서를 기다린다. 둘 다 보이지 않으면 `null` 이다. */
async function waitLayerOrSheet(
  cdpUrl: string,
  page: Page,
  known: Set<string>,
  stepMs: number,
  waitMs: number,
): Promise<Sheet | "layer" | null> {
  const until = Date.now() + Math.min(waitMs, page.remaining());
  while (Date.now() < until) {
    const sheet = await findSheet(cdpUrl, page, known);
    if (sheet) return sheet;
    if ((await page.evaluate(LAYER_OPEN).catch(() => false)) === true)
      return "layer";
    await Bun.sleep(stepMs);
  }
  return null;
}

/** 「바로구매」 뒤 주문서를 기다린다. */
async function waitSheet(
  cdpUrl: string,
  page: Page,
  known: Set<string>,
  stepMs: number,
  waitMs: number,
) {
  const until = Date.now() + Math.min(waitMs, page.remaining());
  while (Date.now() < until) {
    const sheet = await findSheet(cdpUrl, page, known);
    if (sheet) return sheet;
    await Bun.sleep(stepMs);
  }
  return null;
}

/** 층 안에서 옵션 값을 단계마다 고르고 수량을 맞춘 뒤 다시 읽어 확인한다. 못 맞추면 「바로구매」 를 누르지 않고 실패다. */
async function chooseInLayer(
  page: Page,
  input: FillInput,
  stepMs: number,
  waitMs: number,
) {
  for (const [index, label] of input.optionLabels.entries()) {
    if ((await page.call(OPEN_LAYER_OPTION, [index])) !== true) throw failed();
    const until = Date.now() + Math.min(waitMs, page.remaining());
    while (true) {
      const chosen = await page.call(CHOOSE_OPTION, [label]);
      if (chosen === "ok") break;
      if (chosen !== "missing" || Date.now() >= until) throw failed();
      await Bun.sleep(stepMs);
    }
    await Bun.sleep(stepMs);
  }
  // 수량 칸이 없으면 층의 기본값 1 만 받는다.
  const matches = (value: unknown) =>
    value === input.quantity || (value === null && input.quantity === 1);
  if (!matches(await page.call(SET_QUANTITY, [input.quantity])))
    throw failed();
  await Bun.sleep(stepMs);
  if (!matches(await page.call(SET_QUANTITY, [input.quantity])))
    throw failed();
}

/** 주문서 탭에서 화면 분류를 하고 두 자리의 금액이 같아질 때까지 읽는다. 사람이 해야 하는 화면이거나 읽지 못하면 `null` 이다. */
async function readSheet(page: Page, stepMs: number, waitMs: number) {
  await waitReady(page, stepMs);
  const probe = ((await page.evaluate(PAGE_PROBE)) ?? {}) as {
    href?: unknown;
  };
  if (classifyPage(probe) !== "ok" || !isOrderSheet(probe.href)) return null;
  const until = Date.now() + Math.min(waitMs, page.remaining());
  while (true) {
    const amount = (await page.evaluate(ORDER_SHEET_AMOUNT)) as {
      total?: unknown;
      summary?: unknown;
    } | null;
    const total = amount?.total;
    if (
      typeof total === "number" &&
      Number.isSafeInteger(total) &&
      total >= 0 &&
      total < 1_000_000_000 &&
      total === amount?.summary
    )
      return total;
    if (Date.now() >= until) return null;
    await Bun.sleep(stepMs);
  }
}
