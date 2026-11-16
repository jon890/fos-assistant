/**
 * 채우기의 층과 주문서 단계(`fill.ts` 가 부른다). 옵션·수량 맞추기와 주문서 금액 읽기다.
 * 결제 단추를 누르는 코드 경로가 없다. 주문서 탭에서는 준비 상태, 화면 분류, 최종 금액 읽기만 부른다.
 */
import { classifyPage } from "./classify.ts";
import { ToolError } from "./errors.ts";
import {
  CHOOSE_OPTION,
  OPEN_LAYER_OPTION,
  ORDER_SHEET_AMOUNT,
  PAGE_PROBE,
  SET_QUANTITY,
} from "./expressions.ts";
import { ORDER_SHEET } from "./fields.ts";
import { waitReady, type Page } from "./tab.ts";
import type { FillInput } from "./fill.ts";

export const failed = () => new ToolError("NAVER_SHOPPING_CHECKOUT_FAILED");

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

/** 층 안에서 옵션 값을 단계마다 고르고 수량을 맞춘 뒤 다시 읽어 확인한다. 못 맞추면 「바로구매」 를 누르지 않고 실패다. */
export async function chooseInLayer(
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
  if (!matches(await page.call(SET_QUANTITY, [input.quantity]))) throw failed();
  await Bun.sleep(stepMs);
  if (!matches(await page.call(SET_QUANTITY, [input.quantity]))) throw failed();
}

/** 주문서 탭에서 화면 분류를 하고 두 자리의 금액이 같아질 때까지 읽는다. 사람이 해야 하는 화면이거나 읽지 못하면 `null` 이다. */
export async function readSheet(page: Page, stepMs: number, waitMs: number) {
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
