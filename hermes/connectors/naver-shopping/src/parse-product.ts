import { CATALOG, FREE_DELIVERY, PRODUCT, SALE_STATUS } from "./fields.ts";
import { STORE_MAX, TITLE_MAX } from "./parse-search.ts";
import { ToolError } from "./errors.ts";
import { list, pick, text, won, type Json } from "./values.ts";

export const OPTION_GROUPS_MAX = 20;
export const OPTION_VALUES_MAX = 100;
const OPTION_LABEL_MAX = 100;

type OptionValue = {
  label: string;
  extra_krw: number | null;
  sold_out: boolean | null;
};

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
  // 옵션을 쓰지 않는 상품(실측)은 옵션 칸이 아예 없다. true 인데 칸을 못 찾으면 지어내지 않고 빈 목록과 `unknown` 으로 둔다.
  // 옵션 목록은 사람이 드롭다운을 눌러야 실려 처음 상태에 없다(2026-11-13 실측).
  if (pick(product, PRODUCT.optionUsable) === false)
    return { options: [], truncated: false, unknown: false };
  const groups = list(pick(product, PRODUCT.optionGroups)).slice(
    0,
    OPTION_GROUPS_MAX,
  );
  const combinations = list(pick(product, PRODUCT.combinations));
  let truncated = false;
  const result = groups.map((group, index) => {
    const nameKey = PRODUCT.combinationNames[index];
    const byLabel = new Map<string, { extras: number[]; stocks: number[] }>();
    for (const combination of combinations) {
      const label = nameKey
        ? text(pick(combination, [nameKey]), OPTION_LABEL_MAX)
        : null;
      if (label === null) continue;
      const seen = byLabel.get(label) ?? { extras: [], stocks: [] };
      const extra = won(pick(combination, PRODUCT.combinationPrice));
      const stock = won(pick(combination, PRODUCT.combinationStock));
      if (extra !== null) seen.extras.push(extra);
      if (stock !== null) seen.stocks.push(stock);
      byLabel.set(label, seen);
    }
    if (byLabel.size > OPTION_VALUES_MAX) truncated = true;
    const values: OptionValue[] = [...byLabel]
      .slice(0, OPTION_VALUES_MAX)
      .map(([label, seen]) => ({
        label,
        extra_krw:
          index === 0 && seen.extras.length ? Math.min(...seen.extras) : null,
        sold_out:
          index === 0 && seen.stocks.length
            ? seen.stocks.every((stock) => stock === 0)
            : null,
      }));
    return {
      name:
        text(pick(group, PRODUCT.groupName), OPTION_LABEL_MAX) ??
        `옵션 ${index + 1}`,
      values,
    };
  });
  if (list(pick(product, PRODUCT.optionGroups)).length > OPTION_GROUPS_MAX)
    truncated = true;
  const unknown =
    pick(product, PRODUCT.optionUsable) === true &&
    result.every((group) => group.values.length === 0);
  return { options: result, truncated, unknown };
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
  const { options: groups, truncated, unknown } = options(product);
  const free = pick(delivery, PRODUCT.feeType) === FREE_DELIVERY;
  return {
    kind: "store" as const,
    url,
    title,
    store: text(pick(product, PRODUCT.store), STORE_MAX),
    price_krw: discounted ?? salePrice,
    options: groups,
    ...(truncated ? { options_truncated: true } : {}),
    ...(unknown ? { options_unknown: true } : {}),
    shipping: {
      fee_krw: free ? 0 : (won(pick(delivery, PRODUCT.baseFee)) ?? 0),
      free_over_krw: free ? null : won(pick(delivery, PRODUCT.freeOver)),
      regional_extra: PRODUCT.regional.some(
        (key) => (won(pick(delivery, [key])) ?? 0) > 0,
      ),
    },
    naver_pay_buy: pick(product, PRODUCT.status) === SALE_STATUS,
  };
}

/**
 * 고정 식이 준 묶음 객체들에서 연 주소의 `/catalog/<번호>` 와 `nCatalogId` 가 같은 것을 고른다. 비교·추천 묶음이 앞서 실려도
 * 맞는 묶음을 찾고, 없으면 다른 상품의 값을 내지 않고 `catalog_id` 가 빠졌다고 알린다.
 */
export function selectCatalog(candidates: Json, url: string): Json {
  if (!Array.isArray(candidates) || candidates.length === 0)
    throw new MissingFields(["catalog"]);
  const urlId = /\/catalog\/([0-9]+)$/.exec(url)?.[1];
  const same = candidates.filter(
    (candidate) =>
      urlId !== undefined &&
      String(pick(candidate, CATALOG.catalogId) ?? "") === urlId,
  );
  if (same.length === 0) throw new MissingFields(["catalog_id"]);
  // 같은 번호의 값이 빈 사본이 앞서 실려도 이름과 최저가가 읽히는 묶음을 고른다. 모두 비었으면 첫 것을 넘겨 parse 가 빠진 칸을 알린다.
  return (
    same.find(
      (candidate) =>
        text(pick(candidate, CATALOG.title), TITLE_MAX) !== null &&
        won(pick(candidate, CATALOG.lowestPrice)) !== null,
    ) ?? same[0]
  );
}

/**
 * 가격비교 묶음(주소 번호와 같은 묶음은 `selectCatalog` 가 이미 골랐다). 이름, 최저가, 배송비만 읽는다. 판매처별 가격 목록은 읽는 칸을 가려내지 못해 지어내지 않고 빈 `sellers` 에
 * `offers_unknown: true` 를 실어, 담당이 판매처별 가격을 사용자의 「내 브라우저」 에서 확인하게 한다.
 */
export function parseCatalog(state: Json, url: string) {
  if (state === null || typeof state !== "object")
    throw new MissingFields(["catalog"]);
  const title = text(pick(state, CATALOG.title), TITLE_MAX);
  const lowest = won(pick(state, CATALOG.lowestPrice));
  if (title === null || lowest === null) {
    throw new MissingFields([
      ...(title === null ? ["title"] : []),
      ...(lowest === null ? ["lowest_price"] : []),
    ]);
  }
  return {
    kind: "catalog" as const,
    url,
    title,
    lowest_price_krw: lowest,
    delivery_fee_krw: won(pick(state, CATALOG.deliveryFee)),
    lowest_price_with_fee_krw: won(pick(state, CATALOG.lowestPriceWithFee)),
    regional_extra: text(pick(state, CATALOG.regionalNote), 200) !== null,
    sellers: [] as never[],
    offers_unknown: true as const,
  };
}

/** 고른 옵션 조합. `extra_krw` 는 그 조합의 추가 금액, `sold_out` 은 재고가 0 인지(재고 칸이 없으면 거짓)다. */
export type ChosenOption = {
  /** 화면의 옵션 값 글(옵션 단계 순서). 채우기가 층에서 이 글로 고른다. */
  labels: string[];
  /** 카드에 보일 `{name, value}`. 이름은 옵션 묶음 이름이다. */
  shown: { name: string; value: string }[];
  extra_krw: number;
  sold_out: boolean;
};

/**
 * 옵션 글을 상품 상태의 조합 하나로 맞춘다(design.md 「도구(PR3c)」). 옵션 없는 상품은 빈 글만 받는다.
 * 맞는 조합이 없거나 둘 이상이면 `NAVER_SHOPPING_OPTION_MISMATCH`, 옵션 목록을 읽지 못했으면 `NAVER_SHOPPING_OPTIONS_UNKNOWN` 이다.
 * 조합의 추가 금액 칸이 비면 지어내지 않고 바뀐 화면으로 본다. 단계는 `combinationNames` 의 수(셋)까지다.
 */
export function chooseOption(
  value: Json,
  product: NonNullable<ReturnType<typeof parseStoreProduct>>,
  wanted: string[],
): ChosenOption {
  if (product.options_unknown) throw new ToolError("NAVER_SHOPPING_OPTIONS_UNKNOWN");
  const labels = wanted.map((label) => text(label, OPTION_LABEL_MAX));
  if (labels.some((label) => label === null))
    throw new ToolError("NAVER_SHOPPING_OPTION_MISMATCH");
  const groups = product.options;
  if (groups.length === 0) {
    if (labels.length) throw new ToolError("NAVER_SHOPPING_OPTION_MISMATCH");
    return { labels: [], shown: [], extra_krw: 0, sold_out: false };
  }
  if (
    groups.length > PRODUCT.combinationNames.length ||
    labels.length !== groups.length
  )
    throw new ToolError("NAVER_SHOPPING_OPTION_MISMATCH");
  const matches = list(pick(value, PRODUCT.combinations)).filter(
    (combination) =>
      labels.every(
        (label, index) =>
          text(
            pick(combination, [PRODUCT.combinationNames[index]!]),
            OPTION_LABEL_MAX,
          ) === label,
      ),
  );
  if (matches.length !== 1)
    throw new ToolError("NAVER_SHOPPING_OPTION_MISMATCH");
  // 조합의 추가 금액은 음수(더 싼 옵션)일 수 있다.
  const rawExtra = pick(matches[0], PRODUCT.combinationPrice);
  const extra =
    typeof rawExtra === "number" && Number.isSafeInteger(rawExtra)
      ? rawExtra
      : null;
  if (extra === null) throw new MissingFields(["option_price"]);
  const stock = won(pick(matches[0], PRODUCT.combinationStock));
  return {
    labels: labels as string[],
    shown: groups.map((group, index) => ({
      name: group.name,
      value: labels[index]!,
    })),
    extra_krw: extra,
    sold_out: stock === 0,
  };
}
