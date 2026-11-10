/**
 * 탭에서 `Runtime.evaluate` 로 부르는 식은 이 파일의 상수뿐이다. 모델이나 인자가 식을 정하지 못한다.
 * 누르기, 입력, 이동, 내리기 식은 두지 않는다. 테스트가 탭에 보낸 식이 모두 이 집합 안인지 본다.
 */
import { CAPTCHA_SELECTOR } from "./classify.ts";
import {
  CATALOG_STATE_PATH,
  PRODUCT_STATE_PATH,
  SEARCH_LIST_PATH,
} from "./fields.ts";

/** 경로를 따라 내려가는 식 조각. 경로는 이 저장소의 상수라 글자를 그대로 싣는다. */
function walk(root: string, path: string[]) {
  return path.reduce(
    (expression, key) => `${expression}?.[${JSON.stringify(key)}]`,
    root,
  );
}

/** `__NEXT_DATA__` 의 경로 아래를 JSON 글로 꺼낸다. 없으면 `null` 이다. */
function nextData(path: string[]) {
  return `(() => { try { const s = JSON.parse(document.getElementById("__NEXT_DATA__")?.textContent ?? "null"); const v = ${walk("s", path)}; return v === undefined ? null : JSON.stringify(v); } catch { return null; } })()`;
}

export const READY_STATE = "document.readyState";

export const PAGE_PROBE = `(() => ({ href: location.href, captcha: document.querySelector(${JSON.stringify(CAPTCHA_SELECTOR)}) !== null }))()`;

export const SEARCH_STATE = nextData(SEARCH_LIST_PATH);

export const CATALOG_STATE = nextData(CATALOG_STATE_PATH);

/** 상품 화면은 상태가 있는지(`present`)와 상품 칸(`value`)을 함께 준다. 상태는 있는데 상품이 없으면 없는 상품이다. */
export const PRODUCT_STATE = `(() => { try { const s = window.__PRELOADED_STATE__; if (!s || typeof s !== "object") return null; const v = ${walk("s", PRODUCT_STATE_PATH)}; return JSON.stringify({ present: true, value: v ?? null }); } catch { return null; } })()`;

export const EXPRESSIONS = new Set([
  READY_STATE,
  PAGE_PROBE,
  SEARCH_STATE,
  CATALOG_STATE,
  PRODUCT_STATE,
]);
