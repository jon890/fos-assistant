import { withTimeout } from "@/lib/with-timeout";
import { base64UrlToBytes, sameKey } from "@/lib/push-device";

/**
 * 이 브라우저의 웹 푸시 구독과 푸시 서버 라우트를 다룬다(docs/features/attention/design.md 의 「웹」).
 *
 * <p>응답을 읽고 실패를 문구로 바꾸는 일은 부르는 쪽이 한다. 판정은 `push-device.ts` 가 한다.
 */

export type PushConfig = { enabled: boolean; publicKey: string | null };

/** 내 기기 한 줄이다. 주소와 키 원문은 오지 않는다 */
export type PushDevice = {
  id: string;
  name: string;
  registeredAt: string;
  /** 구독 주소의 SHA-256 소문자 hex. 이 브라우저 구독의 해시와 같으면 「이 기기」 다 */
  endpointHash: string;
};

/** 등록 요청 본문이다. `publicKey` 는 구독할 때 쓴 VAPID 공개 키로, 서버가 지금 키와 다르면 409 로 거절한다 */
export type PushRegistrationBody = {
  endpoint: string;
  keys: { p256dh: string; auth: string };
  name: string;
  timeZone: string;
  publicKey: string;
};

/** 서비스 워커 파일의 주소다. 범위는 사이트 전체다 */
const SERVICE_WORKER_URL = "/sw.js";

export function fetchPushConfig(): Promise<Response> {
  return fetch("/api/push/config", { cache: "no-store" });
}

export function fetchPushDevices(): Promise<Response> {
  return fetch("/api/push/devices", { cache: "no-store" });
}

export function putPushDevice(body: PushRegistrationBody): Promise<Response> {
  return fetch("/api/push/devices", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

export function deleteCurrentPushDevice(
  endpoint: string,
  signal?: AbortSignal,
): Promise<Response> {
  return fetch("/api/push/devices/current", {
    method: "DELETE",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ endpoint }),
    signal,
  });
}

/** 서비스 워커와 웹 푸시, 알림을 모두 지원하는 브라우저인지다. */
export function pushSupported(): boolean {
  return (
    typeof navigator !== "undefined" &&
    "serviceWorker" in navigator &&
    typeof window !== "undefined" &&
    "PushManager" in window &&
    "Notification" in window
  );
}

export function notificationPermission():
  NotificationPermission | "unsupported" {
  return typeof Notification === "undefined"
    ? "unsupported"
    : Notification.permission;
}

/** 홈 화면 앱으로 열었는지다. iOS Safari 는 `navigator.standalone` 도 준다. */
export function isStandalone(): boolean {
  const iosStandalone = (navigator as Navigator & { standalone?: boolean })
    .standalone;
  return (
    window.matchMedia("(display-mode: standalone)").matches ||
    iosStandalone === true
  );
}

/**
 * 이 브라우저의 지금 구독이다. 서비스 워커를 새로 설치하지 않는다.
 *
 * <p>화면을 보기만 하거나 로그아웃할 때 설치가 일어나지 않게 `getRegistration` 만 쓴다.
 */
export async function currentSubscription(): Promise<PushSubscription | null> {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator))
    return null;
  const registration = await navigator.serviceWorker.getRegistration("/");
  if (!registration) return null;
  return registration.pushManager.getSubscription();
}

/**
 * 지금 공개 키로 구독을 만든다. 서비스 워커는 이때만 등록한다.
 *
 * <p>기존 구독이 다른 키로 만든 것이면 끊고 새로 만든다. 서버가 키를 바꾼 뒤 옛 구독을 등록하면 거절된다.
 */
export async function subscribe(
  publicKey: string,
  timeoutMs = SUBSCRIBE_TIMEOUT_MS,
): Promise<PushSubscription> {
  return withTimeout(subscribeUnbounded(publicKey), timeoutMs);
}

/** 구독이 이 시간 안에 끝나지 않으면 포기한다. 푸시 서비스에 닿지 못하는 브라우저는 응답도 거절도 없이 멈춘다 */
export const SUBSCRIBE_TIMEOUT_MS = 20_000;

async function subscribeUnbounded(
  publicKey: string,
): Promise<PushSubscription> {
  await navigator.serviceWorker.register(SERVICE_WORKER_URL, { scope: "/" });
  const registration = await navigator.serviceWorker.ready;
  const key = base64UrlToBytes(publicKey);
  const existing = await registration.pushManager.getSubscription();
  if (existing) {
    if (sameKey(existing.options.applicationServerKey, key)) return existing;
    await existing.unsubscribe();
  }
  return registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: key,
  });
}

/** 구독 주소의 SHA-256 소문자 hex 다. 서버 목록의 `endpointHash` 와 같은 계산이다. */
export async function endpointHash(endpoint: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(endpoint),
  );
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

/** 등록 요청 본문을 만든다. 시간대는 브라우저 시간대다(스펙 REQ1). */
export function registrationBody(
  subscription: PushSubscription,
  name: string,
  publicKey: string,
): PushRegistrationBody {
  const json = subscription.toJSON();
  return {
    endpoint: subscription.endpoint,
    keys: { p256dh: json.keys?.p256dh ?? "", auth: json.keys?.auth ?? "" },
    name,
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    publicKey,
  };
}

/**
 * 로그아웃 전에 이 브라우저의 기기를 서버 목록에서 빼고 구독을 끊는다(docs/features/attention/spec.md 의 REQ9).
 *
 * <p>`timeoutMs` 안에서만 기다리고 실패해도 던지지 않는다. 로그아웃을 막지 않는다. 구독이 없으면 바로 끝난다.
 */
export async function releaseThisDevice(timeoutMs = 2000): Promise<void> {
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<void>((resolve) => {
    timer = setTimeout(() => {
      controller.abort();
      resolve();
    }, timeoutMs);
  });
  const release = (async () => {
    const subscription = await currentSubscription();
    if (!subscription) return;
    await Promise.allSettled([
      deleteCurrentPushDevice(subscription.endpoint, controller.signal),
      subscription.unsubscribe(),
    ]);
  })().catch(() => undefined);
  try {
    await Promise.race([release, deadline]);
  } finally {
    clearTimeout(timer);
  }
}
