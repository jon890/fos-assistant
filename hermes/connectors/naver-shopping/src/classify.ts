/**
 * 화면 분류. 주소와 보이는 캡차 요소의 있고 없음만 본다. 캡차 그림을 읽거나 답을 넣는 코드는 두지 않는다.
 * 분류와 결과는 docs/features/naver-shopping.md 의 「화면 읽기」 가 갖는다.
 */
import type { ErrorCode } from "./errors.ts";

export type PageKind = "ok" | "login" | "captcha" | "device_check";

const LOGIN_HOST = "nid.naver.com";
/** 새 기기 확인 경로에 들어가는 낱말이다. */
const DEVICE_PATH = /device/i;
/** 네이버 보안 확인(캡차) 요소를 찾는 선택자다. 탭에서 부르는 고정 식이 쓴다. */
export const CAPTCHA_SELECTOR =
  '[id*="captcha" i], [class*="captcha" i], iframe[src*="captcha" i]';

/** 탭에서 읽은 `{href, captcha}` 로 분류한다. */
export function classifyPage(probe: {
  href?: unknown;
  captcha?: unknown;
}): PageKind {
  let url: URL | null = null;
  try {
    url = typeof probe.href === "string" ? new URL(probe.href) : null;
  } catch {
    url = null;
  }
  if (url?.hostname === LOGIN_HOST)
    return DEVICE_PATH.test(url.pathname) ? "device_check" : "login";
  if (probe.captcha === true) return "captcha";
  return "ok";
}

/** 네이버 화면인가. 이동 뒤 최종 주소가 다른 사이트면 읽지 않는다. */
export function naverHost(href: unknown) {
  try {
    const host =
      typeof href === "string" ? new URL(href).hostname.toLowerCase() : "";
    return host === "naver.com" || host.endsWith(".naver.com");
  } catch {
    return false;
  }
}

/** 사람이 해야 하는 분류의 오류 코드. 정상이면 `null` 이다. */
export function humanStepError(kind: PageKind): ErrorCode | null {
  if (kind === "login") return "NAVER_SHOPPING_LOGIN_REQUIRED";
  if (kind === "captcha") return "NAVER_SHOPPING_CAPTCHA";
  if (kind === "device_check") return "NAVER_SHOPPING_DEVICE_CHECK";
  return null;
}
