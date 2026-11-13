import { logDiagnostic, ToolError } from "./errors.ts";
import { CATALOG_STATE, PRODUCT_STATE, SEARCH_STATE } from "./expressions.ts";
import {
  MissingFields,
  parseCatalog,
  selectCatalog,
} from "./parse-product.ts";
import { readStore } from "./product-read.ts";
import { parseSearch, searchLooksChanged } from "./parse-search.ts";
import { browserUrl, type Env } from "./session.ts";
import { readInTab, type Page, type TabOptions } from "./tab.ts";
import { classifyUrl, searchUrl } from "./urls.ts";
import { parseJson } from "./values.ts";

export const QUERY_MAX = 100;

export type ToolDeps = { read: typeof readInTab; tab?: TabOptions };
const DEFAULT_DEPS: ToolDeps = { read: readInTab };

/** 검색어는 한 줄의 1자에서 100자다. 제어 문자를 받지 않는다. */
function checkQuery(query: string) {
  const trimmed = query.trim();
  if (
    !trimmed ||
    [...trimmed].length > QUERY_MAX ||
    /[\u0000-\u001f\u007f]/.test(trimmed)
  )
    throw new ToolError("NAVER_SHOPPING_INVALID_INPUT");
  return trimmed;
}

/** 검색어 하나로 첫 페이지 한 번만 읽는다. 다음 페이지와 내리기는 하지 않는다. */
export async function searchProducts(
  env: Env,
  query: string,
  deps: ToolDeps = DEFAULT_DEPS,
) {
  const cdpUrl = browserUrl(env);
  const checked = checkQuery(query);
  // 읽기는 탭 안에서 한다. 칸이 없으면 탭을 남긴 예상 밖 화면 넘기기가 된다.
  const products = await deps.read(
    cdpUrl,
    searchUrl(checked),
    SEARCH_STATE,
    (raw) => {
      const state = parseJson(raw);
      const found = parseSearch(state);
      if (found === null || searchLooksChanged(state, found)) {
        logDiagnostic(
          "search_products",
          "parse",
          found === null ? ["products"] : ["item"],
        );
        throw new ToolError("NAVER_SHOPPING_PAGE_CHANGED");
      }
      return found;
    },
    deps.tab,
  );
  return { query: checked, count: products.length, products };
}

/**
 * 주소 분류 표가 `store` 나 `catalog` 로 본 주소만 탭을 연다. `external` 은 탭 없이 링크만 돌려주고, 거절은 입력 오류다.
 */
export async function productDetail(
  env: Env,
  rawUrl: string,
  deps: ToolDeps = DEFAULT_DEPS,
) {
  const target = classifyUrl(rawUrl);
  if (target === null) throw new ToolError("NAVER_SHOPPING_INVALID_INPUT");
  if (target.kind === "external")
    return { kind: "external" as const, url: target.url, buyable: false };
  const cdpUrl = browserUrl(env);
  const tool = "get_product_detail";
  const read = async (raw: unknown, page?: Page) => {
    try {
      if (target.kind === "catalog") {
        const state = parseJson(raw);
        if (state === undefined || state === null)
          throw new MissingFields(["catalog"]);
        return parseCatalog(selectCatalog(state, target.url), target.url);
      }
      return (await readStore(raw, target.url, page)).product;
    } catch (error) {
      if (!(error instanceof MissingFields)) throw error;
      logDiagnostic(tool, `parse.${target.kind}`, error.fields);
      throw new ToolError("NAVER_SHOPPING_PAGE_CHANGED");
    }
  };
  // 읽기는 탭 안에서 한다. 칸이 없으면 탭을 남긴 예상 밖 화면 넘기기가 되고, 없는 상품은 탭을 닫는 오류다.
  return deps.read(
    cdpUrl,
    target.url,
    target.kind === "store" ? PRODUCT_STATE : CATALOG_STATE,
    read,
    deps.tab,
  );
}
