export const MINIMUM_BUN_VERSION = [1, 3, 14] as const;
export const PROXY_ENVIRONMENT_KEYS = [
  "HTTP_PROXY",
  "HTTPS_PROXY",
  "ALL_PROXY",
  "http_proxy",
  "https_proxy",
  "all_proxy",
] as const;
export const API_BASE = "https://openapi.tossinvest.com";
/** 대시보드가 확인 도구를 10초 기다리고 그 안에 토큰과 계좌 목록 두 요청이 든다. */
export const REQUEST_TIMEOUT_MS = 4_000;
export const RESPONSE_MAX_BYTES = 1024 * 1024;
/** `expires_in` 에서 이만큼을 뺀 시각까지만 받은 토큰을 쓴다. */
export const TOKEN_MARGIN_MS = 60_000;
/** 여유보다 짧은 양의 수명은 이 값과 절반 중 긴 동안 쓰되 공식 만료를 넘지 않는다. */
export const TOKEN_MIN_TTL_MS = 5_000;
/** 서비스가 준 글 값 하나를 결과에 담을 때의 길이 상한(코드 포인트)이다. 종목 이름은 `NAME_MAX_CHARS` 다. */
export const VALUE_MAX_CHARS = 64;
/** 여러 symbol의 공식 pattern에서 쉼표를 제외한 단일 symbol이다. */
export const SYMBOL = /^[A-Za-z0-9.-]+$/;
export const NAME_MAX_CHARS = 100;
export const QUOTE_SYMBOLS_MAX = 200;
/** connector.json 의 `account` 칸 형식과 같다. */
export const ACCOUNT_SEQ = /^[1-9][0-9]{0,18}$/;
export const validAccountSeq = (seq: string) => ACCOUNT_SEQ.test(seq) && BigInt(seq) <= 9223372036854775807n;
/** 끝난 주문 한 페이지의 최대 limit이다. OPEN의 전량을 자르지 않는다. */
export const ORDERS_MAX = 100;
