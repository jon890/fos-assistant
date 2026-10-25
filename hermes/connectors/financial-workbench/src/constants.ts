export const MINIMUM_BUN_VERSION = [1, 3, 14] as const;
export const PROXY_ENVIRONMENT_KEYS = [
  "HTTP_PROXY",
  "HTTPS_PROXY",
  "ALL_PROXY",
  "http_proxy",
  "https_proxy",
  "all_proxy",
] as const;
export const TOKEN_ENV = "FINANCIAL_WORKBENCH_TOKEN";
export const BASE_URL_ENV = "FINANCIAL_WORKBENCH_BASE_URL";
// 워크벤치 발급 형식이다. `fwb_` 뒤에 32바이트를 base64url 로 쓴 43자가 붙는다.
export const TOKEN = /^fwb_[A-Za-z0-9_-]{43}$/;
export const UUID =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
export const TYPE_KEY = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
export const TICKER = /^[A-Za-z0-9][A-Za-z0-9.-]{0,31}$/;
// 워크벤치가 받는 시장 값이다. 대소문자는 워크벤치가 무시한다.
export const MARKETS = new Set([
  "KR",
  "US",
  "KOSPI",
  "KOSDAQ",
  "NASDAQ",
  "NYSE",
  "AMEX",
  "KR_ETC",
  "US_ETC",
  "KRX_KOSPI",
  "KRX_KOSDAQ",
  "OTHER",
]);
export const LEVELS = new Set(["KNOWN", "UNSURE", "LEARNING"]);
export const QUERY_MAX_CHARS = 100;
export const PAGE_SIZE_MAX = 50;
// 워크벤치가 받는 요청 본문의 상한(64KiB)이다. 넘으면 부르지 않는다.
export const REQUEST_MAX_BYTES = 64 * 1024;
export const RESPONSE_MAX_BYTES = 4 * 1024 * 1024;
// 워크벤치는 상세의 속성 글을 자르지 않는다. 한 칸이 모델 입력을 다 차지하지 않게 커넥터가 자른다.
export const TEXT_MAX_CHARS = 20_000;
// 대시보드가 확인 도구를 기다리는 시간(10초)보다 짧게 둔다.
export const TIMEOUT_MS = 8_000;
