import { CATALOG, PRODUCT, SALE_STATUS } from "./fields.ts";
import { STORE_MAX, TITLE_MAX } from "./parse-search.ts";
import { classifyUrl } from "./urls.ts";
import { list, pick, text, won, yes, type Json } from "./values.ts";

export const OPTION_GROUPS_MAX = 20;
export const OPTION_VALUES_MAX = 100;
const OPTION_LABEL_MAX = 100;

type OptionValue = { label: string; extra_krw: number | null; sold_out: boolean | null };

/** 읽지 못한 필수 칸의 이름을 담아 던진다. 부르는 쪽이 진단 줄에 싣고 `PAGE_CHANGED` 로 끝낸다. */
export class MissingFields extends Error {
  constructor(readonly fields: string[]) {
    super("missing_fields");
  }
}

/**
 * 조합형 옵션을 묶음별 값 목록으로 바꾼다. 첫 묶음은 값마다 추가 금액(그 값 조합의 가장 작은 추가 금액)과 품절(모든 조합의
 * 재고가 0)을 싣는다. 둘째 묶음부터는 값의 이름만 싣는다. 고른 조합의 금액과 재고는 주문서 준비가 다시 읽는다.
 */
function options(product: Json) {
  const groups = list(pick(product, PRODUCT.optionGroups)).slice(0, OPTION_GROUPS_MAX);
  const combinations = list(pick(product, PRODUCT.combinations));
  let truncated = false;
  const result = groups.map((group, index) => {
    const nameKey = PRODUCT.combinationNames[index];
    const byLabel = new Map<string, { extras: number[]; stocks: number[] }>();
    for (const combination of combinations) {
      const label = nameKey ? text(pick(combination, [nameKey]), OPTION_LABEL_MAX) : null;
      if (label === null) continue;
      const seen = byLabel.get(label) ?? { extras: [], stocks: [] };
      const extra = won(pick(combination, PRODUCT.combinationPrice));
      const stock = won(pick(combination, PRODUCT.combinationStock));
      if (extra !== null) seen.extras.push(extra);
      if (stock !== null) seen.stocks.push(stock);
      byLabel.set(label, seen);
    }
    if (byLabel.size > OPTION_VALUES_MAX) truncated = true;
    const values: OptionValue[] = [...byLabel].slice(0, OPTION_VALUES_MAX).map(([label, seen]) => ({
      label,
      extra_krw: index === 0 && seen.extras.length ? Math.min(...seen.extras) : null,
      sold_out: index === 0 && seen.stocks.length ? seen.stocks.every((stock) => stock === 0) : null,
    }));
    return { name: text(pick(group, PRODUCT.groupName), OPTION_LABEL_MAX) ?? `옵션 ${index + 1}`, values };
  });
  if (list(pick(product, PRODUCT.optionGroups)).length > OPTION_GROUPS_MAX) truncated = true;
  return { options: result, truncated };
}

/** 스마트스토어·브랜드스토어 상품. 상태는 있는데 상품이 없으면 `null` 이고 부르는 쪽이 `NOT_FOUND` 로 끝낸다. */
export function parseStoreProduct(product: Json, url: string) {
  if (product === null || product === undefined) return null;
  const title = text(pick(product, PRODUCT.title), TITLE_MAX);
  const salePrice = won(pick(product, PRODUCT.salePrice));
  const discounted = won(pick(product, PRODUCT.discountedPrice));
  const delivery = pick(product, PRODUCT.delivery);
  const missing = [
    ...(title === null ? ["title"] : []),
    ...(salePrice === null && discounted === null ? ["price"] : []),
    ...(delivery === undefined ? ["delivery"] : []),
  ];
  if (missing.length) throw new MissingFields(missing);
  const { options: groups, truncated } = options(product);
  return {
    kind: "store" as const,
    url,
    title,
    store: text(pick(product, PRODUCT.store), STORE_MAX),
    price_krw: discounted ?? salePrice,
    options: groups,
    ...(truncated ? { options_truncated: true } : {}),
    shipping: {
      fee_krw: won(pick(delivery, PRODUCT.baseFee)) ?? 0,
      free_over_krw: won(pick(delivery, PRODUCT.freeOver)),
      regional_extra: pick(delivery, PRODUCT.regional) !== undefined,
    },
    naver_pay_buy: pick(product, PRODUCT.status) === SALE_STATUS,
  };
}

/** 가격비교 묶음. 네이버페이로 바로 사는 판매처만 싣는다. */
export function parseCatalog(state: Json, url: string) {
  if (state === null || typeof state !== "object") throw new MissingFields(["catalog"]);
  const title = text(pick(state, CATALOG.title), TITLE_MAX);
  const sellers = pick(state, CATALOG.sellers);
  if (title === null || !Array.isArray(sellers)) {
    throw new MissingFields([...(title === null ? ["title"] : []), ...(Array.isArray(sellers) ? [] : ["sellers"])]);
  }
  return {
    kind: "catalog" as const,
    url,
    title,
    sellers: sellers.flatMap((seller) => {
      const target = classifyUrl(pick(seller, CATALOG.url));
      const price = won(pick(seller, CATALOG.price));
      if (!yes(pick(seller, CATALOG.naverPay)) || target?.kind !== "store" || price === null) return [];
      return [{
        store: text(pick(seller, CATALOG.store), STORE_MAX),
        price_krw: price,
        shipping_fee_krw: won(pick(seller, CATALOG.shippingFee)),
        url: target.url,
      }];
    }).slice(0, OPTION_VALUES_MAX),
  };
}
