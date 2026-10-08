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
/** 스펙의 `symbols` 형식 `^[A-Za-z0-9.,\-]+$` 에서 기호 하나를 떼고 길이 상한을 둔 것이다. */
export const SYMBOL = /^[A-Za-z0-9.-]{1,12}$/;
export const NAME_MAX_CHARS = 100;
export const QUOTE_SYMBOLS_MAX = 20;
