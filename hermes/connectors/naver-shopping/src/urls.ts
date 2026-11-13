/**
 * 주소 분류 표다. 탭을 여는 것은 `store` 와 `catalog` 뿐이라, 모델이 준 주소로 로그인된 브라우저가 아무 사이트나 열지 않는다.
 * 갈래와 까닭은 docs/features/naver-shopping.md 의 「주소 분류」 가 갖는다.
 */
export type UrlKind = "store" | "catalog" | "external";

export const URL_MAX = 2_048;
export const STORE_HOSTS = new Set([
  "smartstore.naver.com",
  "m.smartstore.naver.com",
  "brand.naver.com",
  "m.brand.naver.com",
]);
const CATALOG_HOSTS = new Set([
  "search.shopping.naver.com",
  "m.search.shopping.naver.com",
]);
const STORE_PATH = /^\/[A-Za-z0-9_-]{1,100}\/products\/[0-9]{1,20}$/;
const CATALOG_PATH = /^\/catalog\/[0-9]{1,20}$/;

/** 거절이면 `null` 이다. 그 밖에는 갈래와 정리한 주소(조회 글과 조각을 뗀 상품 주소, 바깥 주소는 받은 그대로)를 돌려준다. */
export function classifyUrl(
  raw: unknown,
): { kind: UrlKind; url: string } | null {
  if (
    typeof raw !== "string" ||
    raw.length === 0 ||
    raw.length > URL_MAX ||
    /\s/.test(raw)
  )
    return null;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== "https:" || url.username || url.password || url.port)
    return null;
  const host = url.hostname.toLowerCase();
  const path = url.pathname.replace(/\/+$/, "");
  if (STORE_HOSTS.has(host) && STORE_PATH.test(path))
    return { kind: "store", url: `https://${host}${path}` };
  if (CATALOG_HOSTS.has(host) && CATALOG_PATH.test(path))
    return { kind: "catalog", url: `https://${host}${path}` };
  return { kind: "external", url: url.href };
}

/** 검색 주소. 검색어 하나로 첫 페이지만 연다. 다음 페이지를 고르는 인자를 싣지 않는다. */
export function searchUrl(query: string) {
  return `https://search.shopping.naver.com/ns/search?query=${encodeURIComponent(query)}`;
}
