/**
 * 탭에서 `Runtime.evaluate` 로 부르는 식은 이 파일의 상수뿐이다. 모델이나 인자가 식을 정하지 못한다.
 * 누르기, 입력, 이동, 내리기 식은 두지 않는다. 테스트가 탭에 보낸 식이 모두 이 집합 안인지 본다.
 */
import { CAPTCHA_SELECTOR } from "./classify.ts";
import { PRODUCT_STATE_PATH } from "./fields.ts";

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
 * 가장 안쪽 객체 하나에서 필요한 칸만 담아 JSON 글로 준다. 같은 조각의 광고·추천 상품 객체는 이 두 칸이 없어 걸리지 않는다.
 * 조각 스크립트가 없거나 묶음 객체가 없으면 `null` 이다.
 */
export const CATALOG_STATE = `(() => { const r = ${fragmentScan(
  ["nCatalogId", "lowestPrice"],
  1,
  `found.push({
          nCatalogId: o.nCatalogId,
          productName: o.productName,
          lowestPrice: o.lowestPrice,
          deliveryFee: o.deliveryFee,
          lowestPriceWithFee: o.lowestPriceWithFee,
          differentialDeliveryFeeContent: o.differentialDeliveryFeeContent,
          categoryId: o.categoryId,
        });`,
)}; return r === null || r.length === 0 ? null : JSON.stringify(r[0]); })()`;

/** 상품 화면은 상태가 있는지(`present`)와 상품 칸(`value`)을 함께 준다. 상태는 있는데 상품이 없으면 없는 상품이다. */
export const PRODUCT_STATE = `(() => { try { const s = window.__PRELOADED_STATE__; if (!s || typeof s !== "object") return null; const v = ${walk("s", PRODUCT_STATE_PATH)}; return JSON.stringify({ present: true, value: v ?? null }); } catch { return null; } })()`;

export const EXPRESSIONS = new Set([
  READY_STATE,
  PAGE_PROBE,
  SEARCH_STATE,
  CATALOG_STATE,
  PRODUCT_STATE,
]);
