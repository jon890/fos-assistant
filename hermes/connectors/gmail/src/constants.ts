export const MINIMUM_BUN_VERSION = [1, 3, 14] as const;
export const PROXY_ENVIRONMENT_KEYS = [
  "HTTP_PROXY",
  "HTTPS_PROXY",
  "ALL_PROXY",
  "http_proxy",
  "https_proxy",
  "all_proxy",
] as const;
export const TOKEN_URL = "https://oauth2.googleapis.com/token";
export const API_BASE = "https://gmail.googleapis.com/gmail/v1/users/me";
export const RESPONSE_MAX_BYTES = 10 * 1024 * 1024;
export const BODY_MAX_CHARS = 20_000;
export const THREAD_BODY_MAX_CHARS = 5_000;
export const THREAD_MAX_MESSAGES = 20;
export const SEARCH_CONCURRENCY = 5;
export const HEADER_MAX_CHARS = 1_000;
export const ATTACHMENTS_MAX = 50;
export const FILENAME_MAX_CHARS = 255;
export const ID = /^[A-Za-z0-9_-]{1,64}$/;
export const ADDRESS =
  /^[A-Za-z0-9.!#$%&'*+/=?^_{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+$/;
export const CONTROL = /[\x00-\x1f\x7f\x85\u2028\u2029]/;
export const INVISIBLE = /[\p{Cf}\u3164\u115f\u1160\uffa0\u2800]/u;
export const ENCODED_WORD = /=\?[^?\s]*\?[bBqQ]\?/;
export const INVISIBLE_MARKS = new Set([
  "\u034f",
  "\u17b4",
  "\u17b5",
  "\u180b",
  "\u180c",
  "\u180d",
  "\u180f",
  ...Array.from({ length: 16 }, (_, index) =>
    String.fromCodePoint(0xfe00 + index),
  ),
  ...Array.from({ length: 240 }, (_, index) =>
    String.fromCodePoint(0xe0100 + index),
  ),
]);
export const BLOCKED = new Set(["TRASH", "SPAM"]);
export const SYSTEM_LABELS = new Set([
  "INBOX",
  "SENT",
  "DRAFT",
  "CHAT",
  "SPAM",
  "TRASH",
  "UNREAD",
  "STARRED",
  "IMPORTANT",
  "CATEGORY_PERSONAL",
  "CATEGORY_SOCIAL",
  "CATEGORY_PROMOTIONS",
  "CATEGORY_UPDATES",
  "CATEGORY_FORUMS",
]);
// Gmail users.labels Color API가 허용한 팔레트다. text/backgroundColor 모두 이 집합을 쓴다.
export const COLORS = new Set(
  "#000000 #434343 #666666 #999999 #cccccc #efefef #f3f3f3 #ffffff #fb4c2f #ffad47 #fad165 #16a766 #43d692 #4a86e8 #a479e2 #f691b3 #f6c5be #ffe6c7 #fef1d1 #b9e4d0 #c6f3de #c9daf8 #e4d7f5 #fcdee8 #efa093 #ffd6a2 #fce8b3 #89d3b2 #a0eac9 #a4c2f4 #d0bcf1 #fbc8d9 #e66550 #ffbc6b #fcda83 #44b984 #68dfa9 #6d9eeb #b694e8 #f7a7c0 #cc3a21 #eaa041 #f2c960 #149e60 #3dc789 #3c78d8 #8e63ce #e07798 #ac2b16 #cf8933 #d5ae49 #0b804b #2a9c68 #285bac #653e9b #b65775 #822111 #a46a21 #aa8831 #076239 #1a764d #1c4587 #41236d #83334c #464646 #e7e7e7 #0d3472 #b6cff5 #0d3b44 #98d7e4 #3d188e #e3d7ff #711a36 #fbd3e0 #8a1c0a #f2b2a8 #7a2e0b #ffc8af #7a4706 #ffdeb5 #594c05 #fbe983 #684e07 #fdedc1 #0b4f30 #b3efd3 #04502e #a2dcc1 #c2c2c2 #4986e7 #2da2bb #b99aff #994a64 #f691b2 #ff7537 #ffad46 #662e37 #ebdbde #cca6ac #094228 #42d692 #16a765".split(
    " ",
  ),
);
