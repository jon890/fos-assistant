/**
 * 이 기기의 웹 푸시 상태를 정하는 순수 함수다(docs/features/attention/spec.md 의 REQ1-REQ3).
 *
 * <p>브라우저 API 를 읽지 않는다. `push.ts` 가 읽은 값을 받아 판정만 하므로 node 테스트가 표로 본다. node 테스트가
 * 이 파일을 바로 읽으므로 다른 모듈을 import 하지 않는다.
 */

/** 「알림」 화면이 이 기기에 보이는 상태다. */
export type PushScreenState =
  /** 서버에 VAPID 키가 없거나 푸시 경로가 아직 없다 */
  | "server-off"
  /** iOS 16.4 미만이라 웹 푸시를 받을 수 없다 */
  | "ios-old"
  /** 아이폰과 iPad 의 브라우저 탭이다. 홈 화면 앱으로 열어야 받는다 */
  | "ios-install"
  /** 브라우저가 웹 푸시를 지원하지 않는다 */
  | "unsupported"
  /** 사용자가 이 사이트의 알림을 막았다 */
  | "denied"
  /** 이 기기가 서버 목록에 있다 */
  | "registered"
  /** 「이 기기에서 알림 받기」 단추를 보인다 */
  | "ready";

export type PushScreenInput = {
  serverEnabled: boolean;
  supported: boolean;
  permission: NotificationPermission | "unsupported";
  ios: IosInfo;
  standalone: boolean;
  /** 이 브라우저 구독의 주소 해시가 서버 기기 목록에 있다 */
  registered: boolean;
};

export type IosInfo = {
  ios: boolean;
  /** `[주 버전, 부 버전]`. 읽지 못했으면 null 이다 */
  version: readonly [number, number] | null;
};

/** iOS 가 홈 화면 앱에서 웹 푸시를 받기 시작한 버전이다. */
const IOS_PUSH_VERSION = [16, 4] as const;

/**
 * 상태 하나를 고른다. 서버 → iOS 버전 → iOS 브라우저 탭 → 지원 → 허락 → 등록 순서다.
 *
 * <p>iOS 브라우저 탭은 `PushManager` 가 없어 지원하지 않는 것으로 보이므로 지원 판정보다 먼저 본다(스펙 DECISION22).
 */
export function pushScreenState(input: PushScreenInput): PushScreenState {
  if (!input.serverEnabled) return "server-off";
  if (
    input.ios.ios &&
    input.ios.version &&
    olderThan(input.ios.version, IOS_PUSH_VERSION)
  )
    return "ios-old";
  if (input.ios.ios && !input.standalone) return "ios-install";
  if (!input.supported || input.permission === "unsupported")
    return "unsupported";
  if (input.permission === "denied") return "denied";
  if (input.registered) return "registered";
  return "ready";
}

function olderThan(
  version: readonly [number, number],
  than: readonly [number, number],
): boolean {
  return (
    version[0] < than[0] || (version[0] === than[0] && version[1] < than[1])
  );
}

/**
 * user agent 로 iOS 인지와 버전을 읽는다.
 *
 * <p>iPadOS 의 Safari 는 Mac 처럼 보이는 user agent 를 보내므로, Mac 이면서 터치 지점이 둘 이상이면 iPad 로 본다.
 */
export function iosInfo(userAgent: string, maxTouchPoints: number): IosInfo {
  const mobile = /\b(iPhone|iPad|iPod)\b/.test(userAgent);
  const desktopIpad = /\bMacintosh\b/.test(userAgent) && maxTouchPoints > 1;
  if (!mobile && !desktopIpad) return { ios: false, version: null };
  const os = /\bOS (\d+)[_.](\d+)/.exec(userAgent);
  const safari = /\bVersion\/(\d+)\.(\d+)/.exec(userAgent);
  const match = mobile ? (os ?? safari) : safari;
  return {
    ios: true,
    version: match ? [Number(match[1]), Number(match[2])] : null,
  };
}

/**
 * 기기 이름을 OS 와 브라우저로 만든다(스펙 REQ2). 홈 화면 앱이면 브라우저 대신 「앱」 이다.
 *
 * <p>같은 이름에 번호를 붙이지 않는다(스펙 DECISION24). 목록은 등록한 날로 구분한다.
 */
export function deviceName(
  userAgent: string,
  standalone: boolean,
  maxTouchPoints: number,
): string {
  const os = osName(userAgent, maxTouchPoints);
  const app = standalone ? "앱" : browserName(userAgent);
  if (!os) return app ?? "브라우저";
  return `${os} ${app ?? "브라우저"}`;
}

function osName(userAgent: string, maxTouchPoints: number): string | null {
  if (/\biPhone\b/.test(userAgent)) return "iPhone";
  if (/\biPad\b/.test(userAgent)) return "iPad";
  if (/\bAndroid\b/.test(userAgent)) return "Android";
  if (/\bWindows\b/.test(userAgent)) return "Windows";
  if (/\bCrOS\b/.test(userAgent)) return "ChromeOS";
  if (/\bMacintosh\b/.test(userAgent))
    return maxTouchPoints > 1 ? "iPad" : "Mac";
  if (/\bLinux\b/.test(userAgent)) return "Linux";
  return null;
}

/** 다른 브라우저 이름이 함께 들어 있는 user agent 가 많아 구체적인 것부터 본다. */
function browserName(userAgent: string): string | null {
  if (/\bEdg(e|A|iOS)?\//.test(userAgent)) return "Edge";
  if (/\bSamsungBrowser\//.test(userAgent)) return "Samsung Internet";
  if (/\b(Firefox|FxiOS)\//.test(userAgent)) return "Firefox";
  if (/\b(Chrome|CriOS|Chromium)\//.test(userAgent)) return "Chrome";
  if (/\bSafari\//.test(userAgent)) return "Safari";
  return null;
}

/** base64url 공개 키를 구독에 넣을 바이트로 바꾼다. */
export function base64UrlToBytes(value: string): Uint8Array<ArrayBuffer> {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/** 두 키 바이트가 같은지 본다. 기존 구독이 지금 공개 키로 만든 것인지 확인한다. */
export function sameKey(
  left: ArrayBuffer | null | undefined,
  right: Uint8Array,
): boolean {
  if (!left) return false;
  const bytes = new Uint8Array(left);
  if (bytes.length !== right.length) return false;
  return bytes.every((byte, index) => byte === right[index]);
}
