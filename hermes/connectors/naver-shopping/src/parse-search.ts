import { SEARCH_ITEM } from "./fields.ts";
import { classifyUrl, type UrlKind } from "./urls.ts";
import { list, pick, text, won, yes, type Json } from "./values.ts";

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
  naver_pay: "yes" | "unknown";
  ad: boolean;
};

/**
 * 검색 화면의 상품 목록을 결과로 바꾼다. 목록이 배열이 아니면 `null` 이고 부르는 쪽이 `PAGE_CHANGED` 로 끝낸다.
 * 이름, 가격, 주소 가운데 하나라도 읽지 못한 항목과 주소가 거절인 항목은 뺀다. 차례는 화면 그대로이고 40개까지다.
 */
export function parseSearch(state: Json): SearchProduct[] | null {
  if (!Array.isArray(state)) return null;
  const products: SearchProduct[] = [];
  for (const entry of state) {
    if (products.length >= SEARCH_MAX) break;
    const item = pick(entry, [SEARCH_ITEM.wrapper]) ?? entry;
    const title = text(pick(item, SEARCH_ITEM.title), TITLE_MAX);
    const price = won(pick(item, SEARCH_ITEM.price));
    const url = classifyUrl(pick(item, SEARCH_ITEM.url));
    if (title === null || price === null || url === null) continue;
    products.push({
      rank: products.length + 1,
      title,
      price_krw: price,
      store: text(pick(item, SEARCH_ITEM.store), STORE_MAX),
      url: url.url,
      kind: url.kind,
      naver_pay: yes(pick(item, SEARCH_ITEM.naverPay)) ? "yes" : "unknown",
      ad: pick(entry, SEARCH_ITEM.ad) !== undefined || pick(item, SEARCH_ITEM.ad) !== undefined,
    });
  }
  return products;
}

/** 목록은 있는데 읽은 항목이 하나도 없으면 칸 위치가 바뀐 것으로 본다. 빈 목록은 정상이다. */
export function searchLooksChanged(state: Json, products: SearchProduct[]) {
  return list(state).length > 0 && products.length === 0;
}
