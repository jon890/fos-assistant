import { OPEN_OPTION_LISTS, PRODUCT_STATE } from "./expressions.ts";
import { ToolError } from "./errors.ts";
import { MissingFields, parseStoreProduct } from "./parse-product.ts";
import type { Page } from "./tab.ts";
import { parseJson, type Json } from "./values.ts";

/** 옵션 드롭다운을 연 뒤 상태를 다시 묻는 간격. 기다리는 시간은 탭 설정(`optionWaitMs`)이다. */
const OPTION_POLL_MS = 250;

/** 상품 식이 준 글에서 상품 칸을 꺼낸다. 상태가 없으면 바뀐 화면, 상품이 없으면 `null` 이다. */
function productValue(raw: unknown): Json {
  const wrapped = parseJson(raw) as
    | { present?: unknown; value?: unknown }
    | undefined;
  if (wrapped?.present !== true) throw new MissingFields(["preloaded_state"]);
  return wrapped.value;
}

/** 상품 상태 칸과 그것을 바꾼 결과. 주문서 준비와 채우기가 옵션 조합을 다시 볼 때 상태 칸을 쓴다. */
export type StoreRead = {
  value: Json;
  product: NonNullable<ReturnType<typeof parseStoreProduct>>;
};

/**
 * 스마트스토어·브랜드스토어 상품을 읽는다. 상품이 없으면 `NAVER_SHOPPING_NOT_FOUND` 다.
 * 옵션이 있는데(`optionUsable`) 상태에 옵션이 없고 탭이 열려 있으면 옵션 드롭다운을 한 번만 열고(`OPEN_OPTION_LISTS`)
 * 상태를 다시 묻는다(design.md 「옵션 드롭다운 열기(PR3c)」). 그래도 없으면 `options_unknown` 이 붙은 그대로다.
 * 값을 고르거나 다른 단추를 누르지 않고, 닫는 식도 부르지 않는다. 읽기 탭은 정상으로 끝나면 탭째 닫힌다.
 */
export async function readStore(
  raw: unknown,
  url: string,
  page?: Page,
): Promise<StoreRead> {
  let value = productValue(raw);
  let product = parseStoreProduct(value, url);
  if (product === null) throw new ToolError("NAVER_SHOPPING_NOT_FOUND");
  if (!product.options_unknown || !page) return { value, product };
  if ((await page.evaluate(OPEN_OPTION_LISTS)) !== true)
    return { value, product };
  const until = Date.now() + Math.min(page.optionWaitMs, page.remaining());
  while (true) {
    const again = productValue(await page.evaluate(PRODUCT_STATE));
    const parsed = parseStoreProduct(again, url);
    if (parsed === null) throw new ToolError("NAVER_SHOPPING_NOT_FOUND");
    value = again;
    product = parsed;
    if (!product.options_unknown || Date.now() >= until)
      return { value, product };
    await Bun.sleep(Math.min(OPTION_POLL_MS, Math.max(1, until - Date.now())));
  }
}
