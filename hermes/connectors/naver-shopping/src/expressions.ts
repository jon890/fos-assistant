/**
 * 탭에서 `Runtime.evaluate` 로 부르는 식과 `Runtime.callFunctionOn` 으로 부르는 함수 선언은 이 파일의 상수뿐이다.
 * 모델이나 인자가 식을 정하지 못한다. 함수 선언이 받는 옵션 글과 수량은 인자 값으로만 넘어간다.
 * 읽기 도구의 누르기는 옵션 드롭다운을 여는 `OPEN_OPTION_LISTS` 하나다. 채우기만 쓰는 식은 `FILL_EXPRESSIONS`, `FILL_FUNCTIONS` 로 따로 묶는다.
 * 이동, 내리기 식과 결제 단추를 누르는 식은 두지 않는다. 테스트가 탭에 보낸 식이 모두 이 집합 안인지 본다.
 */
import { CAPTCHA_SELECTOR } from "./classify.ts";
import {
  ORDER_SHEET,
  PRODUCT_OPTION_BUTTON,
  PRODUCT_STATE_PATH,
  PURCHASE_LAYER,
} from "./fields.ts";
import { STORE_HOSTS } from "./urls.ts";

/** 경로를 따라 내려가는 식 조각. 경로는 이 저장소의 상수라 글자를 그대로 싣는다. */
function walk(root: string, path: string[]) {
  return path.reduce(
    (expression, key) => `${expression}?.[${JSON.stringify(key)}]`,
    root,
  );
}

export const READY_STATE = "document.readyState";

/**
 * 그려진 요소만 센다. 크기 0(`script`, `style`, `display:none` 포함)과 `visibility:hidden` 은 보이지 않는다.
 * 보안 확인이 아닌 정상 화면에도 숨은 captcha 스크립트나 틀이 있어 요소의 존재만으로는 오판한다.
 * korail-ktx 의 `VISIBLE` 과 같은 규칙이다. 플러그인은 서로 코드를 나누지 못해 옮겨 썼다.
 */
const VISIBLE = `(e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== "hidden"; }`;

export const PAGE_PROBE = `(() => { const visible = ${VISIBLE}; return { href: location.href, captcha: [...document.querySelectorAll(${JSON.stringify(CAPTCHA_SELECTOR)})].some(visible) }; })()`;

/**
 * App Router 화면(검색, 가격비교)의 조각에서 객체를 꺼내는 식을 만든다(2026-11-13 실측). `__NEXT_DATA__` 는 없고 데이터는
 * `self.__next_f.push([1,"<조각>"])` 로 시작하는 스크립트들의 둘째 값(문자열)에 흩어져 있다. `window.__next_f` 는
 * hydration 뒤 일부만 남아 쓰지 않는다. 조각을 이어야 객체가 완성되는 경우가 있어 이은 글 하나를 훑는다.
 * 이은 글은 줄마다 JSON 한 값이라 줄이 바뀌면 문자열과 괄호 상태를 버린다. 텍스트 행(`<id>:T<16진 길이>,<글>`)은 줄바꿈 없이
 * 길이(UTF-8 바이트)로 끝나므로, 행 첫머리에서 그 머리를 알아보면 그 길이만큼 통째로 건너뛴다(글 안의 짝 없는 따옴표와 괄호가 뒤 행을 망가뜨리지 못하게).
 * `keys` 를 모두 직접 가진 가장 안쪽 객체를 찾아 `pick` 이 고른 칸만 담아 `found` 에 모은다. `limit` 개를 모으면 멈춘다.
 * 조각 스크립트가 하나도 없으면 `null` 을 돌려준다. 읽기만 하고 화면을 바꾸지 않는다. `keys` 와 `limit`, `pick` 은 이 저장소의 상수다.
 */
function fragmentScan(keys: string[], limit: number, pick: string) {
  return `(() => { try {
  const PREFIX = "self.__next_f.push(";
  let text = "";
  for (const script of document.scripts) {
    const source = (script.textContent ?? "").trimStart();
    if (!source.startsWith(PREFIX)) continue;
    const end = source.lastIndexOf(")");
    if (end < PREFIX.length) continue;
    try {
      const chunk = JSON.parse(source.slice(PREFIX.length, end));
      if (Array.isArray(chunk) && chunk[0] === 1 && typeof chunk[1] === "string") text += chunk[1];
    } catch {}
  }
  if (!text) return null;
  const KEYS = ${JSON.stringify(keys)};
  const found = [];
  const TEXT_ROW = /[0-9a-f]+:T([0-9a-f]+),/y;
  let stack = [];
  let rowStart = true;
  for (let i = 0; i < text.length && found.length < ${limit}; i++) {
    if (rowStart) {
      rowStart = false;
      TEXT_ROW.lastIndex = i;
      const head = TEXT_ROW.exec(text);
      if (head) {
        let bytes = parseInt(head[1], 16);
        let k = i + head[0].length;
        while (bytes > 0 && k < text.length) {
          const cp = text.codePointAt(k);
          bytes -= cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
          k += cp > 0xffff ? 2 : 1;
        }
        stack = [];
        rowStart = true;
        i = k - 1;
        continue;
      }
    }
    const c = text[i];
    if (c === "\\n") { stack = []; rowStart = true; continue; }
    if (c === '"') {
      let j = i + 1;
      while (j < text.length && text[j] !== '"' && text[j] !== "\\n") j += text[j] === "\\\\" ? 2 : 1;
      if (stack.length) {
        for (const key of KEYS) {
          if (text.startsWith(key + '"', i + 1) && text[i + key.length + 2] === ":") stack[stack.length - 1].seen.add(key);
        }
      }
      i = text[j] === "\\n" ? j - 1 : j;
    } else if (c === "{") {
      stack.push({ start: i, seen: new Set() });
    } else if (c === "}") {
      const frame = stack.pop();
      if (!frame || frame.seen.size < KEYS.length) continue;
      try {
        const o = JSON.parse(text.slice(frame.start, i + 1));
        ${pick}
      } catch {}
    }
  }
  return found;
} catch { return null; } })()`;
}

/** 고정 식이 한 번에 돌려주는 상품 객체의 상한. 한 화면은 48개였다. 한 번 더 걸러 40개로 자르는 것은 `parse-search.ts` 다. */
export const SEARCH_RAW_MAX = 200;

/**
 * 검색 화면의 상품을 꺼낸다(2026-11-13 실측). `"productName"` 칸을 직접 가진 가장 안쪽 객체를 상품으로 보고 필요한 칸만 담아
 * JSON 글로 준다. 조각 스크립트가 하나도 없으면 `null`, 있는데 상품이 없으면 `[]` 다.
 */
export const SEARCH_STATE = `(() => { const r = ${fragmentScan(
  ["productName"],
  SEARCH_RAW_MAX,
  `if (typeof o.productName !== "string") continue;
        const url = o.productUrl;
        found.push({
          productName: o.productName,
          salePrice: o.salePrice,
          discountedSalePrice: o.discountedSalePrice,
          mallName: o.mallName,
          productUrl: url && typeof url === "object" ? { pcUrl: url.pcUrl } : undefined,
          nvMid: o.nvMid,
          adId: o.adId,
          cardType: o.cardType,
        });`,
)}; return r === null ? null : JSON.stringify(r); })()`;

/**
 * 가격비교 화면의 묶음 정보를 꺼낸다(2026-11-13 실측, 판매처 목록 제외). `"nCatalogId"` 와 `"lowestPrice"` 를 함께 직접 가진
 * 가장 안쪽 객체들에서 필요한 칸만 담아 JSON 배열 글로 준다. 같은 조각의 광고·추천 상품 객체는 이 두 칸이 없어 걸리지 않는다.
 * 비교·추천 묶음이 앞서 실릴 수 있고 고정 식은 연 주소를 모르므로, 연 주소의 번호와 같은 묶음을 고르는 일은 `parse-product.ts` 가 한다.
 * 조각 스크립트가 없으면 `null`, 있는데 묶음 객체가 없으면 `[]` 다.
 */
export const CATALOG_RAW_MAX = 20;

export const CATALOG_STATE = `(() => { const r = ${fragmentScan(
  ["nCatalogId", "lowestPrice"],
  CATALOG_RAW_MAX,
  `found.push({
          nCatalogId: o.nCatalogId,
          productName: o.productName,
          lowestPrice: o.lowestPrice,
          deliveryFee: o.deliveryFee,
          lowestPriceWithFee: o.lowestPriceWithFee,
          differentialDeliveryFeeContent: o.differentialDeliveryFeeContent,
          categoryId: o.categoryId,
        });`,
)}; return r === null ? null : JSON.stringify(r); })()`;

/** 상품 화면은 상태가 있는지(`present`)와 상품 칸(`value`)을 함께 준다. 상태는 있는데 상품이 없으면 없는 상품이다. */
export const PRODUCT_STATE = `(() => { try { const s = window.__PRELOADED_STATE__; if (!s || typeof s !== "object") return null; const v = ${walk("s", PRODUCT_STATE_PATH)}; return JSON.stringify({ present: true, value: v ?? null }); } catch { return null; } })()`;

/** 함수 선언을 부를 대상(페이지의 전역 객체)의 id 를 받는 식. 값을 읽지 않는다. */
export const GLOBAL_OBJECT = "globalThis";

/** 누르는 식의 머리. 지금 주소가 스마트스토어·브랜드스토어가 아니면 누르지 않는다. 주문서 화면에서는 아무것도 누르지 않는다. */
const ON_STORE = `if (!${JSON.stringify([...STORE_HOSTS])}.includes(location.hostname.toLowerCase())) return false;`;

/** 보이는 단추 가운데 글(빈칸을 하나로 줄인 것)이 `label` 과 정확히 같은 첫 단추. */
const BUTTON_BY_LABEL = `(label) => [...document.querySelectorAll("button, a[role=button]")].find((e) => visible(e) && (e.textContent ?? "").replace(/\\s+/g, " ").trim() === label) ?? null`;

/** 「바로구매」 단추가 든 가장 가까운 층. 없으면 문서 전체다. */
const LAYER_SCOPE = `() => { const b = byLabel(${JSON.stringify(PURCHASE_LAYER.buyNowLabel)}); return (b && b.closest(${JSON.stringify(PURCHASE_LAYER.layer)})) ?? document; }`;

const HELPERS = `const visible = ${VISIBLE}; const byLabel = ${BUTTON_BY_LABEL}; const layerScope = ${LAYER_SCOPE};`;

/**
 * 상품 화면의 옵션 자리 안의 첫 드롭다운 단추 하나만 누른다(사용자 결정 2026-11-13). 값을 고르거나 구매하기·장바구니를 누르지 않는다.
 * 읽기 도구(`get_product_detail`, `prepare_checkout`)가 부르는 유일한 누르기다. 누르면 참이다.
 */
export const OPEN_OPTION_LISTS = `(() => { try { ${ON_STORE} const visible = ${VISIBLE}; const b = [...document.querySelectorAll(${JSON.stringify(PRODUCT_OPTION_BUTTON)})].find(visible); if (!b) return false; b.click(); return true; } catch { return false; } })()`;

/** 상품 화면의 「구매하기」 를 누른다. 같은 화면에 상품 구매 층이 뜬다(2026-11-13 실측). 장바구니는 누르지 않는다. */
export const PRESS_BUY = `(() => { try { ${ON_STORE} ${HELPERS} const b = byLabel(${JSON.stringify(PURCHASE_LAYER.buyLabel)}); if (!b) return false; b.click(); return true; } catch { return false; } })()`;

/** 상품 구매 층이 떴는가(보이는 「바로구매」 단추가 있는가). 누르지 않는다. */
export const LAYER_OPEN = `(() => { try { ${HELPERS} return byLabel(${JSON.stringify(PURCHASE_LAYER.buyNowLabel)}) !== null; } catch { return false; } })()`;

/** 층의 「바로구매」 를 누른다. 이 뒤로는 주문서가 열렸을 수 있다. */
export const PRESS_BUY_NOW = `(() => { try { ${ON_STORE} ${HELPERS} const b = byLabel(${JSON.stringify(PURCHASE_LAYER.buyNowLabel)}); if (!b) return false; b.click(); return true; } catch { return false; } })()`;

/**
 * 주문서의 최종 결제 금액을 두 자리에서 읽는다(2026-11-13 실측). 주문서 주소가 아니면 `null` 이다.
 * 글에서 첫 금액 숫자만 정수로 바꿔 `{total, summary}` 로 준다. 이름, 주소, 전화번호 같은 화면 글은 돌려주지 않는다.
 */
export const ORDER_SHEET_AMOUNT = `(() => { try { if (location.hostname !== ${JSON.stringify(ORDER_SHEET.host)} || !location.pathname.startsWith(${JSON.stringify(ORDER_SHEET.pathPrefix)})) return null; const amount = (selector) => { const e = document.querySelector(selector); const m = /[0-9][0-9,]*/.exec(e?.textContent ?? ""); if (!m) return null; const n = Number(m[0].replace(/,/g, "")); return Number.isSafeInteger(n) ? n : null; }; return { total: amount(${JSON.stringify(ORDER_SHEET.total)}), summary: amount(${JSON.stringify(ORDER_SHEET.methodSummary)}) }; } catch { return null; } })()`;

/** 층 안의 `index` 번째 옵션 드롭다운을 연다. 열면 참이다. */
export const OPEN_LAYER_OPTION = `function (index) { try { ${ON_STORE} ${HELPERS} const b = [...layerScope().querySelectorAll(${JSON.stringify(PURCHASE_LAYER.optionButton)})].filter(visible)[index]; if (!b) return false; b.click(); return true; } catch { return false; } }`;

/**
 * 열린 목록에서 글이 `label` 과 같은 값 하나를 고른다. 같은 것이 없으면 `label` 뒤에 빈칸이나 「(」 가 이어지는 것(추가 금액 표시)을 본다.
 * `ok`, `missing`, `ambiguous`, `disabled` 가운데 하나를 준다.
 */
export const CHOOSE_OPTION = `function (label) { try { ${ON_STORE} const visible = ${VISIBLE}; const items = [...document.querySelectorAll(${JSON.stringify(PURCHASE_LAYER.optionItem)})].filter(visible); const norm = (e) => (e.textContent ?? "").replace(/\\s+/g, " ").trim(); let hits = items.filter((e) => norm(e) === label); if (hits.length === 0) hits = items.filter((e) => norm(e).startsWith(label + " ") || norm(e).startsWith(label + "(")); if (hits.length === 0) return "missing"; if (hits.length > 1) return "ambiguous"; if (hits[0].getAttribute("aria-disabled") === "true") return "disabled"; hits[0].click(); return "ok"; } catch { return "missing"; } }`;

/**
 * 층 안의 수량 칸 하나에 `quantity` 를 넣고 지금 값을 준다. 같으면 넣지 않고 읽기만 한다.
 * 칸이 없으면 `null`, 둘 이상이면 `-1` 이다. React 가 받도록 값 설정자와 `input`, `change` 사건을 쓴다.
 */
export const SET_QUANTITY = `function (quantity) { try { ${ON_STORE} ${HELPERS} const inputs = [...layerScope().querySelectorAll(${JSON.stringify(PURCHASE_LAYER.quantityInput)})].filter(visible); if (inputs.length === 0) return null; if (inputs.length > 1) return -1; const input = inputs[0]; if (Number(input.value) !== quantity) { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(input, String(quantity)); input.dispatchEvent(new Event("input", { bubbles: true })); input.dispatchEvent(new Event("change", { bubbles: true })); } return Number(input.value); } catch { return null; } }`;

/** 채우기만 부르는 식. */
export const FILL_EXPRESSIONS = new Set([
  PRESS_BUY,
  LAYER_OPEN,
  PRESS_BUY_NOW,
  ORDER_SHEET_AMOUNT,
]);

/** 채우기만 부르는 함수 선언. `Runtime.callFunctionOn` 에 이 글 그대로 싣는다. */
export const FILL_FUNCTIONS = new Set([
  OPEN_LAYER_OPTION,
  CHOOSE_OPTION,
  SET_QUANTITY,
]);

/** 읽기 도구와 채우기가 부르는 식 전체. */
export const EXPRESSIONS = new Set([
  READY_STATE,
  PAGE_PROBE,
  SEARCH_STATE,
  CATALOG_STATE,
  PRODUCT_STATE,
  GLOBAL_OBJECT,
  OPEN_OPTION_LISTS,
  ...FILL_EXPRESSIONS,
]);

/** 누르는 식과 함수 선언. 읽기 도구는 이 가운데 `OPEN_OPTION_LISTS` 만 부른다. */
export const CLICKING = new Set([
  OPEN_OPTION_LISTS,
  PRESS_BUY,
  PRESS_BUY_NOW,
  OPEN_LAYER_OPTION,
  CHOOSE_OPTION,
]);
