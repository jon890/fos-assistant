/**
 * 사용자 브라우저의 서버 라우트를 부른다. 응답을 읽고 실패를 문구로 바꾸는 일은 부르는 쪽이 한다.
 */

export type BrowserStatus =
  "STOPPED" | "STARTING" | "RUNNING" | "STOPPING" | "FAILED";

/** `GET /api/browser` 의 응답이다. 기능이 꺼져 있으면 `enabled` 만 온다. */
export type BrowserView = {
  enabled: boolean;
  exists?: boolean;
  status?: BrowserStatus;
  lastError?: string | null;
  startedAt?: string | null;
  lastActiveAt?: string | null;
  idleTimeoutSeconds?: number;
};

export type AdminBrowser = {
  id: number;
  userId: number;
  userName: string;
  status: BrowserStatus;
  lastError: string | null;
  startedAt: string | null;
  lastActiveAt: string | null;
};

export type BrowserAction = "create" | "start" | "stop" | "delete";

/** 상태마다 화면 문구와 배지 색이다. */
const BROWSER_STATUS: Record<
  BrowserStatus,
  { label: string; variant: "outline" | "info" | "success" | "destructive" }
> = {
  STOPPED: { label: "꺼져 있어요", variant: "outline" },
  STARTING: { label: "켜는 중이에요", variant: "info" },
  RUNNING: { label: "켜져 있어요", variant: "success" },
  STOPPING: { label: "끄는 중이에요", variant: "info" },
  FAILED: { label: "켜지 못했어요", variant: "destructive" },
};

/** 끄거나 지우다 proxy 호출이 실패했을 때 Control Plane 이 `lastError` 에 남기는 코드다. */
const STOP_FAILED = "stop_failed";

/** 끄다 실패한 `FAILED` 인가. 그 밖의 `FAILED` 는 켜다 실패한 것이다. */
export function failedToStop(
  status: BrowserStatus,
  lastError: string | null | undefined,
): boolean {
  return status === "FAILED" && lastError === STOP_FAILED;
}

/** 상태의 화면 문구와 배지 색이다. `FAILED` 는 `lastError` 로 켜기 실패와 끄기 실패를 나눈다. */
export function browserStatusView(
  status: BrowserStatus,
  lastError: string | null | undefined,
): { label: string; variant: "outline" | "info" | "success" | "destructive" } {
  return failedToStop(status, lastError)
    ? { label: "끄지 못했어요", variant: "destructive" }
    : BROWSER_STATUS[status];
}

const ACTIONS: Record<BrowserAction, { path: string; method: string }> = {
  create: { path: "/api/browser", method: "POST" },
  start: { path: "/api/browser/start", method: "POST" },
  stop: { path: "/api/browser/stop", method: "POST" },
  delete: { path: "/api/browser", method: "DELETE" },
};

export function fetchBrowser(): Promise<Response> {
  return fetch("/api/browser", { cache: "no-store" });
}

export function browserAction(action: BrowserAction): Promise<Response> {
  const { path, method } = ACTIONS[action];
  return fetch(path, { method });
}

export function fetchAdminBrowsers(): Promise<Response> {
  return fetch("/api/admin/browsers", { cache: "no-store" });
}

export function adminBrowserAction(
  id: number,
  action: "stop" | "delete",
): Promise<Response> {
  return action === "stop"
    ? fetch(`/api/admin/browsers/${id}/stop`, { method: "POST" })
    : fetch(`/api/admin/browsers/${id}`, { method: "DELETE" });
}
