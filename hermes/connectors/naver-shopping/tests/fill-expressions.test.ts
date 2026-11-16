import { expect, test } from "bun:test";
import {
  BUY_NOW_ENABLED,
  CHOOSE_LAYER_RADIO,
  CHOOSE_OPTION,
  EXPRESSIONS,
  FILL_FUNCTIONS,
  LAYER_OPEN,
  LAYER_OPTION_FORM,
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
  /** 부모 요소. `parentElement` 로 올라간다. */
  parent?: El;
  /** 그 밖의 속성(`alt`, `aria-checked`). */
  attrs?: Record<string, string>;
  /** `disabled` 속성. */
  blocked?: boolean;
  /** 누르면 `aria-checked="true"` 가 된다(radio 칩). */
  checkOnClick?: boolean;
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
    get disabled() {
      return e.blocked === true;
    },
    getAttribute: (name: string) =>
      e.attrs?.[name] !== undefined
        ? e.attrs[name]
        : name === "aria-disabled" && e.disabled
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
      if (e.checkOnClick) e.attrs = { ...e.attrs, "aria-checked": "true" };
    },
    get parentElement() {
      return e.parent ? wrap(e.parent) : null;
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

test("옵션 드롭다운 열기는 class 와 상관없이 보이는 단추가 하나면 그것만 누르고, 스토어가 아닌 화면에서는 누르지 않는다", () => {
  // 2026-11-16 실측: 해시 class 단추 하나. 선택자에 class 조건이 없어야 맞는다.
  expect(PRODUCT_OPTION_BUTTON).not.toContain("class");
  const hidden = el({ matches: [PRODUCT_OPTION_BUTTON], hidden: true });
  const only = el({ matches: [PRODUCT_OPTION_BUTTON] });
  expect(
    run(OPEN_OPTION_LISTS, { host: STORE, elements: [hidden, only] }),
  ).toBe(true);
  expect([hidden.clicked, only.clicked]).toEqual([0, 1]);
  expect(
    run(OPEN_OPTION_LISTS, {
      host: "search.shopping.naver.com",
      elements: [only],
    }),
  ).toBe(false);
  expect(only.clicked).toBe(1);
});

test("보이는 드롭다운이 둘 이상이면 「구매하기」 와 같은 구매 상자 안의 것만 누르고, 「구매하기」 가 없으면 누르지 않는다", () => {
  const sort = el({ matches: [PRODUCT_OPTION_BUTTON] });
  const option = el({ matches: [PRODUCT_OPTION_BUTTON] });
  const buy = el({ matches: [BUTTONS], text: "구매하기" });
  const box = el({ matches: [], children: [option, buy] });
  const page = el({ matches: [], children: [sort, box] });
  option.parent = box;
  buy.parent = box;
  box.parent = page;
  sort.parent = page;
  // 문서 차례로는 정렬 단추가 먼저다.
  expect(
    run(OPEN_OPTION_LISTS, { host: STORE, elements: [sort, option, buy] }),
  ).toBe(true);
  expect([sort.clicked, option.clicked]).toEqual([0, 1]);
  expect(
    run(OPEN_OPTION_LISTS, { host: STORE, elements: [sort, option] }),
  ).toBe(false);
  expect([sort.clicked, option.clicked]).toEqual([0, 1]);
});

test("「구매하기」 에서 가장 가까운, 단추가 든 조상에 단추가 둘 이상이면 어느 것도 누르지 않는다", () => {
  // 구매 상자에 옵션 단추가 없어 페이지 래퍼까지 올라간 경우. 래퍼 안에는 다른 드롭다운 둘이 있다.
  const sort = el({ matches: [PRODUCT_OPTION_BUTTON] });
  const filter = el({ matches: [PRODUCT_OPTION_BUTTON] });
  const buy = el({ matches: [BUTTONS], text: "구매하기" });
  const box = el({ matches: [], children: [buy] });
  const wrapper = el({ matches: [], children: [sort, filter, box] });
  buy.parent = box;
  box.parent = wrapper;
  sort.parent = wrapper;
  filter.parent = wrapper;
  expect(
    run(OPEN_OPTION_LISTS, { host: STORE, elements: [sort, filter, buy] }),
  ).toBe(false);
  expect([sort.clicked, filter.clicked]).toEqual([0, 0]);
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

/** 2026-11-17 실측 꼴의 칩. 색상은 글 없이 `img alt` 가 「<값>false」, 사이즈는 글이 값이다. */
function chip(value: { text?: string; alt?: string; blocked?: boolean }) {
  return el({
    matches: [PURCHASE_LAYER.radio],
    text: value.text,
    blocked: value.blocked,
    attrs: { "aria-checked": "false" },
    checkOnClick: true,
    children: value.alt
      ? [
          el({
            matches: [PURCHASE_LAYER.radioImage],
            attrs: { alt: value.alt },
          }),
        ]
      : [],
  });
}

function radioLayer() {
  const black = chip({ alt: "블랙false" });
  const blackLarge = chip({ alt: "블랙 라지false" });
  const white = chip({ alt: "화이트true" });
  const colors = el({
    matches: [PURCHASE_LAYER.radioGroup],
    children: [black, blackLarge, white],
  });
  const sizes = ["S", "M", "L", "XL"].map((text) => chip({ text }));
  const size = el({ matches: [PURCHASE_LAYER.radioGroup], children: sizes });
  const layer = el({ matches: [], children: [colors, size] });
  const buyNow = el({ matches: [BUTTONS], text: "바로구매", layer });
  return { black, blackLarge, white, sizes, layer, buyNow };
}

test("층의 radio 칩은 묶음 차례로 값이 정확히 같은 칩 하나만 누르고, 누르지 않고 골라졌는지 다시 읽는다", () => {
  const { black, blackLarge, white, sizes, buyNow } = radioLayer();
  const page = { host: STORE, elements: [buyNow] };
  expect(run(LAYER_OPTION_FORM, page)).toEqual({ radios: 2, lists: 0 });
  // 읽기만 하면 누르지 않는다.
  expect(run(CHOOSE_LAYER_RADIO, page, [0, "블랙", false])).toBe("unchecked");
  expect(black.clicked).toBe(0);
  expect(run(CHOOSE_LAYER_RADIO, page, [0, "블랙", true])).toBe("pressed");
  expect([black.clicked, blackLarge.clicked]).toEqual([1, 0]);
  expect(run(CHOOSE_LAYER_RADIO, page, [0, "블랙", false])).toBe("checked");
  // 이미 골라져 있으면 다시 누르지 않는다.
  expect(run(CHOOSE_LAYER_RADIO, page, [0, "블랙", true])).toBe("checked");
  expect(black.clicked).toBe(1);
  // alt 끝의 true 도 뗀다.
  expect(run(CHOOSE_LAYER_RADIO, page, [0, "화이트", true])).toBe("pressed");
  expect(white.clicked).toBe(1);
  // 사이즈는 글이 값이다. 「L」 이 「XL」 을 고르지 않는다.
  expect(run(CHOOSE_LAYER_RADIO, page, [1, "L", true])).toBe("pressed");
  expect(sizes.map((s) => s.clicked)).toEqual([0, 0, 1, 0]);
  // 값을 다른 묶음에서 찾지 않는다.
  expect(run(CHOOSE_LAYER_RADIO, page, [1, "블랙", true])).toBe("missing");
  expect(run(CHOOSE_LAYER_RADIO, page, [2, "L", true])).toBe("missing");
  expect(run(CHOOSE_LAYER_RADIO, page, [1, "XXL", true])).toBe("missing");
  // 스토어가 아닌 화면에서는 누르지 않는다.
  expect(
    run(CHOOSE_LAYER_RADIO, { ...page, host: ORDER_SHEET.host }, [
      1,
      "M",
      true,
    ]),
  ).toBe(false);
  expect(sizes[1]!.clicked).toBe(0);
});

test("층의 radio 칩이 둘 이상 같거나 막혀 있으면 누르지 않고, 층을 찾지 못하면 고르지 않는다", () => {
  const twins = [chip({ text: "M" }), chip({ text: "M" })];
  const blocked = chip({ text: "L", blocked: true });
  const ariaBlocked = el({
    matches: [PURCHASE_LAYER.radio],
    text: "XL",
    disabled: true,
  });
  const group = el({
    matches: [PURCHASE_LAYER.radioGroup],
    children: [...twins, blocked, ariaBlocked],
  });
  const layer = el({ matches: [], children: [group] });
  const page = {
    host: STORE,
    elements: [el({ matches: [BUTTONS], text: "바로구매", layer })],
  };
  expect(run(CHOOSE_LAYER_RADIO, page, [0, "M", true])).toBe("ambiguous");
  expect(run(CHOOSE_LAYER_RADIO, page, [0, "L", true])).toBe("disabled");
  expect(run(CHOOSE_LAYER_RADIO, page, [0, "XL", true])).toBe("disabled");
  expect([...twins, blocked, ariaBlocked].map((c) => c.clicked)).toEqual([
    0, 0, 0, 0,
  ]);
  // 「바로구매」 가 층 안에 없으면 문서의 칩 묶음을 세지도 고르지도 않는다.
  const loose = chip({ text: "M" });
  const looseGroup = el({
    matches: [PURCHASE_LAYER.radioGroup],
    children: [loose],
  });
  const noLayer = {
    host: STORE,
    elements: [el({ matches: [BUTTONS], text: "바로구매" }), looseGroup],
  };
  expect(run(LAYER_OPTION_FORM, noLayer)).toEqual({ radios: 0, lists: 0 });
  expect(run(CHOOSE_LAYER_RADIO, noLayer, [0, "M", true])).toBe("missing");
  expect(loose.clicked).toBe(0);
});

test("「바로구매」 가 막혀 있으면 열림 확인이 거짓이고 누르지 않는다", () => {
  const blocked = el({ matches: [BUTTONS], text: "바로구매", blocked: true });
  expect(run(BUY_NOW_ENABLED, { host: STORE, elements: [blocked] })).toBe(
    false,
  );
  expect(run(PRESS_BUY_NOW, { host: STORE, elements: [blocked] })).toBe(false);
  const ariaBlocked = el({
    matches: [BUTTONS],
    text: "바로구매",
    disabled: true,
  });
  expect(run(PRESS_BUY_NOW, { host: STORE, elements: [ariaBlocked] })).toBe(
    false,
  );
  expect([blocked.clicked, ariaBlocked.clicked]).toEqual([0, 0]);
  const open = el({ matches: [BUTTONS], text: "바로구매" });
  expect(run(BUY_NOW_ENABLED, { host: STORE, elements: [open] })).toBe(true);
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
