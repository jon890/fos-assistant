/**
 * 채우기의 층과 주문서 단계(`fill.ts` 가 부른다). 옵션·수량 맞추기와 주문서 금액 읽기다.
 * 결제 단추를 누르는 코드 경로가 없다. 주문서 탭에서는 준비 상태, 화면 분류, 최종 금액 읽기만 부른다.
 */
import { classifyPage } from "./classify.ts";
import { ToolError } from "./errors.ts";
import {
  BUY_NOW_ENABLED,
  CHOOSE_LAYER_RADIO,
  CHOOSE_OPTION,
  LAYER_OPTION_FORM,
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

/**
 * 층 안에서 옵션 값을 단계마다 고르고 수량을 맞춘 뒤 다시 읽어 확인한다. 「바로구매」 가 막혀 있지 않을 때까지 기다린다.
 * 못 맞추면 「바로구매」 를 누르지 않고 실패다. 옵션 꼴은 층에 옵션 묶음(칩)이 있으면 칩, 없으면 드롭다운이다.
 */
export async function chooseInLayer(
  page: Page,
  input: FillInput,
  stepMs: number,
  waitMs: number,
) {
  const deadline = () => Date.now() + Math.min(waitMs, page.remaining());
  if (input.optionLabels.length > 0) {
    const radios = await waitOptionForm(page, stepMs, deadline());
    if (radios > 0) {
      // 묶음 수가 옵션 글 수와 다르면 어느 묶음이 어느 글인지 모른다.
      if (radios !== input.optionLabels.length) throw failed();
      for (const [index, label] of input.optionLabels.entries())
        await chooseRadio(page, index, label, stepMs, deadline());
      // 뒤 묶음을 고르며 앞 묶음이 풀리지 않았는지 누르지 않고 다시 읽는다.
      for (const [index, label] of input.optionLabels.entries())
        if (
          (await page.call(CHOOSE_LAYER_RADIO, [index, label, false])) !==
          "checked"
        )
          throw failed();
    } else {
      for (const [index, label] of input.optionLabels.entries())
        await chooseListItem(page, index, label, stepMs, deadline());
    }
  }
  // 수량 칸이 없으면 층의 기본값 1 만 받는다.
  const matches = (value: unknown) =>
    value === input.quantity || (value === null && input.quantity === 1);
  if (!matches(await page.call(SET_QUANTITY, [input.quantity]))) throw failed();
  await Bun.sleep(stepMs);
  if (!matches(await page.call(SET_QUANTITY, [input.quantity]))) throw failed();
  // 「바로구매」 가 막혀 있으면 고르기가 덜 된 것이다. 누르지 않는다.
  const until = deadline();
  while ((await page.evaluate(BUY_NOW_ENABLED)) !== true) {
    if (Date.now() >= until) throw failed();
    await Bun.sleep(stepMs);
  }
}

/** 층에 옵션 묶음이나 드롭다운이 보이기를 기다리고 묶음 수를 준다. 둘 다 없으면 실패다. */
async function waitOptionForm(page: Page, stepMs: number, until: number) {
  while (true) {
    const form = (await page.evaluate(LAYER_OPTION_FORM)) as {
      radios?: unknown;
      lists?: unknown;
    } | null;
    const radios = typeof form?.radios === "number" ? form.radios : 0;
    const lists = typeof form?.lists === "number" ? form.lists : 0;
    if (radios > 0 || lists > 0) return radios;
    if (Date.now() >= until) throw failed();
    await Bun.sleep(stepMs);
  }
}

/** `index` 번째 묶음에서 값 칩 하나를 한 번만 누르고, 골라졌다고 읽힐 때까지 기다린다. */
async function chooseRadio(
  page: Page,
  index: number,
  label: string,
  stepMs: number,
  until: number,
) {
  let pressed = false;
  while (true) {
    const state = await page.call(CHOOSE_LAYER_RADIO, [index, label, !pressed]);
    if (state === "checked") return;
    // 누르기 전에는 칩이 그려지기를, 누른 뒤에는 골라졌다고 읽히기를 기다린다. 그 밖의 답은 실패다.
    const waiting = pressed ? state === "unchecked" : state === "missing";
    if (state === "pressed") pressed = true;
    else if (!waiting) throw failed();
    if (Date.now() >= until) throw failed();
    await Bun.sleep(stepMs);
  }
}

/** `index` 번째 드롭다운을 열고 값 하나를 고른다(조합형 옵션 층의 가설). */
async function chooseListItem(
  page: Page,
  index: number,
  label: string,
  stepMs: number,
  until: number,
) {
  if ((await page.call(OPEN_LAYER_OPTION, [index])) !== true) throw failed();
  while (true) {
    const chosen = await page.call(CHOOSE_OPTION, [label]);
    if (chosen === "ok") break;
    if (chosen !== "missing" || Date.now() >= until) throw failed();
    await Bun.sleep(stepMs);
  }
  await Bun.sleep(stepMs);
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
