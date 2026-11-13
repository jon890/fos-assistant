import { logDiagnostic, ToolError } from "./errors.ts";
import { PRODUCT_STATE } from "./expressions.ts";
import { chooseOption, MissingFields } from "./parse-product.ts";
import { readStore, type StoreRead } from "./product-read.ts";
import { browserUrl, type Env } from "./session.ts";
import { workInTab, type Page, type TabOptions } from "./tab.ts";
import { classifyUrl } from "./urls.ts";

/** 준비가 만드는 실행 도구의 이름. `assistant.json` 의 `execution_guard.operations` 와 같다. */
export const CHECKOUT_TOOL = "fill_checkout";
export const OPTIONS_MAX = 3;
export const QUANTITY_MAX = 99;
/** 원 단위 금액의 상한(10억 미만). Control Plane 과 대시보드의 주문서 검증과 같다. */
const AMOUNT_BOUND = 1_000_000_000;

export type CheckoutInput = { url: string; options: string[]; quantity: number };

export type CheckoutDeps = {
  work: typeof workInTab;
  tab?: TabOptions;
};

/** 준비와 채우기가 같이 쓰는 값. 계산 방법은 design.md 「도구(PR3c)」 가 갖는다. */
export type PricedCheckout = {
  url: string;
  title: string;
  store: string;
  labels: string[];
  shown: { name: string; value: string }[];
  quantity: number;
  itemAmountKrw: number;
  shippingFeeKrw: number;
  expectedTotalKrw: number;
  regionalExtraFee: boolean;
  soldOut: boolean;
};

/** 준비 인자를 검사한다. 주소는 「주소 분류」 의 `store` 갈래만, 옵션은 0~3개의 한 줄 글, 수량은 1~99 정수다. */
export function checkInput(args: CheckoutInput) {
  const target = classifyUrl(args.url);
  if (
    target?.kind !== "store" ||
    !Array.isArray(args.options) ||
    args.options.length > OPTIONS_MAX ||
    args.options.some(
      (option) =>
        typeof option !== "string" ||
        !option.trim() ||
        [...option].length > 100 ||
        /[\u0000-\u001f\u007f]/.test(option),
    ) ||
    !Number.isSafeInteger(args.quantity) ||
    args.quantity < 1 ||
    args.quantity > QUANTITY_MAX
  )
    throw new ToolError("NAVER_SHOPPING_INVALID_INPUT");
  return { url: target.url, options: args.options, quantity: args.quantity };
}

/**
 * 상품 상태로 금액을 계산한다. 상품 금액은 (판매가 + 옵션 추가 금액) × 수량(spec DECISION21), 배송비는 무료 조건을 반영한
 * 기본 배송비(spec DECISION29)다. 쿠폰과 포인트는 넣지 않는다. 바로 살 수 없거나 판매처를 읽지 못하면 거절한다.
 */
export function priceCheckout(
  read: StoreRead,
  url: string,
  options: string[],
  quantity: number,
): PricedCheckout {
  const { product } = read;
  if (product.store === null) throw new MissingFields(["store"]);
  if (!product.naver_pay_buy) throw new ToolError("NAVER_SHOPPING_NOT_BUYABLE");
  const chosen = chooseOption(read.value, product, options);
  if (product.price_krw === null || product.title === null)
    throw new MissingFields(["price"]);
  const unit = product.price_krw + chosen.extra_krw;
  const item = unit * quantity;
  const freeOver = product.shipping.free_over_krw;
  const shipping =
    freeOver !== null && item >= freeOver ? 0 : product.shipping.fee_krw;
  const total = item + shipping;
  if (!(unit > 0) || !Number.isSafeInteger(total) || total >= AMOUNT_BOUND)
    throw new ToolError("NAVER_SHOPPING_NOT_BUYABLE");
  return {
    url,
    title: product.title,
    store: product.store,
    labels: chosen.labels,
    shown: chosen.shown,
    quantity,
    itemAmountKrw: item,
    shippingFeeKrw: shipping,
    expectedTotalKrw: total,
    regionalExtraFee: product.shipping.regional_extra,
    soldOut: chosen.sold_out,
  };
}

/** 열린 상품 탭에서 상태를 읽어(옵션이 있으면 드롭다운을 한 번 연다) 금액을 계산한다. 칸이 비면 진단 줄을 남기고 바뀐 화면이다. */
export async function priceOnPage(
  page: Page,
  tool: string,
  input: CheckoutInput,
) {
  try {
    const read = await readStore(
      await page.evaluate(PRODUCT_STATE),
      input.url,
      page,
    );
    return priceCheckout(read, input.url, input.options, input.quantity);
  } catch (error) {
    if (!(error instanceof MissingFields)) throw error;
    logDiagnostic(tool, "parse.checkout", error.fields);
    throw new ToolError("NAVER_SHOPPING_PAGE_CHANGED");
  }
}

/**
 * 주문서 준비. 상품 화면을 새로 읽어 옵션 글을 조합 하나로 맞추고 서버가 카드로 그릴 요약과 실행 원문을 만든다.
 * 결과는 모델이 아니라 대시보드의 준비 경로가 받는다. 그 경로는 화면 넘기기 결과를 받지 않으므로, 사람이 해야 하는 화면은
 * 그 탭을 남긴 채 넘기기 칸 없는 오류로 끝낸다. 품절이면 카드를 만들지 않는다.
 */
export async function prepareCheckout(
  env: Env,
  request: { v: 1; tool: string; args: CheckoutInput },
  deps: CheckoutDeps = { work: workInTab },
) {
  if (request.v !== 1 || request.tool !== CHECKOUT_TOOL)
    throw new ToolError("NAVER_SHOPPING_INVALID_INPUT");
  const input = checkInput(request.args);
  const cdpUrl = browserUrl(env);
  let priced: PricedCheckout;
  try {
    priced = await deps.work(
      cdpUrl,
      input.url,
      (page) => priceOnPage(page, "prepare_checkout", input),
      deps.tab,
    );
  } catch (error) {
    if (error instanceof ToolError && error.handoff)
      throw new ToolError(error.code);
    throw error;
  }
  if (priced.soldOut) throw new ToolError("NAVER_SHOPPING_SOLD_OUT");
  return {
    v: 1,
    executionArgs: {
      checkout: 1,
      url: priced.url,
      optionLabels: priced.labels,
      quantity: priced.quantity,
      expectedTotalKrw: priced.expectedTotalKrw,
    },
    summary: {
      kind: "CHECKOUT",
      title: priced.title,
      store: priced.store,
      options: priced.shown,
      quantity: priced.quantity,
      itemAmountKrw: priced.itemAmountKrw,
      shippingFeeKrw: priced.shippingFeeKrw,
      expectedTotalKrw: priced.expectedTotalKrw,
      regionalExtraFee: priced.regionalExtraFee,
      url: priced.url,
    },
  };
}
