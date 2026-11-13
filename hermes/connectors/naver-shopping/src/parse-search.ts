import { AD_CARD_TYPE, SEARCH_ITEM } from "./fields.ts";
import { classifyUrl, type UrlKind } from "./urls.ts";
import { list, pick, text, won, type Json } from "./values.ts";

export const SEARCH_MAX = 40;
export const TITLE_MAX = 200;
export const STORE_MAX = 50;

export type SearchProduct = {
  rank: number;
  title: string;
  price_krw: number;
  store: string | null;
  url: string;
  kind: UrlKind;
  /** 검색 화면에서 네이버페이 여부를 가리는 칸은 확인하지 못해 항상 `unknown` 이다. 지어내지 않는다. */
  naver_pay: "yes" | "unknown";
  ad: boolean;
};

/** 할인가가 있으면 할인가, 없으면 정가. 0 원은 읽은 값으로 치지 않고 다음 후보를 본다. */
function price(item: Json): number | null {
  for (const key of SEARCH_ITEM.price) {
    const value = won(pick(item, [key]));
    if (value !== null && value > 0) return value;
  }
  return null;
}

/**
 * 고정 식이 준 상품 배열을 결과로 바꾼다. 배열이 아니면 `null` 이고 부르는 쪽이 `PAGE_CHANGED` 로 끝낸다.
 * 이름, 가격, 주소 가운데 하나라도 읽지 못한 항목과 주소가 거절인 항목은 뺀다.
 * 같은 `nvMid` 는 화면에서 처음 나온 한 번만 내고, 차례는 화면 그대로이고 40개까지다.
 */
export function parseSearch(state: Json): SearchProduct[] | null {
  if (!Array.isArray(state)) return null;
  const products: SearchProduct[] = [];
  const seen = new Set<string>();
  for (const item of state) {
    if (products.length >= SEARCH_MAX) break;
    const title = text(pick(item, SEARCH_ITEM.title), TITLE_MAX);
    const priceKrw = price(item);
    const url = classifyUrl(pick(item, SEARCH_ITEM.url));
    if (title === null || priceKrw === null || url === null) continue;
    const id = pick(item, SEARCH_ITEM.productId);
    if (typeof id === "string" || typeof id === "number") {
      if (seen.has(String(id))) continue;
      seen.add(String(id));
    }
    products.push({
      rank: products.length + 1,
      title,
      price_krw: priceKrw,
      store: text(pick(item, SEARCH_ITEM.store), STORE_MAX),
      url: url.url,
      kind: url.kind,
      naver_pay: "unknown",
      ad:
        pick(item, SEARCH_ITEM.adId) !== undefined ||
        pick(item, SEARCH_ITEM.cardType) === AD_CARD_TYPE,
    });
  }
  return products;
}

/** 목록은 있는데 읽은 항목이 하나도 없으면 칸 위치가 바뀐 것으로 본다. 빈 목록은 정상이다. */
export function searchLooksChanged(state: Json, products: SearchProduct[]) {
  return list(state).length > 0 && products.length === 0;
}
