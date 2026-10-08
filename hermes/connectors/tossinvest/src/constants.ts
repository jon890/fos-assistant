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
/** `expires_in` 이 없거나 여유보다 짧아도 이만큼은 받은 토큰을 쓴다. 겹친 호출이 서로의 토큰을 무효로 만들지 않게 한다. */
export const TOKEN_MIN_TTL_MS = 5_000;
/** 서비스가 준 글 값 하나를 결과에 담을 때의 길이 상한(코드 포인트)이다. 종목 이름은 `NAME_MAX_CHARS` 다. */
export const VALUE_MAX_CHARS = 64;
/** 스펙의 `symbols` 형식 `^[A-Za-z0-9.,\-]+$` 에서 기호 하나를 떼고 길이 상한을 둔 것이다. */
export const SYMBOL = /^[A-Za-z0-9.-]{1,12}$/;
export const NAME_MAX_CHARS = 100;
export const QUOTE_SYMBOLS_MAX = 20;
/** connector.json 의 `account` 칸 형식과 같다. */
export const ACCOUNT_SEQ = /^[0-9]{1,10}$/;
/** `list_orders` 가 결과에 담는 주문 수의 상한이고, 끝난 주문 한 쪽의 `limit` 이다. */
export const ORDERS_MAX = 100;
/** `from` 과 `to` 를 모두 포함해 센 기간의 상한이다. */
export const ORDER_PERIOD_MAX_DAYS = 366;
/** 파일 출력이 끝난 주문을 100건씩 도는 쪽 수의 상한이다. 넘으면 일부만 쓰지 않고 거절한다. */
export const ORDER_FILE_MAX_PAGES = 20;
/** 파일 출력이 쪽 사이에 쉬는 시간이다. 호출 한도가 `ORDER_HISTORY` 그룹 초당 5회다. */
export const ORDER_FILE_PAGE_PAUSE_MS = 250;
/** 파일 출력이 429 를 받고 같은 쪽을 한 번 다시 부르기 전에 쉬는 시간이다. */
export const ORDER_FILE_RATE_LIMIT_PAUSE_MS = 1_000;
/** 출력 디렉터리의 자기 파일을 이 시간이 지나면 지운다. */
export const ORDER_FILE_TTL_MS = 24 * 60 * 60 * 1000;
