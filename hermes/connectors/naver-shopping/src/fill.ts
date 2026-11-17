/**
 * 승인한 주문서 채우기(`fill_checkout`). 순서와 결과는 design.md 「채우기(REQ11, PR3c)」 와 「채우기 결과」 가 갖는다.
 *
 * 원문·ticket 대조 → 상품 탭 → 가격 확인 → claim → 「구매하기」 → 상품 구매 층의 옵션·수량 → 「바로구매」 → 주문서 금액.
 * **결제 단추를 누르는 코드 경로가 없다.** 주문서 탭에서는 준비 상태, 화면 분류, 최종 금액 읽기만 부른다.
 * 「바로구매」(또는 층이 확인되기 전의 「구매하기」)를 보낸 뒤의 실패는 오류가 아니라 `{status: "UNKNOWN", tab}` 이다.
 * 선언된 오류로 돌려주면 승인 줄이 `FAILED` 가 되어 주문서가 열렸을 수 있는 승인이 하루 합계에서 빠진다.
 * 단계마다 진단 줄을 남긴다(design.md 「채우기 진단」). 단계 이름과 오류 이름만 싣고 주소, 화면 글, ticket 은 싣지 않는다.
 */
import manifest from "../assistant.json";
import { readResume, verifyApproval, type Authority } from "./approval.ts";
import {
  CHECKOUT_TOOL,
  OPTIONS_MAX,
  priceOnPage,
  QUANTITY_MAX,
} from "./checkout.ts";
import {
  diagnosticCode,
  logDiagnostic,
  ToolError,
  type ErrorCode,
} from "./errors.ts";
import {
  BUY_VISIBLE,
  LAYER_OPEN,
  PAGE_PROBE,
  PRESS_BUY,
  PRESS_BUY_NOW,
} from "./expressions.ts";
import {
  chooseInLayer,
  failed,
  isOrderSheet,
  readSheet,
} from "./fill-sheet.ts";
import { browserUrl, hasLoginCookies, type Env } from "./session.ts";
import {
  attachPage,
  closeTab,
  listTabs,
  workInTab,
  type Page,
  type TabOptions,
} from "./tab.ts";
import { classifyUrl } from "./urls.ts";

export { isOrderSheet };

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
const PASSED_AFTER_CLAIM = new Set<ErrorCode>([
  "NAVER_SHOPPING_CHECKOUT_FAILED",
]);

/** 채우기의 단계. 진단 줄의 `stage` 다. 순서는 design.md 「채우기 진단」 이 갖는다. */
export type FillStage =
  | "approval"
  | "tab"
  | "login"
  | "price"
  | "buy_ready"
  | "claim"
  | "pre_buy"
  | "buy"
  | "layer"
  | "buy_now"
  | "sheet";

/**
 * 지금 단계와 끝 줄을 남겼는지. 단계에 들어갈 때 `<단계>` 줄을, 끝날 때 `<단계>.failed`·`<단계>.unknown`·`done` 줄 하나를 남긴다.
 * 안쪽에서 `NAVER_SHOPPING_CHECKOUT_FAILED` 로 바꾸기 전의 오류 이름을 남기려고 끝 줄은 바꾸는 자리에서 먼저 쓴다.
 */
class FillTrace {
  stage: FillStage = "approval";
  private ended = false;

  enter(stage: FillStage) {
    this.stage = stage;
    logDiagnostic(CHECKOUT_TOOL, stage);
  }

  /** `reason` 은 오류 없이 끝난 까닭의 고정 낱말이다. 오류가 있으면 오류 이름을 싣는다. */
  end(
    outcome: "failed" | "unknown" | "done",
    error?: unknown,
    reason?: string,
  ) {
    if (this.ended) return;
    this.ended = true;
    const stage = outcome === "done" ? "done" : `${this.stage}.${outcome}`;
    logDiagnostic(
      CHECKOUT_TOOL,
      stage,
      [],
      error === undefined ? reason : diagnosticCode(error),
    );
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
  const trace = new FillTrace();
  let claimed = false;
  try {
    trace.enter("approval");
    const verified = verifyApproval(authority, CHECKOUT_TOOL, input);
    const resume = readResume(authority);
    checkFill(input);
    const claim = async () => {
      claimed = true;
      await verified();
    };
    trace.enter("tab");
    const cdpUrl = browserUrl(env);
    return await deps.work(
      cdpUrl,
      input.url,
      (page) => fillOnPage(cdpUrl, page, input, claim, deps, trace),
      resume ? { ...deps.tab, tab: resume.tab } : deps.tab,
    );
  } catch (error) {
    trace.end("failed", error);
    // claim 전에 사람이 해야 하는 화면에서 멈췄다. 남긴 탭에서 상품 화면부터 다시 가면 되므로 이어 갈 단계를 함께 낸다.
    // Control Plane 은 승인을 끝내지 않고 사람을 기다리고, 사람이 풀면 같은 승인으로 이 도구를 그 탭에서 한 번 더 부른다.
    if (error instanceof ToolError && error.handoff?.tab && !claimed)
      throw new ToolError(error.code, error.handoff, { step: "product" });
    throw error;
  }
}

type Sheet = { page: Page; own: boolean };

async function fillOnPage(
  cdpUrl: string,
  page: Page,
  input: FillInput,
  claim: () => Promise<void>,
  deps: FillDeps,
  trace: FillTrace,
) {
  const stepMs = deps.stepMs ?? DEFAULT_STEP_MS;
  const waitMs = deps.waitMs ?? DEFAULT_WAIT_MS;
  try {
    // 로그아웃 상태면 구매하기가 로그인 화면으로 가 claim 만 쓰고 결과를 모르게 된다. claim 전에 로그인 화면을 사람에게 넘긴다.
    // 주소 이동만 하고 아무것도 누르지 않는다. 쿠키가 있어도 만료됐을 수 있고, 그때는 지금처럼 결과를 모르는 것으로 끝난다.
    trace.enter("login");
    if (!(await hasLoginCookies(cdpUrl, page.remaining()))) {
      await page.navigate(manifest.owner_browser_login_url);
      throw new ToolError("NAVER_SHOPPING_LOGIN_REQUIRED", {
        need: "login",
        tab: page.id,
      });
    }
    trace.enter("price");
    const priced = await priceOnPage(page, CHECKOUT_TOOL, {
      url: input.url,
      options: input.optionLabels,
      quantity: input.quantity,
    });
    if (priced.soldOut) throw new ToolError("NAVER_SHOPPING_SOLD_OUT");
    if (priced.expectedTotalKrw > input.expectedTotalKrw)
      throw new ToolError("NAVER_SHOPPING_PRICE_CHANGED");
    // 화면이 「구매하기」 를 그리기 전에 claim 하면 ticket 만 쓰고 누르지 못한다. 보일 때까지 기다린 뒤 claim 한다.
    trace.enter("buy_ready");
    if (!(await waitBuyButton(page, stepMs, waitMs))) throw failed();
    trace.enter("claim");
    await claim();
  } catch (error) {
    // `workInTab` 이 시간 초과나 화면 넘기기로 코드를 바꾸기 전에 원래 이름을 남긴다.
    trace.end("failed", error);
    throw error;
  }

  // claim 뒤의 단계. `buy` 와 `sent` 에서 난 실패는 결과를 모르는 것이다.
  let phase: "claimed" | "buy" | "layer" | "sent" = "claimed";
  let sheet: Sheet | null = null;
  const unknown = (error?: unknown, reason?: string) => {
    trace.end("unknown", error, reason);
    const tab = sheet?.page.id ?? page.id;
    if (tab === page.id) page.keep();
    sheet?.page.close();
    return { status: "UNKNOWN" as const, tab };
  };
  // 누르기 전에 없던 탭만 주문서 후보다. 층 단계의 실패도 이 목록으로 주문서가 열렸는지 다시 본다.
  let beforeBuy = new Set<string>();
  try {
    trace.enter("pre_buy");
    beforeBuy = await tabIds(cdpUrl, page);
    let known = beforeBuy;
    // 누르기 전부터 「바로구매」 가 보이면 누른 뒤 층을 알아볼 수 없다. 아무것도 누르지 않고 실패로 끝낸다.
    if ((await page.evaluate(LAYER_OPEN)) === true) throw failed();
    trace.enter("buy");
    phase = "buy";
    if ((await page.evaluate(PRESS_BUY)) !== true) {
      phase = "claimed";
      throw failed();
    }
    const seen = await waitLayerOrSheet(cdpUrl, page, known, stepMs, waitMs);
    if (seen === null) return unknown(undefined, "not_seen");
    if (seen !== "layer") {
      // 층 없이 주문서가 바로 열렸으면 옵션과 수량을 고르지 못했다. 기본 옵션·수량 1 과 같을 때만 읽는다.
      sheet = seen;
      if (input.optionLabels.length > 0 || input.quantity !== 1) {
        phase = "sent";
        return unknown(undefined, "sheet_without_layer");
      }
    } else {
      trace.enter("layer");
      phase = "layer";
      await chooseInLayer(page, input, stepMs, waitMs);
      known = await tabIds(cdpUrl, page);
      trace.enter("buy_now");
      phase = "sent";
      if ((await page.evaluate(PRESS_BUY_NOW)) !== true) {
        phase = "layer";
        throw failed();
      }
      sheet = await waitSheet(cdpUrl, page, known, stepMs, waitMs);
      if (sheet === null) return unknown(undefined, "sheet_not_seen");
    }
    trace.enter("sheet");
    phase = "sent";
    const finalAmount = await readSheet(sheet.page, stepMs, waitMs);
    if (finalAmount === null) return unknown(undefined, "amount_unread");
    const tab = sheet.page.id;
    sheet.page.close();
    if (finalAmount > input.expectedTotalKrw) {
      // 승인보다 크면 결과 판정이 넘기지 않으므로 주문서 탭을 닫는다. 상품 탭은 `workInTab` 이 닫는다.
      if (!sheet.own) await closeTab(cdpUrl, tab);
    } else if (sheet.own) page.keep();
    trace.end("done");
    return {
      status: "ORDER_SHEET_READY" as const,
      finalAmountKrw: finalAmount,
      tab,
    };
  } catch (error) {
    if (phase === "buy" || phase === "sent") return unknown(error);
    if (phase === "layer") {
      // 층으로 알아봤어도 그사이 주문서가 열렸을 수 있다. 열렸거나 확인하지 못하면 결과를 모르는 것이다.
      const opened = await findSheet(cdpUrl, page, beforeBuy).catch(
        () => "unchecked" as const,
      );
      if (opened !== null) {
        if (opened !== "unchecked") sheet = opened;
        return unknown(error);
      }
    }
    // 바꾸기 전의 오류 이름을 남긴다. 바깥 `fillCheckout` 은 이미 끝 줄이 있으면 다시 쓰지 않는다.
    trace.end("failed", error);
    sheet?.page.close();
    if (error instanceof ToolError && PASSED_AFTER_CLAIM.has(error.code))
      throw error;
    throw failed();
  }
}

/** 상품 화면의 「구매하기」 단추가 보이기를 기다린다. 시간 안에 보이면 참이다. */
async function waitBuyButton(page: Page, stepMs: number, waitMs: number) {
  const until = Date.now() + Math.min(waitMs, page.remaining());
  while (true) {
    // 연결 오류는 삼키지 않는다. 식은 스스로 예외를 거짓으로 바꾸므로 던져지는 것은 연결 문제뿐이다.
    if ((await page.evaluate(BUY_VISIBLE)) === true) return true;
    if (Date.now() >= until) return false;
    await Bun.sleep(stepMs);
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
