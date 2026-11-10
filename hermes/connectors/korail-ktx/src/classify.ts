/**
 * 화면 분류. 주소, 요소의 있고 없음, 정해 둔 안내 글만 본다. 캡차 그림을 읽거나 답을 넣는 코드,
 * 탐지 안내 층의 「확인」 을 누르는 코드는 두지 않는다. 순서와 결과는 설계의 「화면 분류」 가 갖는다.
 */
import type { ErrorCode } from "./errors.ts";
import { BOT_CHECK_CODE, BOT_CHECK_WORD, LOGIN_PATH } from "./fields.ts";

export type Probe = {
  href?: unknown;
  captcha?: unknown;
  /** 보이는 「안내 메시지」 층의 글. 없으면 `null`. */
  notice?: unknown;
  /** 이 탭에 뜬 JS 대화상자의 글. 탭 쪽이 채운다. */
  dialog?: unknown;
};

export type PageKind =
  | { kind: "ok" }
  | { kind: "login" | "bot_check" | "captcha" }
  | { kind: "site_alert"; message: string };

const clip = (value: string) =>
  [...value.replace(/\s+/g, " ").trim()].slice(0, 200).join("");

export function classifyPage(probe: Probe): PageKind {
  let path = "";
  try {
    path = typeof probe.href === "string" ? new URL(probe.href).pathname : "";
  } catch {
    path = "";
  }
  if (path.startsWith(LOGIN_PATH)) return { kind: "login" };
  const notice = typeof probe.notice === "string" ? probe.notice : "";
  if (notice.includes(BOT_CHECK_WORD) && BOT_CHECK_CODE.test(notice))
    return { kind: "bot_check" };
  if (probe.captcha === true) return { kind: "captcha" };
  const dialog = typeof probe.dialog === "string" ? probe.dialog : "";
  if (dialog || notice)
    return { kind: "site_alert", message: clip(dialog || notice) };
  return { kind: "ok" };
}

/** 분류를 오류로 바꾼다. 정상이면 `null` 이다. 사람이 해야 하는 셋은 `human` 이 참이고 탭을 남긴다. */
export function pageError(
  page: PageKind,
): { code: ErrorCode; human: boolean; message?: string } | null {
  if (page.kind === "login")
    return { code: "KORAIL_LOGIN_REQUIRED", human: true };
  if (page.kind === "bot_check")
    return { code: "KORAIL_BOT_CHECK", human: true };
  if (page.kind === "captcha") return { code: "KORAIL_CAPTCHA", human: true };
  if (page.kind === "site_alert")
    return { code: "KORAIL_REJECTED", human: false, message: page.message };
  return null;
}
