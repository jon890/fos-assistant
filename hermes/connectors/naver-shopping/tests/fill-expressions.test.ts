import { expect, test } from "bun:test";
import {
  CHOOSE_OPTION,
  EXPRESSIONS,
  FILL_FUNCTIONS,
  LAYER_OPEN,
  OPEN_LAYER_OPTION,
  OPEN_OPTION_LISTS,
  ORDER_SHEET_AMOUNT,
  PRESS_BUY,
  PRESS_BUY_NOW,
  SET_QUANTITY,
} from "../src/expressions.ts";
import {
  ORDER_SHEET,
  PRODUCT_OPTION_BUTTON,
  PURCHASE_LAYER,
} from "../src/fields.ts";

/** 합성 요소. 실제 네이버 화면이 아니다. 고르는 선택자 글로만 찾는다. */
type El = {
  text?: string;
  hidden?: boolean;
  matches: string[];
  disabled?: boolean;
  value?: string;
  clicked?: number;
  children?: El[];
  layer?: El;
  /** `aria-controls` 값. 이 id 로 `getElementById` 가 찾는다. */
  controls?: string;
  id?: string;
};

function el(spec: Partial<El> & { matches: string[] }): El {
  return { clicked: 0, children: [], ...spec };
}

/** 합성 `document`, `location` 으로 식이나 함수 선언을 돌린다. */
function run(
  source: string,
  page: { host: string; path?: string; elements: El[] },
  args: unknown[] = [],
) {
  const wrap = (e: El): any => ({
    textContent: e.text ?? "",
    getBoundingClientRect: () => ({
      width: e.hidden ? 0 : 10,
      height: e.hidden ? 0 : 10,
    }),
    getAttribute: (name: string) =>
      name === "aria-disabled" && e.disabled
        ? "true"
        : name === "aria-controls"
          ? (e.controls ?? null)
          : null,
    querySelector: (selector: string) => {
      const found = (e.children ?? []).find((c) =>
        c.matches.includes(selector),
      );
      return found ? wrap(found) : null;
    },
    click: () => {
      e.clicked = (e.clicked ?? 0) + 1;
    },
    closest: (selector: string) =>
      e.layer && selector === PURCHASE_LAYER.layer ? wrap(e.layer) : null,
    querySelectorAll: (selector: string) =>
      (e.children ?? []).filter((c) => c.matches.includes(selector)).map(wrap),
    get value() {
      return e.value ?? "";
    },
    set value(v: string) {
      e.value = v;
    },
    dispatchEvent: () => true,
    _el: e,
  });
  const document = {
    querySelectorAll: (selector: string) =>
      page.elements.filter((e) => e.matches.includes(selector)).map(wrap),
    querySelector: (selector: string) => {
      const found = page.elements.find((e) => e.matches.includes(selector));
      return found ? wrap(found) : null;
    },
    getElementById: (id: string) => {
      const found = page.elements.find((e) => e.id === id);
      return found ? wrap(found) : null;
    },
  };
  const location = { hostname: page.host, pathname: page.path ?? "/" };
  const getComputedStyle = () => ({ visibility: "visible" });
  class HTMLInputElement {}
  Object.defineProperty(HTMLInputElement.prototype, "value", {
    set(this: { value: string }, v: string) {
      (this as any)._el.value = v;
    },
  });
  class Event {
    constructor(readonly type: string) {}
  }
  const body = FILL_FUNCTIONS.has(source)
    ? `return (${source}).apply(null, args);`
    : `return ${source};`;
  return new Function(
    "document",
    "location",
    "getComputedStyle",
    "HTMLInputElement",
    "Event",
    "args",
    body,
  )(document, location, getComputedStyle, HTMLInputElement, Event, args);
}

const STORE = "smartstore.naver.com";
const BUTTONS = "button, a[role=button]";

test("모든 식과 함수 선언은 문법이 맞다", () => {
  for (const source of EXPRESSIONS)
    expect(() => new Function(`return ${source};`)).not.toThrow();
  for (const source of FILL_FUNCTIONS)
    expect(() => new Function(`return (${source});`)).not.toThrow();
});

test("구매하기와 바로구매는 글이 정확히 같은 보이는 단추 하나만 누르고, 스토어가 아닌 화면에서는 누르지 않는다", () => {
  const cart = el({ matches: [BUTTONS], text: "장바구니" });
  const hiddenBuy = el({ matches: [BUTTONS], text: "구매하기", hidden: true });
  const buy = el({ matches: [BUTTONS], text: " 구매하기 " });
  const buyNow = el({ matches: [BUTTONS], text: "바로구매" });
  const elements = [cart, hiddenBuy, buy, buyNow];
  expect(run(PRESS_BUY, { host: STORE, elements })).toBe(true);
  expect([
    cart.clicked,
    hiddenBuy.clicked,
    buy.clicked,
    buyNow.clicked,
  ]).toEqual([0, 0, 1, 0]);
  expect(run(LAYER_OPEN, { host: STORE, elements })).toBe(true);
  expect(run(PRESS_BUY_NOW, { host: STORE, elements })).toBe(true);
  expect(buyNow.clicked).toBe(1);
  // 주문서 화면에서는 같은 글의 단추가 있어도 누르지 않는다.
  expect(run(PRESS_BUY_NOW, { host: ORDER_SHEET.host, elements })).toBe(false);
  expect(buyNow.clicked).toBe(1);
  expect(run(PRESS_BUY, { host: STORE, elements: [cart] })).toBe(false);
  expect(cart.clicked).toBe(0);
});

test("옵션 드롭다운 열기는 옵션 자리의 첫 보이는 단추 하나만 누른다", () => {
  const first = el({ matches: [PRODUCT_OPTION_BUTTON], hidden: true });
  const second = el({ matches: [PRODUCT_OPTION_BUTTON] });
  const third = el({ matches: [PRODUCT_OPTION_BUTTON] });
  expect(
    run(OPEN_OPTION_LISTS, { host: STORE, elements: [first, second, third] }),
  ).toBe(true);
  expect([first.clicked, second.clicked, third.clicked]).toEqual([0, 1, 0]);
  expect(
    run(OPEN_OPTION_LISTS, {
      host: "search.shopping.naver.com",
      elements: [second],
    }),
  ).toBe(false);
});

test("층의 옵션은 층 안의 n 번째 단추를 열고, 같은 글을 먼저 고르며 추가 금액 표시가 붙은 글도 고른다", () => {
  const optionA = el({ matches: [PURCHASE_LAYER.optionButton] });
  const optionB = el({ matches: [PURCHASE_LAYER.optionButton] });
  const layer = el({ matches: [], children: [optionA, optionB] });
  const buyNow = el({ matches: [BUTTONS], text: "바로구매", layer });
  expect(run(OPEN_LAYER_OPTION, { host: STORE, elements: [buyNow] }, [1])).toBe(
    true,
  );
  expect([optionA.clicked, optionB.clicked]).toEqual([0, 1]);
  expect(run(OPEN_LAYER_OPTION, { host: STORE, elements: [buyNow] }, [2])).toBe(
    false,
  );

  const exact = el({ matches: [PURCHASE_LAYER.optionItem], text: "검정" });
  const longer = el({
    matches: [PURCHASE_LAYER.optionItem],
    text: "검정 (+1,000원)",
  });
  expect(
    run(CHOOSE_OPTION, { host: STORE, elements: [longer, exact] }, ["검정"]),
  ).toBe("ok");
  expect([exact.clicked, longer.clicked]).toEqual([1, 0]);
  const priced = el({
    matches: [PURCHASE_LAYER.optionItem],
    text: "흰색 (+500원)",
  });
  expect(
    run(CHOOSE_OPTION, { host: STORE, elements: [priced] }, ["흰색"]),
  ).toBe("ok");
  // 다른 변형(「검정 라지」)은 같은 값으로 보지 않는다.
  const variant = el({
    matches: [PURCHASE_LAYER.optionItem],
    text: "검정 라지",
  });
  expect(
    run(CHOOSE_OPTION, { host: STORE, elements: [variant] }, ["검정"]),
  ).toBe("missing");
  expect(variant.clicked).toBe(0);
  const twin = el({
    matches: [PURCHASE_LAYER.optionItem],
    text: "빨강 (+100원)",
  });
  const twin2 = el({
    matches: [PURCHASE_LAYER.optionItem],
    text: "빨강(+200원)",
  });
  expect(
    run(CHOOSE_OPTION, { host: STORE, elements: [twin, twin2] }, ["빨강"]),
  ).toBe("ambiguous");
  const soldOut = el({
    matches: [PURCHASE_LAYER.optionItem],
    text: "파랑",
    disabled: true,
  });
  expect(
    run(CHOOSE_OPTION, { host: STORE, elements: [soldOut] }, ["파랑"]),
  ).toBe("disabled");
  expect(soldOut.clicked).toBe(0);
  expect(run(CHOOSE_OPTION, { host: STORE, elements: [] }, ["없음"])).toBe(
    "missing",
  );
});

test("층 안에서 펼친 드롭다운이 있으면 그 단추가 가리키는 목록 안에서만 고르고 층 밖 목록의 값은 누르지 않는다", () => {
  const outside = el({ matches: [PURCHASE_LAYER.optionItem], text: "검정" });
  const inside = el({ matches: [PURCHASE_LAYER.optionItem], text: "검정" });
  const list = el({ matches: [], id: "layer-list", children: [inside] });
  const opened = el({
    matches: [PURCHASE_LAYER.openedOptionButton],
    controls: "layer-list",
  });
  const layer = el({ matches: [], children: [opened] });
  const buyNow = el({ matches: [BUTTONS], text: "바로구매", layer });
  expect(
    run(CHOOSE_OPTION, { host: STORE, elements: [buyNow, outside, list] }, [
      "검정",
    ]),
  ).toBe("ok");
  expect([inside.clicked, outside.clicked]).toEqual([1, 0]);
  // 가리키는 목록이 없으면 문서 전체로 넘어가지 않는다.
  const lost = el({
    matches: [PURCHASE_LAYER.openedOptionButton],
    controls: "gone",
  });
  const lostBuyNow = el({
    matches: [BUTTONS],
    text: "바로구매",
    layer: el({ matches: [], children: [lost] }),
  });
  expect(
    run(CHOOSE_OPTION, { host: STORE, elements: [lostBuyNow, outside] }, [
      "검정",
    ]),
  ).toBe("missing");
  expect(outside.clicked).toBe(0);
});

test("수량은 층 안의 숫자 칸 하나에 넣고 지금 값을 준다. 칸이 없으면 null, 둘이면 -1 이다", () => {
  const input = el({ matches: [PURCHASE_LAYER.quantityInput], value: "1" });
  const layer = el({ matches: [], children: [input] });
  const buyNow = el({ matches: [BUTTONS], text: "바로구매", layer });
  expect(run(SET_QUANTITY, { host: STORE, elements: [buyNow] }, [3])).toBe(3);
  expect(input.value).toBe("3");
  const empty = el({
    matches: [BUTTONS],
    text: "바로구매",
    layer: el({ matches: [] }),
  });
  expect(run(SET_QUANTITY, { host: STORE, elements: [empty] }, [1])).toBeNull();
  const two = el({
    matches: [BUTTONS],
    text: "바로구매",
    layer: el({
      matches: [],
      children: [
        input,
        el({ matches: [PURCHASE_LAYER.quantityInput], value: "1" }),
      ],
    }),
  });
  expect(run(SET_QUANTITY, { host: STORE, elements: [two] }, [1])).toBe(-1);
});

test("주문서 금액 식은 주문서 주소에서만 두 자리의 금액 정수를 준다", () => {
  const total = el({ matches: [ORDER_SHEET.total], text: "3,300원" });
  const summary = el({ matches: [ORDER_SHEET.methodSummary], text: "3,300원" });
  const sheet = {
    host: ORDER_SHEET.host,
    path: "/ordersheet/seller/x",
    elements: [total, summary],
  };
  expect(run(ORDER_SHEET_AMOUNT, sheet)).toEqual({
    total: 3300,
    summary: 3300,
  });
  expect(run(ORDER_SHEET_AMOUNT, { ...sheet, elements: [total] })).toEqual({
    total: 3300,
    summary: null,
  });
  expect(run(ORDER_SHEET_AMOUNT, { ...sheet, path: "/other" })).toBeNull();
  expect(run(ORDER_SHEET_AMOUNT, { ...sheet, host: STORE })).toBeNull();
});
